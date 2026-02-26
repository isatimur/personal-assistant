package com.assistant.tools.http

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HttpToolTest {

    @Test
    fun `http_get returns response body`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("Hello from server").setResponseCode(200))
        server.start()

        val tool = HttpTool()
        val result = runBlocking {
            tool.execute(ToolCall(name = "http_get", arguments = mapOf("url" to server.url("/test").toString())))
        }
        assertTrue(result is Observation.Success)
        assertTrue((result as Observation.Success).result.contains("Hello from server"))
        server.shutdown()
    }

    @Test
    fun `http_post sends body and returns response`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"status":"ok"}""").setResponseCode(201))
        server.start()

        val tool = HttpTool()
        val result = runBlocking {
            tool.execute(ToolCall(name = "http_post", arguments = mapOf(
                "url" to server.url("/api").toString(),
                "body" to """{"name":"test"}"""
            )))
        }
        assertTrue(result is Observation.Success)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.body.readUtf8().contains("name"))
        server.shutdown()
    }

    @Test
    fun `http_get with missing url returns error`() {
        val tool = HttpTool()
        val result = runBlocking {
            tool.execute(ToolCall(name = "http_get", arguments = emptyMap()))
        }
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `unknown command returns error`() {
        val tool = HttpTool()
        val result = runBlocking {
            tool.execute(ToolCall(name = "http_patch", arguments = emptyMap()))
        }
        assertTrue(result is Observation.Error)
    }
}
