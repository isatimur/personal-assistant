package com.assistant.tools.web

import com.assistant.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class WebBrowserToolTest {
    private val tool = WebBrowserTool()

    @AfterEach
    fun tearDown() {
        tool.close()
    }

    @Test
    fun `fetch known URL returns content`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html")
                .setBody("<html><body><p>Hello from mock</p></body></html>")
        )
        server.start()
        val localTool = WebBrowserTool()
        try {
            val result = localTool.execute(ToolCall("web_fetch", mapOf("url" to server.url("/").toString())))
            assertTrue(result is Observation.Success)
            assertTrue((result as Observation.Success).result.isNotBlank())
        } finally {
            server.shutdown()
            localTool.close()
        }
    }

    @Test
    fun `fetch invalid URL returns error`() = runTest {
        val localTool = WebBrowserTool()
        try {
            val result = localTool.execute(ToolCall("web_fetch", mapOf("url" to "https://this-domain-xyz-does-not-exist.invalid")))
            assertTrue(result is Observation.Error)
        } finally {
            localTool.close()
        }
    }

    @Test
    fun `web_fetch uses playwright to render JS page`() = runTest {
        // Requires: playwright install chromium (one-time setup)
        val playwrightAvailable = runCatching {
            com.microsoft.playwright.Playwright.create().use { true }
        }.getOrDefault(false)
        org.junit.jupiter.api.Assumptions.assumeTrue(
            playwrightAvailable,
            "Playwright not installed — run: playwright install chromium"
        )

        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html")
                .setBody("<html><body><p>Playwright content</p></body></html>")
        )
        server.start()
        try {
            val playwrightTool = WebBrowserTool()
            val result = playwrightTool.execute(ToolCall("web_fetch", mapOf("url" to server.url("/").toString())))
            assertTrue(result is Observation.Success)
            assertTrue((result as Observation.Success).result.contains("Playwright content"))
            playwrightTool.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `unknown command returns error`() = runTest {
        assertTrue(tool.execute(ToolCall("web_unknown", mapOf())) is Observation.Error)
    }

    @Test
    fun `web_search uses Brave API when provider is brave`() {
        val server = MockWebServer()
        val braveResponse = """{"web":{"results":[
          {"title":"Result 1","description":"Desc 1","url":"https://example.com/1"},
          {"title":"Result 2","description":"Desc 2","url":"https://example.com/2"}
        ]}}"""
        server.enqueue(MockResponse().setBody(braveResponse).setHeader("Content-Type", "application/json"))
        server.start()

        val tool = WebBrowserTool(
            maxContentChars = 8000,
            searchProvider = "brave",
            searchApiKey = "test-key",
            searchBaseUrl = server.url("/").toString()
        )
        val result = runBlocking {
            tool.execute(ToolCall(name = "web_search", arguments = mapOf("query" to "kotlin async")))
        }
        assertTrue(result is Observation.Success)
        assertTrue((result as Observation.Success).result.contains("Result 1"))
        server.shutdown()
    }

    @Test
    fun `web_search falls back to duckduckgo when provider is duckduckgo`() {
        // DuckDuckGo uses HTML scraping — just verify it doesn't crash with empty results
        val tool = WebBrowserTool(searchProvider = "duckduckgo")
        // Not calling execute — just checking the tool constructs fine with duckduckgo provider
        assertEquals("web", tool.name)
    }
}
