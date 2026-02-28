package com.assistant

import com.assistant.agent.*
import com.assistant.domain.Channel
import com.assistant.domain.Message
import com.assistant.domain.Session
import com.assistant.gateway.Gateway
import com.assistant.heartbeat.HeartbeatAgent
import com.assistant.heartbeat.HeartbeatConfig
import com.assistant.heartbeat.HeartbeatRunner
import com.assistant.llm.EmbeddingConfig
import com.assistant.llm.LangChain4jEmbeddingProvider
import com.assistant.llm.LangChain4jProvider
import com.assistant.llm.ModelConfig
import com.assistant.memory.SqliteMemoryStore
import com.assistant.ports.EnginePlugin
import com.assistant.ports.ToolPort
import com.assistant.reminder.ReminderManager
import com.assistant.discord.DiscordAdapter
import com.assistant.telegram.TelegramAdapter
import com.assistant.tools.agent.AskAgentTool
import com.assistant.tools.email.EmailConfig
import com.assistant.tools.email.EmailTool
import com.assistant.tools.filesystem.FileSystemTool
import com.assistant.tools.github.GitHubTool
import com.assistant.tools.jira.JiraTool
import com.assistant.tools.linear.LinearTool
import com.assistant.tools.shell.ShellTool
import com.assistant.tools.http.HttpTool
import com.assistant.tools.knowledge.KnowledgeIngestTool
import com.assistant.tools.web.WebBrowserTool
import com.assistant.workspace.WorkspaceLoader
import java.io.File
import java.nio.file.Paths
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class AgentStack(
    val engine: AgentEngine,
    val memory: SqliteMemoryStore,
    val tokenTracker: TokenTracker,
    val tools: List<ToolPort>,
    val workspace: WorkspaceLoader,
    val compaction: CompactionService,
    val plugins: List<EnginePlugin>
)

fun buildAgentEngine(
    agentName: String,
    config: AppConfig,
    llm: LangChain4jProvider,
    baseTools: List<ToolPort>,
    embeddingPort: LangChain4jEmbeddingProvider?,
    globalDir: File,
    plugins: List<EnginePlugin> = emptyList()
): AgentStack {
    val agentDir = File(globalDir, "agents/$agentName").also { it.mkdirs() }
    val dbPath = File(agentDir, "memory.db").absolutePath
    val memory = SqliteMemoryStore(dbPath, embeddingPort).also { it.init() }
    val tools = if (config.tools.knowledge.enabled) baseTools + KnowledgeIngestTool(memory) else baseTools
    val registry = ToolRegistry(tools)
    val workspace = WorkspaceLoader(agentDir, fallbackDir = globalDir)
    val assembler = ContextAssembler(memory, registry, config.memory.windowSize, config.memory.searchLimit, workspace)
    val compaction = CompactionService(llm, memory, threshold = 15)
    val tracker = TokenTracker()
    val engine = AgentEngine(llm, memory, registry, assembler, compactionService = compaction, tokenTracker = tracker, plugins = plugins)
    return AgentStack(engine, memory, tracker, tools, workspace, compaction, plugins)
}

