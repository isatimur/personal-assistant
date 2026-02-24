package com.assistant.tools.github

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

class GitHubTool(private val token: String) : ToolPort {
    override val name = "github"
    override val description = "Interacts with GitHub repositories. Commands: github_list_prs, github_get_pr, github_list_issues, github_create_issue, github_get_file"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun commands(): List<CommandSpec> = listOf(
        CommandSpec(
            name = "github_list_prs",
            description = "List pull requests in a GitHub repository",
            params = listOf(
                ParamSpec("owner", "string", "Repository owner (user or org)"),
                ParamSpec("repo", "string", "Repository name"),
                ParamSpec("state", "string", "PR state: open, closed, or all (default: open)", required = false)
            )
        ),
        CommandSpec(
            name = "github_get_pr",
            description = "Get details of a specific pull request including description and review status",
            params = listOf(
                ParamSpec("owner", "string", "Repository owner"),
                ParamSpec("repo", "string", "Repository name"),
                ParamSpec("number", "integer", "Pull request number")
            )
        ),
        CommandSpec(
            name = "github_list_issues",
            description = "List issues in a GitHub repository",
            params = listOf(
                ParamSpec("owner", "string", "Repository owner"),
                ParamSpec("repo", "string", "Repository name"),
                ParamSpec("state", "string", "Issue state: open, closed, or all (default: open)", required = false)
            )
        ),
        CommandSpec(
            name = "github_create_issue",
            description = "Create a new issue in a GitHub repository",
            params = listOf(
                ParamSpec("owner", "string", "Repository owner"),
                ParamSpec("repo", "string", "Repository name"),
                ParamSpec("title", "string", "Issue title"),
                ParamSpec("body", "string", "Issue body/description")
            )
        ),
        CommandSpec(
            name = "github_get_file",
            description = "Read a file from a GitHub repository",
            params = listOf(
                ParamSpec("owner", "string", "Repository owner"),
                ParamSpec("repo", "string", "Repository name"),
                ParamSpec("path", "string", "File path within the repository"),
                ParamSpec("ref", "string", "Branch, tag, or commit SHA (default: HEAD)", required = false)
            )
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        return when (call.name) {
            "github_list_prs" -> {
                val owner = call.arguments["owner"] as? String ?: return Observation.Error("Missing 'owner'")
                val repo = call.arguments["repo"] as? String ?: return Observation.Error("Missing 'repo'")
                val state = call.arguments["state"] as? String ?: "open"
                listPrs(owner, repo, state)
            }
            "github_get_pr" -> {
                val owner = call.arguments["owner"] as? String ?: return Observation.Error("Missing 'owner'")
                val repo = call.arguments["repo"] as? String ?: return Observation.Error("Missing 'repo'")
                val number = call.arguments["number"]?.toString()?.toIntOrNull() ?: return Observation.Error("Missing 'number'")
                getPr(owner, repo, number)
            }
            "github_list_issues" -> {
                val owner = call.arguments["owner"] as? String ?: return Observation.Error("Missing 'owner'")
                val repo = call.arguments["repo"] as? String ?: return Observation.Error("Missing 'repo'")
                val state = call.arguments["state"] as? String ?: "open"
                listIssues(owner, repo, state)
            }
            "github_create_issue" -> {
                val owner = call.arguments["owner"] as? String ?: return Observation.Error("Missing 'owner'")
                val repo = call.arguments["repo"] as? String ?: return Observation.Error("Missing 'repo'")
                val title = call.arguments["title"] as? String ?: return Observation.Error("Missing 'title'")
                val body = call.arguments["body"] as? String ?: return Observation.Error("Missing 'body'")
                createIssue(owner, repo, title, body)
            }
            "github_get_file" -> {
                val owner = call.arguments["owner"] as? String ?: return Observation.Error("Missing 'owner'")
                val repo = call.arguments["repo"] as? String ?: return Observation.Error("Missing 'repo'")
                val path = call.arguments["path"] as? String ?: return Observation.Error("Missing 'path'")
                val ref = call.arguments["ref"] as? String ?: "HEAD"
                getFile(owner, repo, path, ref)
            }
            else -> Observation.Error("Unknown github command: ${call.name}")
        }
    }

    private fun apiRequest(url: String): Result<JsonElement> = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return Result.failure(Exception("GitHub API error: ${resp.code} ${resp.message}"))
            resp.body?.string() ?: return Result.failure(Exception("Empty response from GitHub"))
        }
        Json.parseToJsonElement(body)
    }

    private fun listPrs(owner: String, repo: String, state: String): Observation {
        val result = apiRequest("https://api.github.com/repos/$owner/$repo/pulls?state=$state&per_page=30")
        return result.fold(
            onSuccess = { json ->
                val prs = json.jsonArray.joinToString("\n") { pr ->
                    val obj = pr.jsonObject
                    val num = obj["number"]?.jsonPrimitive?.int ?: 0
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    val author = obj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.contentOrNull ?: ""
                    val state2 = obj["state"]?.jsonPrimitive?.contentOrNull ?: ""
                    "#$num [$state2] $title (by $author)"
                }
                Observation.Success(if (prs.isBlank()) "No pull requests found." else prs)
            },
            onFailure = { Observation.Error(it.message ?: "Failed to list PRs") }
        )
    }

    private fun getPr(owner: String, repo: String, number: Int): Observation {
        val result = apiRequest("https://api.github.com/repos/$owner/$repo/pulls/$number")
        return result.fold(
            onSuccess = { json ->
                val obj = json.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                val author = obj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.contentOrNull ?: ""
                val state = obj["state"]?.jsonPrimitive?.contentOrNull ?: ""
                val body = obj["body"]?.jsonPrimitive?.contentOrNull ?: "(no description)"
                val additions = obj["additions"]?.jsonPrimitive?.intOrNull ?: 0
                val deletions = obj["deletions"]?.jsonPrimitive?.intOrNull ?: 0
                val changedFiles = obj["changed_files"]?.jsonPrimitive?.intOrNull ?: 0
                val mergeableState = obj["mergeable_state"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                Observation.Success(
                    "PR #$number: $title\nAuthor: $author | State: $state | Mergeable: $mergeableState\n" +
                    "Changes: +$additions -$deletions across $changedFiles files\n\n$body"
                )
            },
            onFailure = { Observation.Error(it.message ?: "Failed to get PR") }
        )
    }

    private fun listIssues(owner: String, repo: String, state: String): Observation {
        val result = apiRequest("https://api.github.com/repos/$owner/$repo/issues?state=$state&per_page=30")
        return result.fold(
            onSuccess = { json ->
                val issues = json.jsonArray
                    .filter { it.jsonObject["pull_request"] == null }  // exclude PRs
                    .joinToString("\n") { issue ->
                        val obj = issue.jsonObject
                        val num = obj["number"]?.jsonPrimitive?.int ?: 0
                        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                        val labels = obj["labels"]?.jsonArray
                            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                            ?.joinToString(", ") ?: ""
                        val labelStr = if (labels.isNotBlank()) " [$labels]" else ""
                        "#$num$labelStr $title"
                    }
                Observation.Success(if (issues.isBlank()) "No issues found." else issues)
            },
            onFailure = { Observation.Error(it.message ?: "Failed to list issues") }
        )
    }

    private fun createIssue(owner: String, repo: String, title: String, body: String): Observation =
        runCatching {
            val payload = buildJsonObject {
                put("title", title)
                put("body", body)
            }.toString()
            val req = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/issues")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val responseBody = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching Observation.Error("GitHub API error: ${resp.code} ${resp.message}")
                resp.body?.string() ?: return@runCatching Observation.Error("Empty response")
            }
            val json = Json.parseToJsonElement(responseBody).jsonObject
            val url = json["html_url"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val num = json["number"]?.jsonPrimitive?.int ?: 0
            Observation.Success("Issue #$num created: $url")
        }.getOrElse { Observation.Error(it.message ?: "Failed to create issue") }

    private fun getFile(owner: String, repo: String, path: String, ref: String): Observation {
        val result = apiRequest("https://api.github.com/repos/$owner/$repo/contents/$path?ref=$ref")
        return result.fold(
            onSuccess = { json ->
                val obj = json.jsonObject
                val encoding = obj["encoding"]?.jsonPrimitive?.contentOrNull
                val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
                if (encoding == "base64") {
                    val decoded = java.util.Base64.getDecoder()
                        .decode(content.replace("\n", ""))
                        .toString(Charsets.UTF_8)
                    Observation.Success(decoded)
                } else {
                    Observation.Success(content)
                }
            },
            onFailure = { Observation.Error(it.message ?: "Failed to get file") }
        )
    }
}
