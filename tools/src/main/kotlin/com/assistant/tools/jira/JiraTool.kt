package com.assistant.tools.jira

import com.assistant.domain.*
import com.assistant.ports.CommandSpec
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

class JiraTool(
    private val baseUrl: String,
    private val email: String,
    private val apiToken: String
) : ToolPort {
    override val name = "jira"
    override val description = "Interacts with Jira. Commands: jira_search, jira_get_issue, jira_create_issue, jira_comment, jira_list_projects"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val auth: String = "Basic " + Base64.getEncoder()
        .encodeToString("$email:$apiToken".toByteArray())

    private val api = baseUrl.trimEnd('/') + "/rest/api/3"

    override fun commands(): List<CommandSpec> = listOf(
        CommandSpec(
            name = "jira_search",
            description = "Search Jira issues using JQL",
            params = listOf(
                ParamSpec("jql", "string", "JQL query string, e.g. 'project=PA AND status=Open'"),
                ParamSpec("max_results", "integer", "Maximum number of results to return (default 10)", required = false)
            )
        ),
        CommandSpec(
            name = "jira_get_issue",
            description = "Get details of a specific Jira issue by key",
            params = listOf(
                ParamSpec("key", "string", "Issue key, e.g. PA-123")
            )
        ),
        CommandSpec(
            name = "jira_create_issue",
            description = "Create a new Jira issue",
            params = listOf(
                ParamSpec("project", "string", "Project key, e.g. PA"),
                ParamSpec("type", "string", "Issue type, e.g. Bug, Task, Story"),
                ParamSpec("summary", "string", "Issue summary/title"),
                ParamSpec("description", "string", "Issue description", required = false)
            )
        ),
        CommandSpec(
            name = "jira_comment",
            description = "Add a comment to a Jira issue",
            params = listOf(
                ParamSpec("key", "string", "Issue key, e.g. PA-123"),
                ParamSpec("text", "string", "Comment text")
            )
        ),
        CommandSpec(
            name = "jira_list_projects",
            description = "List recent Jira projects",
            params = emptyList()
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        return when (call.name) {
        "jira_search" -> {
            val jql = call.arguments["jql"] as? String ?: return Observation.Error("Missing 'jql'")
            val max = call.arguments["max_results"]?.toString()?.toIntOrNull() ?: 10
            searchIssues(jql, max)
        }
        "jira_get_issue" -> {
            val key = call.arguments["key"] as? String ?: return Observation.Error("Missing 'key'")
            getIssue(key)
        }
        "jira_create_issue" -> {
            val project = call.arguments["project"] as? String ?: return Observation.Error("Missing 'project'")
            val type = call.arguments["type"] as? String ?: return Observation.Error("Missing 'type'")
            val summary = call.arguments["summary"] as? String ?: return Observation.Error("Missing 'summary'")
            val description = call.arguments["description"] as? String ?: ""
            createIssue(project, type, summary, description)
        }
        "jira_comment" -> {
            val key = call.arguments["key"] as? String ?: return Observation.Error("Missing 'key'")
            val text = call.arguments["text"] as? String ?: return Observation.Error("Missing 'text'")
            addComment(key, text)
        }
        "jira_list_projects" -> listProjects()
        else -> Observation.Error("Unknown jira command: ${call.name}")
        }
    }

    private fun apiGet(path: String): Result<JsonElement> = runCatching {
        val req = Request.Builder()
            .url("$api$path")
            .header("Authorization", auth)
            .header("Accept", "application/json")
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return Result.failure(Exception("Jira API error: ${resp.code} ${resp.message}"))
            resp.body?.string() ?: return Result.failure(Exception("Empty response from Jira"))
        }
        Json.parseToJsonElement(body)
    }

    private fun apiPost(path: String, payload: String): Result<JsonElement> = runCatching {
        val req = Request.Builder()
            .url("$api$path")
            .header("Authorization", auth)
            .header("Accept", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return Result.failure(Exception("Jira API error: ${resp.code} ${resp.message}"))
            resp.body?.string() ?: return Result.failure(Exception("Empty response from Jira"))
        }
        Json.parseToJsonElement(body)
    }

    private fun searchIssues(jql: String, max: Int): Observation {
        val encoded = java.net.URLEncoder.encode(jql, "UTF-8")
        return apiGet("/search?jql=$encoded&maxResults=$max").fold(
            onSuccess = { json ->
                val issues = json.jsonObject["issues"]?.jsonArray ?: return Observation.Success("No issues found.")
                val lines = issues.joinToString("\n") { issue ->
                    val obj = issue.jsonObject
                    val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: ""
                    val fields = obj["fields"]?.jsonObject ?: JsonObject(emptyMap())
                    val summary = fields["summary"]?.jsonPrimitive?.contentOrNull ?: ""
                    val status = fields["status"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                    val assignee = fields["assignee"]?.takeUnless { it is JsonNull }?.jsonObject?.get("displayName")?.jsonPrimitive?.contentOrNull ?: "Unassigned"
                    "$key [$status] $summary (assignee: $assignee)"
                }
                Observation.Success(if (lines.isBlank()) "No issues found." else lines)
            },
            onFailure = { Observation.Error(it.message ?: "Failed to search issues") }
        )
    }

    private fun getIssue(key: String): Observation =
        apiGet("/issue/$key").fold(
            onSuccess = { json ->
                val obj = json.jsonObject
                val fields = obj["fields"]?.jsonObject ?: JsonObject(emptyMap())
                val summary = fields["summary"]?.jsonPrimitive?.contentOrNull ?: ""
                val status = fields["status"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                val assignee = fields["assignee"]?.jsonObject?.get("displayName")?.jsonPrimitive?.contentOrNull ?: "Unassigned"
                val issueType = fields["issuetype"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
                val description = fields["description"]?.jsonObject
                    ?.get("content")?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("text")?.jsonPrimitive?.contentOrNull ?: "(no description)"
                val link = "${baseUrl.trimEnd('/')}/browse/$key"
                Observation.Success(
                    "$key [$issueType]: $summary\nStatus: $status | Assignee: $assignee\n$description\nLink: $link"
                )
            },
            onFailure = { Observation.Error(it.message ?: "Failed to get issue $key") }
        )

    private fun createIssue(project: String, type: String, summary: String, description: String): Observation {
        val payload = buildJsonObject {
            putJsonObject("fields") {
                putJsonObject("project") { put("key", project) }
                putJsonObject("issuetype") { put("name", type) }
                put("summary", summary)
                if (description.isNotBlank()) {
                    putJsonObject("description") {
                        put("type", "doc")
                        put("version", 1)
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "paragraph")
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", description)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.toString()
        return apiPost("/issue", payload).fold(
            onSuccess = { json ->
                val key = json.jsonObject["key"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                val link = "${baseUrl.trimEnd('/')}/browse/$key"
                Observation.Success("Issue $key created: $link")
            },
            onFailure = { Observation.Error(it.message ?: "Failed to create issue") }
        )
    }

    private fun addComment(key: String, text: String): Observation {
        val payload = buildJsonObject {
            putJsonObject("body") {
                put("type", "doc")
                put("version", 1)
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "paragraph")
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", text)
                            }
                        }
                    }
                }
            }
        }.toString()
        return apiPost("/issue/$key/comment", payload).fold(
            onSuccess = { Observation.Success("Comment added to $key.") },
            onFailure = { Observation.Error(it.message ?: "Failed to add comment") }
        )
    }

    private fun listProjects(): Observation =
        apiGet("/project?recent=20").fold(
            onSuccess = { json ->
                val projects = json.jsonArray.joinToString("\n") { p ->
                    val obj = p.jsonObject
                    val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    "$key — $name"
                }
                Observation.Success(if (projects.isBlank()) "No projects found." else projects)
            },
            onFailure = { Observation.Error(it.message ?: "Failed to list projects") }
        )
}
