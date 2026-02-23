package com.assistant

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable data class AppConfig(val telegram: TelegramConfig, val llm: LlmConfig, val memory: MemoryConfig, val tools: ToolsConfig, val embedding: EmbeddingCfg? = null, val heartbeat: HeartbeatConfig = HeartbeatConfig())
@Serializable data class TelegramConfig(val token: String, @SerialName("timeout-ms") val timeoutMs: Long = 120_000)
@Serializable data class LlmConfig(val provider: String, val model: String, @SerialName("api-key") val apiKey: String? = null, @SerialName("base-url") val baseUrl: String? = null)
@Serializable data class MemoryConfig(@SerialName("db-path") val dbPath: String, @SerialName("window-size") val windowSize: Int, @SerialName("search-limit") val searchLimit: Int = 5)
@Serializable data class EmbeddingCfg(val provider: String, val model: String, @SerialName("api-key") val apiKey: String? = null, @SerialName("base-url") val baseUrl: String? = null)
@Serializable data class ToolsConfig(val shell: ShellConfig = ShellConfig(), val web: WebConfig = WebConfig(), val email: EmailToolConfig = EmailToolConfig(), val filesystem: FileSystemConfig = FileSystemConfig())
@Serializable data class ShellConfig(@SerialName("timeout-seconds") val timeoutSeconds: Long = 30, @SerialName("max-output-chars") val maxOutputChars: Int = 10_000)
@Serializable data class WebConfig(@SerialName("max-content-chars") val maxContentChars: Int = 8_000)
@Serializable data class EmailToolConfig(val enabled: Boolean = false, @SerialName("imap-host") val imapHost: String = "", @SerialName("imap-port") val imapPort: Int = 993, @SerialName("smtp-host") val smtpHost: String = "", @SerialName("smtp-port") val smtpPort: Int = 587, val username: String = "", val password: String = "")
@Serializable data class FileSystemConfig(@SerialName("allowed-paths") val allowedPaths: List<String> = listOf("~"))
@Serializable data class HeartbeatConfig(val enabled: Boolean = false, val every: String = "1h", val time: String? = null, val prompt: String = "Check if there's anything proactive you should do.")

fun loadConfig(path: String = "config/application.yml"): AppConfig =
    Yaml.default.decodeFromString(AppConfig.serializer(), File(path).readText())
