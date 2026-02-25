package com.assistant.tools.linear

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LinearToolTest {
    private lateinit var server: MockWebServer
    private lateinit var tool: LinearTool

    @BeforeEach fun setUp() {
        server = MockWebServer()
        server.start()
        tool = LinearTool(
            apiKey = "test-api-key",
            endpointUrl = server.url("/graphql").toString()
        )
    }

    @AfterEach fun tearDown() { server.shutdown() }

    @Test
    fun `linear_search returns formatted issue list`() = runTest {
        val json = """
            {"data":{"issues":{"nodes":[
                {"id":"abc","identifier":"ENG-1","title":"Fix auth bug","state":{"name":"In Progress"},"assignee":{"name":"Alice"},"url":"https://linear.app/team/issue/ENG-1"},
                {"id":"def","identifier":"ENG-2","title":"Add caching","state":{"name":"Todo"},"assignee":null,"url":"https://linear.app/team/issue/ENG-2"}
            ]}}}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = tool.execute(ToolCall("linear_search", mapOf("query" to "fix")))

        assertTrue(result is Observation.Success)
        val text = (result as Observation.Success).result
        assertTrue(text.contains("ENG-1"))
        assertTrue(text.contains("Fix auth bug"))
        assertTrue(text.contains("In Progress"))
        assertTrue(text.contains("ENG-2"))
    }

    @Test
    fun `linear_search missing query returns error`() = runTest {
        val result = tool.execute(ToolCall("linear_search", emptyMap()))
        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("query"))
    }

    @Test
    fun `linear_get_issue returns issue details`() = runTest {
        val json = """
            {"data":{"issue":{"id":"abc","identifier":"ENG-5","title":"Critical security fix",
            "description":"Details here","state":{"name":"Done"},"assignee":{"name":"Bob"},
            "url":"https://linear.app/team/issue/ENG-5"}}}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = tool.execute(ToolCall("linear_get_issue", mapOf("id" to "ENG-5")))

        assertTrue(result is Observation.Success)
        val text = (result as Observation.Success).result
        assertTrue(text.contains("ENG-5"))
        assertTrue(text.contains("Critical security fix"))
        assertTrue(text.contains("Done"))
    }

    @Test
    fun `linear_get_issue missing id returns error`() = runTest {
        val result = tool.execute(ToolCall("linear_get_issue", emptyMap()))
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `linear_create_issue returns created issue`() = runTest {
        val json = """
            {"data":{"issueCreate":{"success":true,"issue":{"id":"new-id","identifier":"ENG-42","title":"New feature","url":"https://linear.app/team/issue/ENG-42"}}}}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = tool.execute(ToolCall("linear_create_issue", mapOf(
            "team_id" to "team-123",
            "title" to "New feature",
            "description" to "Implement X"
        )))

        assertTrue(result is Observation.Success)
        val text = (result as Observation.Success).result
        assertTrue(text.contains("ENG-42"))
    }

    @Test
    fun `linear_create_issue missing team_id returns error`() = runTest {
        val result = tool.execute(ToolCall("linear_create_issue", mapOf("title" to "x")))
        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("team_id"))
    }

    @Test
    fun `linear_list_teams returns team list`() = runTest {
        val json = """
            {"data":{"teams":{"nodes":[
                {"id":"t1","name":"Engineering","key":"ENG"},
                {"id":"t2","name":"Product","key":"PRD"}
            ]}}}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = tool.execute(ToolCall("linear_list_teams", emptyMap()))

        assertTrue(result is Observation.Success)
        val text = (result as Observation.Success).result
        assertTrue(text.contains("ENG"))
        assertTrue(text.contains("Engineering"))
        assertTrue(text.contains("PRD"))
    }

    @Test
    fun `linear_list_my_issues returns assigned issues`() = runTest {
        val json = """
            {"data":{"viewer":{"assignedIssues":{"nodes":[
                {"id":"x1","identifier":"ENG-10","title":"My task","state":{"name":"In Progress"},"url":"https://linear.app/x"},
                {"id":"x2","identifier":"ENG-11","title":"Review PR","state":{"name":"Done"},"url":"https://linear.app/y"}
            ]}}}}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = tool.execute(ToolCall("linear_list_my_issues", emptyMap()))

        assertTrue(result is Observation.Success)
        val text = (result as Observation.Success).result
        assertTrue(text.contains("ENG-10"))
        assertTrue(text.contains("My task"))
        assertTrue(text.contains("ENG-11"))
    }

    @Test
    fun `graphql error returns Observation_Error`() = runTest {
        val json = """{"errors":[{"message":"Authentication required","locations":[]}]}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = tool.execute(ToolCall("linear_list_teams", emptyMap()))

        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("Authentication required"))
    }

    @Test
    fun `http error returns Observation_Error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val result = tool.execute(ToolCall("linear_list_teams", emptyMap()))

        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("500"))
    }

    @Test
    fun `unknown command returns Observation_Error`() = runTest {
        val result = tool.execute(ToolCall("linear_unknown", emptyMap()))
        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("Unknown linear command"))
    }
}
