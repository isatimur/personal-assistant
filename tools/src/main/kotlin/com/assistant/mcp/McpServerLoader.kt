package com.assistant.mcp

import kotlinx.serialization.json.*
import java.io.File
import java.util.logging.Logger

data class McpServerConfig(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap()
)

object McpServerLoader {
    private val logger = Logger.getLogger(McpServerLoader::class.java.name)

    /** Parses [configFile] in Claude Code mcpServers format. Returns empty list on any error. */
    fun loadConfigs(configFile: File): List<McpServerConfig> {
        if (!configFile.exists()) return emptyList()
        return try {
            val root = Json.parseToJsonElement(configFile.readText()).jsonObject
            val servers = root["mcpServers"]?.jsonObject ?: return emptyList()
            servers.entries.mapNotNull { (name, value) ->
                try {
                    val obj = value.jsonObject
                    val args = obj["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                    val env = obj["env"]?.jsonObject?.mapValues { (_, v) -> v.jsonPrimitive.content } ?: emptyMap()
                    McpServerConfig(
                        name = name,
                        command = obj["command"]!!.jsonPrimitive.content,
                        args = args,
                        env = env
                    )
                } catch (e: Exception) {
                    logger.warning("Skipping MCP server '$name': ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            logger.warning("Failed to parse mcp-servers.json: ${e.message}")
            emptyList()
        }
    }

    /** Reads [globalDir]/mcp-servers.json and starts all configured MCP servers. */
    fun load(globalDir: File): List<McpToolPort> {
        val configFile = File(globalDir, "mcp-servers.json")
        return loadConfigs(configFile).mapNotNull { config ->
            McpToolPort.create(config).also {
                if (it != null) logger.info("MCP server '${config.name}' started (${it.commands().size} tools)")
            }
        }
    }
}
