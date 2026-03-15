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

    fun loadConfigs(configFile: File): List<McpServerConfig> {
        if (!configFile.exists()) return emptyList()
        return try {
            val root = Json.parseToJsonElement(configFile.readText()).jsonObject
            val servers = root["mcpServers"]?.jsonObject ?: return emptyList()
            servers.entries.map { (name, value) ->
                val obj = value.jsonObject
                val args = obj["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val env = obj["env"]?.jsonObject?.mapValues { (_, v) -> v.jsonPrimitive.content } ?: emptyMap()
                McpServerConfig(
                    name = name,
                    command = obj["command"]!!.jsonPrimitive.content,
                    args = args,
                    env = env
                )
            }
        } catch (e: Exception) {
            logger.warning("Failed to parse mcp-servers.json: ${e.message}")
            emptyList()
        }
    }
}
