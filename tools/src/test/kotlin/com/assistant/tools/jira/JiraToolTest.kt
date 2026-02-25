package com.assistant.tools.jira

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JiraToolTest {
    private lateinit var server: MockWebServer
    private lateinit var tool: JiraTool

    @BeforeEach fun setUp() {
        server = MockWebServer()
        server.start()
        tool = JiraTool(
            baseUrl = server.url("/").toString().trimEnd('/'),
            email = "test@example.com",
            apiToken = "test-token"
        )
    }

    @AfterEach fun tearDown() { server.shutdown() }

    @Test
    fun `jira_search returns formatted issue list`() = runTest {
        val json = """
            {"issues":[
                {"key":"PA-1","fields":{"summary":"Fix login bug","status":{"name":"Open"},"assignee":{"displayName":"Alice"}}},
                {"key":"PA-2","fields":{"summary":"Add dark mode","status":{"name":"In Progress"},"assignee":null}}
            ]}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = tool.execute(ToolCall("jira_search", mapOf("jql" to "project=PA")))

        assertTrue(result is Observation.Success)
        val text = (result as Observation.Success).result
        assertTrue(text.contains("PA-1"))
        assertTrue(text.contains("Fix login bug"))
        assertTrue(text.contains("Open"))
        assertTrue(text.contains("PA-2"))
    }

    @Test
    fun `jira_search returns no issues message when empty`() = runTest {
        server.enqueue(MockResponse().setBody("""{"issues":[]}""").setResponseCode(200))

        val result = tool.execute(ToolCall("jira_search", mapOf("jql" to "project=EMPTY")))

        assertTrue(result is Observation.Success)
        assertTrue((result as Observation.Success).result.contains("No issues found"))
    }

    @Test
    fun `jira_search missing jql returns error`() = runTest {
        val result = tool.execute(ToolCall("jira_search", emptyMap()))
        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("jql"))
    }

    @Test
    fun `jira_get_issue returns issue details`() = runTest {
        val json = """
            {"key":"PA-5","fields":{"summary":"Critical bug","status":{"name":"Done"},
            "assignee":{"displayName":"Bob"},"issuetype":{"name":"Bug"},
            "description":{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"Detailed description"}]}]}}}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = tool.execute(ToolCall("jira_get_issue", mapOf("key" to "PA-5")))

        assertTrue(result is Observation.Success)
        val text = (result as Observation.Success).result
        assertTrue(text.contains("PA-5"))
        assertTrue(text.contains("Critical bug"))
        assertTrue(text.contains("Done"))
    }

    @Test
    fun `jira_create_issue returns created issue key and link`() = runTest {
        val json = """{"key":"PA-99","id":"100","self":"https://example.atlassian.net/rest/api/3/issue/100"}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(201))

        val result = tool.execute(ToolCall("jira_create_issue", mapOf(
            "project" to "PA",
            "type" to "Task",
            "summary" to "New task"
        )))

        assertTrue(result is Observation.Success)
        val text = (result as Observation.Success).result
        assertTrue(text.contains("PA-99"))
    }

    @Test
    fun `jira_create_issue missing project returns error`() = runTest {
        val result = tool.execute(ToolCall("jira_create_issue", mapOf("type" to "Task", "summary" to "x")))
        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("project"))
    }

    @Test
    fun `jira_comment adds comment successfully`() = runTest {
        val json = """{"id":"10001","body":{"type":"doc","version":1}}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(201))

        val result = tool.execute(ToolCall("jira_comment", mapOf("key" to "PA-1", "text" to "Looks good")))

        assertTrue(result is Observation.Success)
        assertTrue((result as Observation.Success).result.contains("PA-1"))
    }

    @Test
    fun `jira_list_projects returns project list`() = runTest {
        val json = """[{"key":"PA","name":"Personal Assistant"},{"key":"INT","name":"Integrations"}]"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = tool.execute(ToolCall("jira_list_projects", emptyMap()))

        assertTrue(result is Observation.Success)
        val text = (result as Observation.Success).result
        assertTrue(text.contains("PA"))
        assertTrue(text.contains("Personal Assistant"))
    }

    @Test
    fun `api error returns Observation_Error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"errorMessages":["Unauthorized"]}"""))

        val result = tool.execute(ToolCall("jira_search", mapOf("jql" to "project=PA")))

        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("401"))
    }

    @Test
    fun `unknown command returns Observation_Error`() = runTest {
        val result = tool.execute(ToolCall("jira_unknown", emptyMap()))
        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("Unknown jira command"))
    }
}
