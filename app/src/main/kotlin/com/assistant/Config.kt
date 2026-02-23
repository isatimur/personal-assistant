package com.assistant

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
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
@Serializable data class HeartbeatConfig(val enabled: Boolean = false, val every: String = "1h", val time: String? = null, val cron: String? = null, val agents: List<HeartbeatAgentConfig> = emptyList(), val prompt: String = "Check if there's anything proactive you should do.")
@Serializable data class HeartbeatAgentConfig(val name: String, val cron: String, val prompt: String, val timezone: String = "")

// Secrets overlay
@Serializable data class TelegramSecrets(val token: String? = null)
@Serializable data class LlmSecrets(@SerialName("api-key") val apiKey: String? = null)
@Serializable data class EmbeddingSecrets(@SerialName("api-key") val apiKey: String? = null)
@Serializable data class EmailSecrets(val username: String? = null, val password: String? = null)
@Serializable data class ToolsSecrets(val email: EmailSecrets? = null)
@Serializable data class SecretsConfig(
    val telegram: TelegramSecrets? = null,
    val llm: LlmSecrets? = null,
    val embedding: EmbeddingSecrets? = null,
    val tools: ToolsSecrets? = null
)

fun loadConfig(basePath: String = "config/application.yml", secretsPath: String = "config/secrets.yml"): AppConfig {
    val base = Yaml.default.decodeFromString(AppConfig.serializer(), File(basePath).readText())
    val secretsFile = File(secretsPath)
    if (!secretsFile.exists()) return base
    val lenient = Yaml(configuration = YamlConfiguration(strictMode = false))
    val secrets = lenient.decodeFromString(SecretsConfig.serializer(), secretsFile.readText())
    return base.copy(
        telegram = secrets.telegram?.token?.let { base.telegram.copy(token = it) } ?: base.telegram,
        llm = secrets.llm?.apiKey?.let { base.llm.copy(apiKey = it) } ?: base.llm,
        embedding = secrets.embedding?.apiKey?.let { base.embedding?.copy(apiKey = it) } ?: base.embedding,
        tools = if (secrets.tools?.email != null) {
            val se = secrets.tools.email
            base.tools.copy(email = base.tools.email.copy(
                username = se.username ?: base.tools.email.username,
                password = se.password ?: base.tools.email.password
            ))
        } else base.tools
    )
}
