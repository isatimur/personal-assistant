package com.assistant.mcp

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import com.assistant.ports.CommandSpec
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.logging.Logger

class McpToolPort(
    private val serverName: String,
    private val client: McpSyncClient
) : ToolPort, java.io.Closeable {

    // NOTE: server names must not collide with existing tool names (e.g. "shell", "web", "fs").
    override val name: String get() = serverName
    override val description: String get() = "MCP server: $serverName"

    // Lazy-cached list of commands fetched once from the server.
    private val cachedCommands: List<CommandSpec> by lazy {
        try {
            val result = client.listTools()
            result.tools().map { tool -> tool.toCommandSpec(serverName) }
        } catch (e: Exception) {
            logger.warning("[$serverName] Failed to list tools: ${e.message}")
            emptyList()
        }
    }

    override fun commands(): List<CommandSpec> = cachedCommands

    override suspend fun execute(call: ToolCall): Observation {
        // Strip the "${serverName}_" prefix before forwarding to the MCP SDK,
        // which expects the original tool name (e.g. "read_file", not "filesystem_read_file").
        val mcpToolName = call.name.removePrefix("${serverName}_")
        return try {
            val args = call.arguments
            val request = McpSchema.CallToolRequest(mcpToolName, args)
            val result = withContext(Dispatchers.IO) { client.callTool(request) }

            val text = result.content()
                .filterIsInstance<McpSchema.TextContent>()
                .joinToString("") { it.text() }

            if (result.isError() == true) {
                Observation.Error(text)
            } else {
                Observation.Success(text)
            }
        } catch (e: Exception) {
            Observation.Error(e.message ?: "Unknown error calling tool '${call.name}'")
        }
    }

    override fun close() {
        try {
            client.close()
        } catch (_: Exception) {
            // Swallow — client may already be closed.
        }
    }

    companion object {
        private val logger = Logger.getLogger(McpToolPort::class.java.name)

        /**
         * Creates an [McpToolPort] by launching the subprocess described by [config]
         * and initializing the MCP handshake. Returns `null` if the server cannot be started.
         */
        fun create(config: McpServerConfig): McpToolPort? {
            return try {
                val params = ServerParameters.builder(config.command)
                    .args(config.args)
                    .env(config.env)
                    .build()

                val transport = StdioClientTransport(params)
                val client: McpSyncClient = McpClient.sync(transport).build()
                client.initialize()

                McpToolPort(config.name, client)
            } catch (e: Exception) {
                logger.warning("Failed to start MCP server '${config.name}': ${e.message}")
                null
            }
        }
    }
}

// ── Private extension ─────────────────────────────────────────────────────────

private fun McpSchema.Tool.toCommandSpec(serverName: String): CommandSpec {
    val schema = inputSchema()
    val properties: Map<String, Any> = schema?.properties() ?: emptyMap()
    val required: Set<String> = schema?.required()?.toSet() ?: emptySet()

    val params = properties.map { (propName, propValue) ->
        @Suppress("UNCHECKED_CAST")
        val propMap = propValue as? Map<String, Any> ?: emptyMap()
        val jsonType = propMap["type"] as? String ?: "string"
        val mappedType = when (jsonType) {
            "integer" -> "integer"
            "boolean" -> "boolean"
            else -> "string"  // includes "number", "object", "array", and unknown types
        }
        val paramDescription = propMap["description"] as? String ?: propName
        ParamSpec(
            name = propName,
            type = mappedType,
            description = paramDescription,
            required = propName in required
        )
    }

    return CommandSpec(
        name = "${serverName}_${name()}",
        description = description() ?: "",
        params = params
    )
}
