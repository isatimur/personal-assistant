package com.assistant.mcp

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class McpToolPortTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Build a McpSchema.Tool with a simple JSON schema (no properties). */
    private fun makeTool(
        name: String,
        description: String,
        properties: Map<String, Any> = emptyMap(),
        required: List<String> = emptyList()
    ): McpSchema.Tool {
        val schema = McpSchema.JsonSchema(
            /* type               */ "object",
            /* properties         */ properties,
            /* required           */ required,
            /* additionalProperties */ null,
            /* defs               */ null,
            /* definitions        */ null
        )
        return McpSchema.Tool(name, description, schema)
    }

    /** Property map entry matching the SDK's expected format: {"type": "..."} */
    private fun prop(type: String): Map<String, Any> = mapOf("type" to type)

    private fun makeListResult(vararg tools: McpSchema.Tool): McpSchema.ListToolsResult =
        McpSchema.ListToolsResult(tools.toList(), null)

    private fun makeCallResult(
        text: String,
        isError: Boolean = false
    ): McpSchema.CallToolResult =
        McpSchema.CallToolResult(
            listOf(McpSchema.TextContent(text)),
            isError
        )

    // ── test: name equals serverName ─────────────────────────────────────────

    @Test
    fun `name equals serverName`() {
        val client = mockk<McpSyncClient>(relaxed = true)
        val port = McpToolPort("filesystem", client)
        assertEquals("filesystem", port.name)
    }

    @Test
    fun `description contains server name`() {
        val client = mockk<McpSyncClient>(relaxed = true)
        val port = McpToolPort("filesystem", client)
        assertEquals("MCP server: filesystem", port.description)
    }

    // ── test: commands() ──────────────────────────────────────────────────────

    @Test
    fun `commands returns correct CommandSpecs from listTools`() {
        val client = mockk<McpSyncClient>(relaxed = true)
        val tool = makeTool(
            name = "read_file",
            description = "Read a file",
            properties = mapOf(
                "path" to prop("string"),
                "count" to prop("integer"),
                "flag" to prop("boolean"),
                "size" to prop("number")
            ),
            required = listOf("path", "count")
        )
        every { client.listTools() } returns makeListResult(tool)

        val port = McpToolPort("filesystem", client)
        val commands = port.commands()

        assertEquals(1, commands.size)
        val cmd = commands[0]
        assertEquals("filesystem_read_file", cmd.name)
        assertEquals("Read a file", cmd.description)
        assertEquals(4, cmd.params.size)

        val byName = cmd.params.associateBy { it.name }

        // string stays "string"
        assertEquals("string", byName["path"]!!.type)
        assertTrue(byName["path"]!!.required)

        // integer → "integer"
        assertEquals("integer", byName["count"]!!.type)
        assertTrue(byName["count"]!!.required)

        // boolean → "boolean"
        assertEquals("boolean", byName["flag"]!!.type)
        assertFalse(byName["flag"]!!.required)

        // number → "string" (floating-point; no ParamSpec equivalent)
        assertEquals("string", byName["size"]!!.type)
        assertFalse(byName["size"]!!.required)
    }

    @Test
    fun `commands are lazy-cached — listTools called only once`() {
        val client = mockk<McpSyncClient>(relaxed = true)
        every { client.listTools() } returns makeListResult(makeTool("t", "desc"))

        val port = McpToolPort("x", client)
        port.commands()
        port.commands()

        verify(exactly = 1) { client.listTools() }
    }

    @Test
    fun `commands returns empty list when listTools throws`() {
        val client = mockk<McpSyncClient>(relaxed = true)
        every { client.listTools() } throws RuntimeException("connection refused")

        val port = McpToolPort("broken", client)
        val commands = port.commands()

        assertTrue(commands.isEmpty())
    }

    // ── test: execute() success ───────────────────────────────────────────────

    @Test
    fun `execute success — calls callTool with correct args and returns Success`() = runTest {
        val client = mockk<McpSyncClient>(relaxed = true)
        val args = mapOf("path" to "/tmp/file.txt", "count" to 10)
        every { client.callTool(any()) } returns makeCallResult("file contents here")

        val port = McpToolPort("filesystem", client)
        // LLM calls the prefixed name; execute() must strip the prefix before forwarding.
        val result = port.execute(ToolCall("filesystem_read_file", args))

        assertInstanceOf(Observation.Success::class.java, result)
        assertEquals("file contents here", (result as Observation.Success).result)

        verify(exactly = 1) {
            client.callTool(
                withArg { req ->
                    req.name() == "read_file" && req.arguments() == args
                }
            )
        }
    }

    @Test
    fun `execute concatenates multiple text content pieces`() = runTest {
        val client = mockk<McpSyncClient>(relaxed = true)
        every { client.callTool(any()) } returns McpSchema.CallToolResult(
            listOf(
                McpSchema.TextContent("hello"),
                McpSchema.TextContent(" world")
            ),
            false
        )

        val port = McpToolPort("fs", client)
        val result = port.execute(ToolCall("tool", emptyMap()))

        assertInstanceOf(Observation.Success::class.java, result)
        assertEquals("hello world", (result as Observation.Success).result)
    }

    // ── test: execute() isError=true ──────────────────────────────────────────

    @Test
    fun `execute returns Error when isError is true`() = runTest {
        val client = mockk<McpSyncClient>(relaxed = true)
        every { client.callTool(any()) } returns makeCallResult("something went wrong", isError = true)

        val port = McpToolPort("filesystem", client)
        val result = port.execute(ToolCall("read_file", emptyMap()))

        assertInstanceOf(Observation.Error::class.java, result)
        assertEquals("something went wrong", (result as Observation.Error).message)
    }

    // ── test: execute() non-text content ──────────────────────────────────────

    @Test
    fun `execute returns Error when tool returns only non-text content`() = runTest {
        val client = mockk<McpSyncClient>(relaxed = true)
        every { client.listTools() } returns McpSchema.ListToolsResult(emptyList(), null)
        // Return a non-TextContent (simulate ImageContent or other)
        val imageContent = mockk<McpSchema.Content>()  // Not a TextContent
        every { client.callTool(any()) } returns McpSchema.CallToolResult(listOf(imageContent), false)
        val port = McpToolPort("filesystem", client)
        val result = port.execute(ToolCall("filesystem_download_image", mapOf()))
        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("non-text"))
    }

    // ── test: execute() throws ────────────────────────────────────────────────

    @Test
    fun `execute returns Error when callTool throws`() = runTest {
        val client = mockk<McpSyncClient>(relaxed = true)
        every { client.callTool(any()) } throws RuntimeException("timeout")

        val port = McpToolPort("filesystem", client)
        val result = port.execute(ToolCall("read_file", emptyMap()))

        assertInstanceOf(Observation.Error::class.java, result)
        assertTrue((result as Observation.Error).message.contains("timeout"))
    }

    // ── test: close() ─────────────────────────────────────────────────────────

    @Test
    fun `close delegates to client close`() {
        val client = mockk<McpSyncClient>(relaxed = true)
        val port = McpToolPort("filesystem", client)

        port.close()

        verify(exactly = 1) { client.close() }
    }

    @Test
    fun `close swallows exceptions from client`() {
        val client = mockk<McpSyncClient>(relaxed = true)
        every { client.close() } throws RuntimeException("already closed")

        val port = McpToolPort("filesystem", client)
        assertDoesNotThrow { port.close() }
    }
}
