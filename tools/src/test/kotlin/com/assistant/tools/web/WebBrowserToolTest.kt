package com.assistant.tools.web

import com.assistant.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class WebBrowserToolTest {
    private val tool = WebBrowserTool()

    @Test
    fun `fetch known URL returns content`() = runTest {
        val result = tool.execute(ToolCall("web_fetch", mapOf("url" to "https://example.com")))
        assertTrue(result is Observation.Success)
        assertTrue((result as Observation.Success).result.isNotBlank())
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
