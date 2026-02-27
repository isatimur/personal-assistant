package com.assistant

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable data class AppConfig(val telegram: TelegramConfig, val llm: LlmConfig, val memory: MemoryConfig, val tools: ToolsConfig, val embedding: EmbeddingCfg? = null, val heartbeat: HeartbeatConfig = HeartbeatConfig(), val voice: VoiceConfig = VoiceConfig(), val discord: DiscordConfig = DiscordConfig())
@Serializable data class TelegramConfig(val token: String, @SerialName("timeout-ms") val timeoutMs: Long = 120_000)
@Serializable data class LlmConfig(val provider: String, val model: String, @SerialName("api-key") val apiKey: String? = null, @SerialName("base-url") val baseUrl: String? = null, @SerialName("fast-model") val fastModel: String? = null)
@Serializable data class MemoryConfig(@SerialName("db-path") val dbPath: String, @SerialName("window-size") val windowSize: Int, @SerialName("search-limit") val searchLimit: Int = 5)
@Serializable data class EmbeddingCfg(val provider: String, val model: String, @SerialName("api-key") val apiKey: String? = null, @SerialName("base-url") val baseUrl: String? = null)
@Serializable data class GitHubConfig(val enabled: Boolean = false, val token: String = "")
@Serializable data class JiraConfig(val enabled: Boolean = false, @SerialName("base-url") val baseUrl: String = "", val email: String = "", @SerialName("api-token") val apiToken: String = "")
@Serializable data class LinearConfig(val enabled: Boolean = false, @SerialName("api-key") val apiKey: String = "")
@Serializable data class VoiceConfig(val enabled: Boolean = false, @SerialName("api-key") val apiKey: String = "")
@Serializable data class DiscordConfig(val token: String = "", val enabled: Boolean = false)
@Serializable data class HttpToolConfig(val enabled: Boolean = true)
@Serializable data class KnowledgeConfig(val enabled: Boolean = true)
@Serializable data class ToolsConfig(val shell: ShellConfig = ShellConfig(), val web: WebConfig = WebConfig(), val email: EmailToolConfig = EmailToolConfig(), val filesystem: FileSystemConfig = FileSystemConfig(), val github: GitHubConfig = GitHubConfig(), val jira: JiraConfig = JiraConfig(), val linear: LinearConfig = LinearConfig(), val http: HttpToolConfig = HttpToolConfig(), val knowledge: KnowledgeConfig = KnowledgeConfig())
@Serializable data class ShellConfig(@SerialName("timeout-seconds") val timeoutSeconds: Long = 30, @SerialName("max-output-chars") val maxOutputChars: Int = 10_000)
@Serializable data class WebConfig(@SerialName("max-content-chars") val maxContentChars: Int = 8_000, @SerialName("search-provider") val searchProvider: String = "duckduckgo", @SerialName("search-api-key") val searchApiKey: String = "")
@Serializable data class EmailToolConfig(val enabled: Boolean = false, @SerialName("imap-host") val imapHost: String = "", @SerialName("imap-port") val imapPort: Int = 993, @SerialName("smtp-host") val smtpHost: String = "", @SerialName("smtp-port") val smtpPort: Int = 587, val username: String = "", val password: String = "")
@Serializable data class FileSystemConfig(@SerialName("allowed-paths") val allowedPaths: List<String> = listOf("~"))
@Serializable data class HeartbeatConfig(val enabled: Boolean = false, val every: String = "1h", val time: String? = null, val cron: String? = null, val agents: List<HeartbeatAgentConfig> = emptyList(), val prompt: String = "Check if there's anything proactive you should do.")
@Serializable data class HeartbeatAgentConfig(val name: String, val cron: String, val prompt: String, val timezone: String = "")

// Secrets overlay
@Serializable data class TelegramSecrets(val token: String? = null)
@Serializable data class LlmSecrets(@SerialName("api-key") val apiKey: String? = null)
@Serializable data class EmbeddingSecrets(@SerialName("api-key") val apiKey: String? = null)
@Serializable data class EmailSecrets(val username: String? = null, val password: String? = null)
@Serializable data class GitHubSecrets(val token: String? = null)
@Serializable data class JiraSecrets(val email: String? = null, @SerialName("api-token") val apiToken: String? = null)
@Serializable data class LinearSecrets(@SerialName("api-key") val apiKey: String? = null)
@Serializable data class VoiceSecrets(@SerialName("api-key") val apiKey: String? = null)
@Serializable data class DiscordSecrets(val token: String? = null)
@Serializable data class WebSecrets(@SerialName("search-api-key") val searchApiKey: String? = null)
@Serializable data class ToolsSecrets(val email: EmailSecrets? = null, val github: GitHubSecrets? = null, val jira: JiraSecrets? = null, val linear: LinearSecrets? = null, val web: WebSecrets? = null)
@Serializable data class SecretsConfig(
    val telegram: TelegramSecrets? = null,
    val llm: LlmSecrets? = null,
    val embedding: EmbeddingSecrets? = null,
    val tools: ToolsSecrets? = null,
    val voice: VoiceSecrets? = null,
    val discord: DiscordSecrets? = null
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
        voice = secrets.voice?.apiKey?.let { base.voice.copy(apiKey = it) } ?: base.voice,
        discord = secrets.discord?.token?.let { base.discord.copy(token = it) } ?: base.discord,
        tools = run {
            var t = base.tools
            if (secrets.tools?.email != null) {
                val se = secrets.tools.email
                t = t.copy(email = t.email.copy(
                    username = se.username ?: t.email.username,
                    password = se.password ?: t.email.password
                ))
            }
            if (secrets.tools?.github?.token != null) {
                t = t.copy(github = t.github.copy(token = secrets.tools.github.token))
            }
            if (secrets.tools?.jira != null) {
                val sj = secrets.tools.jira
                t = t.copy(jira = t.jira.copy(
                    email = sj.email ?: t.jira.email,
                    apiToken = sj.apiToken ?: t.jira.apiToken
                ))
            }
            if (secrets.tools?.linear?.apiKey != null) {
                t = t.copy(linear = t.linear.copy(apiKey = secrets.tools.linear.apiKey))
            }
            if (secrets.tools?.web?.searchApiKey != null) {
                t = t.copy(web = t.web.copy(searchApiKey = secrets.tools.web.searchApiKey))
            }
            t
        }
    )
}