fun main() {
    val config = loadConfig(secretsPath = "config/secrets.yml")
    val pluginLoader = PluginLoader()

    val globalDir = File(System.getProperty("user.home"), ".assistant")

    val embeddingPort = config.embedding?.let {
        LangChain4jEmbeddingProvider(EmbeddingConfig(it.provider, it.model, it.apiKey, it.baseUrl))
    }

    val llm = LangChain4jProvider(ModelConfig(config.llm.provider, config.llm.model, config.llm.apiKey, config.llm.baseUrl, config.llm.fastModel))

    // Base tools shared across all agents (no KnowledgeIngestTool — that is memory-specific)
    val baseTools: List<ToolPort> = buildList {
        add(FileSystemTool(config.tools.filesystem.allowedPaths))
        add(ShellTool(config.tools.shell.timeoutSeconds, config.tools.shell.maxOutputChars))
        add(WebBrowserTool(
            maxContentChars = config.tools.web.maxContentChars,
            searchProvider = config.tools.web.searchProvider,
            searchApiKey = config.tools.web.searchApiKey
        ))
        if (config.tools.http.enabled) {
            add(HttpTool())
        }
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
        addAll(pluginLoader.loadTools())
    }

    val defaultPlugins: List<EnginePlugin> = listOf(LoggingPlugin())

    val (gateway, activeMemory, activeTokenTracker) = if (config.routing == null) {
        // === LEGACY PATH (unchanged behavior) ===
        val dbPath = config.memory.dbPath.replace("~", System.getProperty("user.home"))
        File(dbPath).parentFile.mkdirs()
        val memory = SqliteMemoryStore(dbPath, embeddingPort).also { it.init() }
        val tools = if (config.tools.knowledge.enabled) baseTools + KnowledgeIngestTool(memory) else baseTools
        val registry = ToolRegistry(tools)
        val workspace = WorkspaceLoader(globalDir)
        val assembler = ContextAssembler(memory, registry, config.memory.windowSize, config.memory.searchLimit, workspace)
        val compaction = CompactionService(llm, memory, threshold = 15)
        val tokenTracker = TokenTracker()
        val engine = AgentEngine(llm, memory, registry, assembler, compactionService = compaction, tokenTracker = tokenTracker, plugins = defaultPlugins)
        Triple(Gateway(engine), memory, tokenTracker)
    } else {
        // === MULTI-AGENT ROUTING PATH ===
        val routing = config.routing
        val allAgentNames = (routing.channels.values + routing.default).toSet()
        val baseStacks = allAgentNames.associateWith { buildAgentEngine(it, config, llm, baseTools, embeddingPort, globalDir, defaultPlugins) }

        // Create the inter-agent bus and rebuild engines with AskAgentTool when messaging is enabled
        val busScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val bus = InProcessAgentBus(busScope)

        val finalStacks = if (routing.messaging.enabled) {
            baseStacks.mapValues { (agentName, stack) ->
                val allTools = stack.tools + AskAgentTool(bus, agentName, routing.messaging.timeoutMs)
                val newRegistry = ToolRegistry(allTools)
                val newAssembler = ContextAssembler(stack.memory, newRegistry, config.memory.windowSize, config.memory.searchLimit, stack.workspace)
                val newEngine = AgentEngine(llm, stack.memory, newRegistry, newAssembler, compactionService = stack.compaction, tokenTracker = stack.tokenTracker, plugins = stack.plugins)
                stack.copy(engine = newEngine)
            }
        } else {
            baseStacks
        }

        // Register all agents with the bus so they can receive inter-agent messages
        finalStacks.forEach { (agentName, stack) ->
            bus.registerAgent(agentName) { from, text ->
                val sessionKey = "AGENT:$from→$agentName"
                val session = Session(id = sessionKey, userId = from, channel = Channel.AGENT)
                stack.engine.process(session, Message(sender = from, text = text, channel = Channel.AGENT))
            }
        }

        val defaultStack = finalStacks[routing.default]!!
        val engineByChannel = routing.channels.mapValues { (_, name) -> finalStacks[name]!!.engine }
        val gw = Gateway(defaultStack.engine, engineByChannel)

        val telegramAgentName = routing.channels["telegram"] ?: routing.default
        val telegramStack = finalStacks[telegramAgentName]!!
        Triple(gw, telegramStack.memory, telegramStack.tokenTracker)
    }

    val workspace = WorkspaceLoader(globalDir)

    val voiceApiKey = if (config.voice.apiKey.isNotBlank()) config.voice.apiKey
                      else config.llm.apiKey ?: ""

    val telegram = TelegramAdapter(
        config.telegram.token,
        gateway,
        activeMemory,
        config.telegram.timeoutMs,
        modelName = config.llm.model,
        tokenTracker = activeTokenTracker,
        voiceEnabled = config.voice.enabled,
        voiceApiKey = voiceApiKey
    )

    val reminderManager = ReminderManager(
        persistFile = File(workspace.workspaceDir, "reminders.json"),
        send = { chatId, text -> telegram.sendProactive(chatId, text) }
    )
    telegram.reminderManager = reminderManager
    reminderManager.loadAndReschedule()

    val pluginChannels = pluginLoader.loadChannels()
    pluginChannels.forEach { channel ->
        channel.start { _, userId, text, imageUrl ->
            val msg = Message(sender = userId, text = text, channel = Channel.PLUGIN, imageUrl = imageUrl)
            gateway.handle(msg)
        }
        println("Plugin channel started: ${channel.name}")
    }

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

    if (config.discord.enabled) {
        val discord = DiscordAdapter(config.discord.token)
        discord.start { _, userId, text, imageUrl ->
            gateway.handle(Message(sender = userId, text = text, channel = Channel.DISCORD, imageUrl = imageUrl))
        }
        println("Discord adapter started")
    }

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
