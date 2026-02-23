package com.assistant.telegram

import com.assistant.domain.*
import com.assistant.gateway.Gateway
import com.assistant.ports.MemoryPort
import com.assistant.reminder.ReminderManager
import com.assistant.reminder.parseReminderDuration
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatAction
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.extensions.filters.Filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.logging.Logger

class TelegramAdapter(
    private val token: String,
    private val gateway: Gateway,
    private val memory: MemoryPort,
    private val timeoutMs: Long = 120_000L,
    private val lastChatIdFile: File = File(System.getProperty("user.home"), ".assistant/last-chat-id"),
    private val workspaceDir: File = File(System.getProperty("user.home"), ".assistant")
) {
    private val logger = Logger.getLogger(TelegramAdapter::class.java.name)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val semaphore = Semaphore(4)
    private var telegramBot: Bot? = null
    private val onboarding = OnboardingManager(memory, workspaceDir)
    var reminderManager: ReminderManager? = null

    fun normalize(senderId: String, text: String): Message =
        Message(sender = senderId, text = text, channel = Channel.TELEGRAM)

    internal fun writeChatId(chatId: Long) {
        lastChatIdFile.parentFile?.mkdirs()
        val tmp = File(lastChatIdFile.parentFile ?: File("."), ".last-chat-id.tmp")
        tmp.writeText(chatId.toString())
        tmp.renameTo(lastChatIdFile)
    }

    fun sendProactive(chatId: Long, text: String) {
        telegramBot?.sendMessage(ChatId.fromId(chatId), text)
    }

    internal suspend fun handleCommand(bot: Bot, chatId: Long, text: String): Boolean {
        if (!text.startsWith("/")) return false
        val sessionKey = "TELEGRAM:$chatId"
        when {
            text == "/start" -> {
                if (onboarding.needsOnboarding()) {
                    onboarding.start(bot, chatId)
                } else {
                    bot.sendMessage(ChatId.fromId(chatId), "Welcome back! Send me a message.")
                }
            }
            text == "/new" || text == "/reset" -> {
                onboarding.cancel(chatId)
                gateway.clearSession(sessionKey)
                memory.clearHistory(sessionKey)
                bot.sendMessage(ChatId.fromId(chatId), "Session cleared. Starting fresh!")
            }
            text == "/memory" -> {
                val facts = memory.facts(chatId.toString())
                if (facts.isEmpty()) {
                    bot.sendMessage(ChatId.fromId(chatId), "I don't know anything about you yet.")
                } else {
                    val list = facts.mapIndexed { i, f -> "${i + 1}. $f" }.joinToString("\n")
                    bot.sendMessage(ChatId.fromId(chatId), "What I know about you:\n$list")
                }
            }
            text.startsWith("/forget ") -> {
                val idx = text.removePrefix("/forget ").trim().toIntOrNull()
                if (idx == null) {
                    bot.sendMessage(ChatId.fromId(chatId), "Usage: /forget <number>")
                } else {
                    val facts = memory.facts(chatId.toString())
                    val fact = facts.getOrNull(idx - 1)
                    if (fact == null) {
                        bot.sendMessage(ChatId.fromId(chatId), "No fact #$idx.")
                    } else {
                        memory.deleteFact(chatId.toString(), fact)
                        bot.sendMessage(ChatId.fromId(chatId), "Forgotten: \"$fact\"")
                    }
                }
            }
            text.startsWith("/remind ") -> {
                val rm = reminderManager
                val parts = text.removePrefix("/remind ").trim().split(" ", limit = 2)
                if (parts.size < 2 || rm == null) {
                    bot.sendMessage(ChatId.fromId(chatId), "Usage: /remind <duration> <text>  e.g. /remind 30m call Artur")
                } else {
                    try {
                        val duration = parseReminderDuration(parts[0])
                        rm.schedule(chatId, duration, parts[1])
                        bot.sendMessage(ChatId.fromId(chatId), "Got it. I'll remind you in ${parts[0]}.")
                    } catch (e: IllegalArgumentException) {
                        bot.sendMessage(ChatId.fromId(chatId), "Invalid duration: ${parts[0]}. Use e.g. 30m, 1h, 2d.")
                    }
                }
            }
            text == "/help" -> {
                bot.sendMessage(
                    ChatId.fromId(chatId),
                    "/start — set up your assistant\n" +
                    "/new or /reset — clear conversation history\n" +
                    "/memory — show what I know about you\n" +
                    "/forget <n> — remove fact #n from my memory\n" +
                    "/remind <duration> <text> — set a reminder (e.g. /remind 30m call Artur)\n" +
                    "/help — show this message"
                )
            }
            else -> {
                bot.sendMessage(ChatId.fromId(chatId), "Unknown command. Try /help.")
            }
        }
        return true
    }

    fun start() {
        telegramBot = bot {
            this.token = this@TelegramAdapter.token
            dispatch {
                message(Filter.Text or Filter.Command) {
                    val chatId = message.chat.id
                    val text = message.text ?: return@message
                    scope.launch {
                        writeChatId(chatId)
                        // 1. Active wizard step (non-command messages only)
                        if (!text.startsWith("/") && onboarding.isActive(chatId)) {
                            onboarding.handle(bot, chatId, text)
                            return@launch
                        }
                        // 2. Commands
                        if (handleCommand(bot, chatId, text)) return@launch
                        // 3. Regular LLM message
                        val normalizedMsg = normalize(chatId.toString(), text)
                        val typingJob = launch {
                            while (isActive) {
                                telegramBot?.sendChatAction(ChatId.fromId(chatId), ChatAction.TYPING)
                                delay(4_000)
                            }
                        }
                        try {
                            semaphore.withPermit {
                                try {
                                    val response = withTimeout(this@TelegramAdapter.timeoutMs) {
                                        gateway.handle(normalizedMsg)
                                    }
                                    bot.sendMessage(ChatId.fromId(chatId), response)
                                } catch (e: TimeoutCancellationException) {
                                    logger.severe("Request timed out for chat $chatId")
                                    bot.sendMessage(ChatId.fromId(chatId), "Request timed out. Please try again.")
                                } catch (e: Exception) {
                                    logger.severe("handle failed for chat $chatId: ${e.message}")
                                    bot.sendMessage(ChatId.fromId(chatId), "Something went wrong. Try again.")
                                }
                            }
                        } finally {
                            typingJob.cancel()
                        }
                    }
                }
            }
        }
        telegramBot!!.startPolling()
    }
}
