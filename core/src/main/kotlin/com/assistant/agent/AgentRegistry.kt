package com.assistant.agent

import java.io.File

interface AgentRegistry {
    fun register(name: String, address: String)
    fun resolve(name: String): String?
    fun all(): Map<String, String>
}

class StaticAgentRegistry(private val entries: Map<String, String>) : AgentRegistry {
    override fun register(name: String, address: String) { /* read-only */ }
    override fun resolve(name: String): String? = entries[name]
    override fun all(): Map<String, String> = entries
}

class FileSystemAgentRegistry(
    private val registryDir: File = File(System.getProperty("user.home"), ".assistant/agents")
) : AgentRegistry {

    init { registryDir.mkdirs() }

    override fun register(name: String, address: String) {
        val file = File(registryDir, "$name.address")
        file.writeText(address)
        Runtime.getRuntime().addShutdownHook(Thread { file.delete() })
    }

    override fun resolve(name: String): String? =
        File(registryDir, "$name.address").takeIf { it.exists() }?.readText()?.trim()

    override fun all(): Map<String, String> =
        registryDir.listFiles { f -> f.extension == "address" }
            ?.associate { f -> f.nameWithoutExtension to f.readText().trim() }
            ?: emptyMap()
}
