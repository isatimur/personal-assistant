package com.assistant

import com.assistant.agent.*
import com.assistant.domain.Channel
import com.assistant.domain.Message
import com.assistant.gateway.Gateway
import com.assistant.heartbeat.HeartbeatAgent
import com.assistant.heartbeat.HeartbeatConfig
import com.assistant.heartbeat.HeartbeatRunner
import com.assistant.llm.EmbeddingConfig
import com.assistant.llm.LangChain4jEmbeddingProvider
import com.assistant.llm.LangChain4jProvider
import com.assistant.llm.ModelConfig
import com.assistant.memory.SqliteMemoryStore
import com.assistant.reminder.ReminderManager
import com.assistant.telegram.TelegramAdapter
import com.assistant.tools.email.EmailConfig
import com.assistant.tools.email.EmailTool
import com.assistant.tools.filesystem.FileSystemTool
import com.assistant.tools.github.GitHubTool
import com.assistant.tools.jira.JiraTool
import com.assistant.tools.linear.LinearTool
import com.assistant.tools.shell.ShellTool
import com.assistant.tools.web.WebBrowserTool
import com.assistant.workspace.WorkspaceLoader
import java.io.File
import java.nio.file.Paths
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main() {
    val config = loadConfig(secretsPath = "config/secrets.yml")

    val dbPath = config.memory.dbPath.replace("~", System.getProperty("user.home"))
    File(dbPath).parentFile.mkdirs()
    val embeddingPort = config.embedding?.let {
        LangChain4jEmbeddingProvider(EmbeddingConfig(it.provider, it.model, it.apiKey, it.baseUrl))
    }
    val memory = SqliteMemoryStore(dbPath, embeddingPort).also { it.init() }

    val llm = LangChain4jProvider(ModelConfig(config.llm.provider, config.llm.model, config.llm.apiKey, config.llm.baseUrl))

    val tools = buildList {
        add(FileSystemTool(config.tools.filesystem.allowedPaths))
        add(ShellTool(config.tools.shell.timeoutSeconds, config.tools.shell.maxOutputChars))
        add(WebBrowserTool(config.tools.web.maxContentChars))
        if (config.tools.email.enabled) {
            add(EmailTool(EmailConfig(config.tools.email.imapHost, config.tools.email.imapPort,
                config.tools.email.smtpHost, config.tools.email.smtpPort,
                config.tools.email.username, config.tools.email.password)))
        }
        if (config.tools.github.enabled) {
            add(GitHubTool(config.tools.github.token))
        }
        if (config.tools.jira.enabled) {
            add(JiraTool(config.tools.jira.baseUrl, config.tools.jira.email, config.tools.jira.apiToken))
        }
        if (config.tools.linear.enabled) {
            add(LinearTool(config.tools.linear.apiKey))
        }
    }

    val workspace = WorkspaceLoader()
    val registry = ToolRegistry(tools)
    val assembler = ContextAssembler(memory, registry, config.memory.windowSize, config.memory.searchLimit, workspace)
    val compaction = CompactionService(llm, memory, threshold = 15)
    val tokenTracker = TokenTracker()
    val engine = AgentEngine(llm, memory, registry, assembler, compactionService = compaction, tokenTracker = tokenTracker)
    val gateway = Gateway(engine)

    val voiceApiKey = if (config.voice.apiKey.isNotBlank()) config.voice.apiKey
                      else config.llm.apiKey ?: ""

    val telegram = TelegramAdapter(
        config.telegram.token,
        gateway,
        memory,
        config.telegram.timeoutMs,
        modelName = config.llm.model,
        tokenTracker = tokenTracker,
        voiceEnabled = config.voice.enabled,
        voiceApiKey = voiceApiKey
    )

    val reminderManager = ReminderManager(
        persistFile = File(workspace.workspaceDir, "reminders.json"),
        send = { chatId, text -> telegram.sendProactive(chatId, text) }
    )
    telegram.reminderManager = reminderManager
    reminderManager.loadAndReschedule()

    val heartbeat = HeartbeatRunner(
        config = HeartbeatConfig(
            enabled = config.heartbeat.enabled,
            every = config.heartbeat.every,
            time = config.heartbeat.time,
            cron = config.heartbeat.cron,
            agents = config.heartbeat.agents.map { a ->
                HeartbeatAgent(name = a.name, cron = a.cron, prompt = a.prompt, timezone = a.timezone)
            },
            prompt = config.heartbeat.prompt
        ),
        gateway = gateway,
        send = { text -> workspace.lastChatId()?.let { chatId -> telegram.sendProactive(chatId, text) } },
        chatIdFile = File(workspace.workspaceDir, "last-chat-id")
    )

    println("Personal assistant starting... Send a message on Telegram!")
    telegram.start { sessionId, userId, text, imageUrl ->
        gateway.handle(Message(sender = userId, text = text, channel = Channel.TELEGRAM, imageUrl = imageUrl))
    }
    heartbeat.start()

    val mainScope = CoroutineScope(Dispatchers.Default)
    val watcher = ConfigWatcher(
        paths = listOf(Paths.get("config/application.yml"), Paths.get("config/secrets.yml")),
        scope = mainScope,
        onChange = {
            println("Config changed — restarting")
            workspace.lastChatId()?.let { chatId ->
                telegram.sendProactive(chatId, "Config reloaded — restarting...")
            }
            delay(500)
            exitProcess(0)
        }
    )
    watcher.start()
}
