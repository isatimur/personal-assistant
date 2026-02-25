package com.assistant.tools.linear

import com.assistant.domain.*
import com.assistant.ports.CommandSpec
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class LinearTool(
    private val apiKey: String,
    private val endpointUrl: String = "https://api.linear.app/graphql"
) : ToolPort {
    override val name = "linear"
    override val description = "Interacts with Linear. Commands: linear_search, linear_get_issue, linear_create_issue, linear_list_teams, linear_list_my_issues"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val endpoint = endpointUrl

    override fun commands(): List<CommandSpec> = listOf(
        CommandSpec(
            name = "linear_search",
            description = "Search Linear issues by title",
            params = listOf(
                ParamSpec("query", "string", "Search query to match against issue titles"),
                ParamSpec("limit", "integer", "Maximum number of results (default 10)", required = false)
            )
        ),
        CommandSpec(
            name = "linear_get_issue",
            description = "Get details of a specific Linear issue by ID",
            params = listOf(
                ParamSpec("id", "string", "Linear issue ID (e.g. abc-123)")
            )
        ),
        CommandSpec(
            name = "linear_create_issue",
            description = "Create a new Linear issue",
            params = listOf(
                ParamSpec("team_id", "string", "Team ID to create the issue in"),
                ParamSpec("title", "string", "Issue title"),
                ParamSpec("description", "string", "Issue description in markdown", required = false)
            )
        ),
        CommandSpec(
            name = "linear_list_teams",
            description = "List all Linear teams",
            params = emptyList()
        ),
        CommandSpec(
            name = "linear_list_my_issues",
            description = "List issues assigned to me in Linear",
            params = listOf(
                ParamSpec("limit", "integer", "Maximum number of results (default 10)", required = false)
            )
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        return when (call.name) {
            "linear_search" -> {
                val query = call.arguments["query"] as? String ?: return Observation.Error("Missing 'query'")
                val limit = call.arguments["limit"]?.toString()?.toIntOrNull() ?: 10
                searchIssues(query, limit)
            }
            "linear_get_issue" -> {
                val id = call.arguments["id"] as? String ?: return Observation.Error("Missing 'id'")
                getIssue(id)
            }
            "linear_create_issue" -> {
                val teamId = call.arguments["team_id"] as? String ?: return Observation.Error("Missing 'team_id'")
                val title = call.arguments["title"] as? String ?: return Observation.Error("Missing 'title'")
                val description = call.arguments["description"] as? String ?: ""
                createIssue(teamId, title, description)
            }
            "linear_list_teams" -> listTeams()
            "linear_list_my_issues" -> {
                val limit = call.arguments["limit"]?.toString()?.toIntOrNull() ?: 10
                listMyIssues(limit)
            }
            else -> Observation.Error("Unknown linear command: ${call.name}")
        }
    }

    private fun graphql(query: String, variables: JsonObject = JsonObject(emptyMap())): Result<JsonElement> = runCatching {
        val payload = buildJsonObject {
            put("query", query)
            if (variables.isNotEmpty()) put("variables", variables)
        }.toString()
        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", apiKey)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return Result.failure(Exception("Linear API error: ${resp.code} ${resp.message}"))
            resp.body?.string() ?: return Result.failure(Exception("Empty response from Linear"))
        }
        val json = Json.parseToJsonElement(body).jsonObject
        val errors = json["errors"]?.jsonArray
        if (errors != null && errors.isNotEmpty()) {
            val msg = errors.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "GraphQL error"
            return Result.failure(Exception(msg))
        }
        json["data"] ?: JsonObject(emptyMap())
    }

    private fun searchIssues(query: String, limit: Int): Observation {
        val gql = "query(\$filter: IssueFilter, \$first: Int) { issues(filter: \$filter, first: \$first) { nodes { id identifier title state { name } assignee { name } url } } }"
        val variables = buildJsonObject {
            putJsonObject("filter") {
                putJsonObject("title") { put("containsIgnoreCase", query) }
            }
            put("first", limit)
        }
        return graphql(gql, variables).fold(
            onSuccess = { data ->
                val nodes = data.jsonObject["issues"]?.jsonObject?.get("nodes")?.jsonArray
                    ?: return Observation.Success("No issues found.")
                val lines = nodes.joinToString("\n") { issue ->
                    val obj = issue.jsonObject
                    val id = obj["identifier"]?.jsonPrimitive?.contentOrNull ?: ""
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    val state = obj["state"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                    val assignee = obj["assignee"]?.takeUnless { it is JsonNull }?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: "Unassigned"
                    "$id [$state] $title (assignee: $assignee)"
                }
                Observation.Success(if (lines.isBlank()) "No issues found." else lines)
            },
            onFailure = { Observation.Error(it.message ?: "Failed to search issues") }
        )
    }

    private fun getIssue(id: String): Observation {
        val gql = "query(\$id: String!) { issue(id: \$id) { id identifier title description state { name } assignee { name } url } }"
        val variables = buildJsonObject { put("id", id) }
        return graphql(gql, variables).fold(
            onSuccess = { data ->
                val issue = data.jsonObject["issue"]?.jsonObject
                    ?: return Observation.Error("Issue $id not found")
                val identifier = issue["identifier"]?.jsonPrimitive?.contentOrNull ?: id
                val title = issue["title"]?.jsonPrimitive?.contentOrNull ?: ""
                val state = issue["state"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                val assignee = issue["assignee"]?.takeUnless { it is JsonNull }?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: "Unassigned"
                val description = issue["description"]?.jsonPrimitive?.contentOrNull ?: "(no description)"
                val url = issue["url"]?.jsonPrimitive?.contentOrNull ?: ""
                Observation.Success("$identifier: $title\nState: $state | Assignee: $assignee\n$description\nURL: $url")
            },
            onFailure = { Observation.Error(it.message ?: "Failed to get issue $id") }
        )
    }

    private fun createIssue(teamId: String, title: String, description: String): Observation {
        val gql = "mutation(\$input: IssueCreateInput!) { issueCreate(input: \$input) { success issue { id identifier title url } } }"
        val input = buildJsonObject {
            put("teamId", teamId)
            put("title", title)
            if (description.isNotBlank()) put("description", description)
        }
        val variables = buildJsonObject { put("input", input) }
        return graphql(gql, variables).fold(
            onSuccess = { data ->
                val result = data.jsonObject["issueCreate"]?.jsonObject
                    ?: return Observation.Error("No issueCreate in response")
                val issue = result["issue"]?.jsonObject
                    ?: return Observation.Error("Issue creation failed")
                val identifier = issue["identifier"]?.jsonPrimitive?.contentOrNull ?: "?"
                val url = issue["url"]?.jsonPrimitive?.contentOrNull ?: ""
                Observation.Success("Issue $identifier created: $url")
            },
            onFailure = { Observation.Error(it.message ?: "Failed to create issue") }
        )
    }

    private fun listTeams(): Observation {
        val gql = "{ teams { nodes { id name key } } }"
        return graphql(gql).fold(
            onSuccess = { data ->
                val teams = data.jsonObject["teams"]?.jsonObject?.get("nodes")?.jsonArray
                    ?: return Observation.Success("No teams found.")
                val lines = teams.joinToString("\n") { team ->
                    val obj = team.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: ""
                    "$key \u2014 $name (id: $id)"
                }
                Observation.Success(if (lines.isBlank()) "No teams found." else lines)
            },
            onFailure = { Observation.Error(it.message ?: "Failed to list teams") }
        )
    }

    private fun listMyIssues(limit: Int): Observation {
        val gql = "query(\$first: Int) { viewer { assignedIssues(first: \$first) { nodes { id identifier title state { name } url } } } }"
        val variables = buildJsonObject { put("first", limit) }
        return graphql(gql, variables).fold(
            onSuccess = { data ->
                val nodes = data.jsonObject["viewer"]?.jsonObject
                    ?.get("assignedIssues")?.jsonObject
                    ?.get("nodes")?.jsonArray
                    ?: return Observation.Success("No assigned issues found.")
                val lines = nodes.joinToString("\n") { issue ->
                    val obj = issue.jsonObject
                    val id = obj["identifier"]?.jsonPrimitive?.contentOrNull ?: ""
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    val state = obj["state"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                    "$id [$state] $title"
                }
                Observation.Success(if (lines.isBlank()) "No assigned issues found." else lines)
            },
            onFailure = { Observation.Error(it.message ?: "Failed to list my issues") }
        )
    }
}
