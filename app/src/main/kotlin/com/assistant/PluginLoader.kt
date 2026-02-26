package com.assistant

import com.assistant.ports.*
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.logging.Logger

class PluginLoader(
    private val pluginsDir: File = File(System.getProperty("user.home"), ".assistant/plugins")
) {
    private val logger = Logger.getLogger(PluginLoader::class.java.name)

    private val classLoader: ClassLoader by lazy {
        val jars = pluginsDir.takeIf { it.isDirectory }
            ?.listFiles { f -> f.extension == "jar" }
            ?.takeIf { it.isNotEmpty() }
            ?: return@lazy Thread.currentThread().contextClassLoader
        URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), Thread.currentThread().contextClassLoader)
    }

    fun loadTools(): List<ToolPort> = load(ToolPort::class.java)
    fun loadChannels(): List<ChannelPort> = load(ChannelPort::class.java)
    fun loadLlmProviders(): List<LlmPort> = load(LlmPort::class.java)
    fun loadMemoryProviders(): List<MemoryPort> = load(MemoryPort::class.java)
    fun loadEmbeddings(): List<EmbeddingPort> = load(EmbeddingPort::class.java)

    private fun <T> load(type: Class<T>): List<T> =
        ServiceLoader.load(type, classLoader).toList().also { plugins ->
            plugins.forEach { logger.info("Loaded plugin: ${type.simpleName}/${it::class.simpleName}") }
        }
}
