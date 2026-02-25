package com.assistant.tools.web

import com.assistant.domain.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class WebBrowserToolTest {
    private val tool = WebBrowserTool()

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
        try {
            val result = tool.execute(ToolCall("web_fetch", mapOf("url" to server.url("/").toString())))
            assertTrue(result is Observation.Success)
            assertTrue((result as Observation.Success).result.isNotBlank())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `fetch invalid URL returns error`() = runTest {
        val result = tool.execute(ToolCall("web_fetch", mapOf("url" to "https://this-domain-xyz-does-not-exist.invalid")))
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `unknown command returns error`() = runTest {
        assertTrue(tool.execute(ToolCall("web_unknown", mapOf())) is Observation.Error)
    }
}
