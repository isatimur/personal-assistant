package com.assistant

import com.assistant.agent.*
import com.assistant.gateway.Gateway
import com.assistant.llm.LangChain4jProvider
import com.assistant.llm.ModelConfig
import com.assistant.memory.SqliteMemoryStore
import com.assistant.telegram.TelegramAdapter
import com.assistant.tools.email.EmailConfig
import com.assistant.tools.email.EmailTool
import com.assistant.tools.filesystem.FileSystemTool
import com.assistant.tools.shell.ShellTool
import com.assistant.tools.web.WebBrowserTool
import java.io.File

fun main() {
    val config = loadConfig()

    val dbPath = config.memory.dbPath.replace("~", System.getProperty("user.home"))
    File(dbPath).parentFile.mkdirs()
    val memory = SqliteMemoryStore(dbPath).also { it.init() }

    val llm = LangChain4jProvider(ModelConfig(config.llm.provider, config.llm.model, config.llm.apiKey, config.llm.baseUrl))

    val tools = buildList {
        add(FileSystemTool())
        add(ShellTool(config.tools.shell.timeoutSeconds, config.tools.shell.maxOutputChars))
        add(WebBrowserTool(config.tools.web.maxContentChars))
        if (config.tools.email.enabled) {
            add(EmailTool(EmailConfig(config.tools.email.imapHost, config.tools.email.imapPort,
                config.tools.email.smtpHost, config.tools.email.smtpPort,
                config.tools.email.username, config.tools.email.password)))
        }
    }

    val registry = ToolRegistry(tools)
    val assembler = ContextAssembler(memory, registry, config.memory.windowSize)
    val engine = AgentEngine(llm, memory, registry, assembler)
    val gateway = Gateway(engine)
    val telegram = TelegramAdapter(config.telegram.token, gateway)

    println("Personal assistant starting... Send a message on Telegram!")
    telegram.start()
}
