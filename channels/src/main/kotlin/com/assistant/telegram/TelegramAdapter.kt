package com.assistant.telegram

import com.assistant.domain.*
import com.assistant.gateway.Gateway
import com.assistant.ports.MemoryPort
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
    private val lastChatIdFile: File = File(System.getProperty("user.home"), ".assistant/last-chat-id")
) {
    private val logger = Logger.getLogger(TelegramAdapter::class.java.name)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val semaphore = Semaphore(4)
    private var telegramBot: Bot? = null

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
            text == "/new" || text == "/reset" -> {
                gateway.clearSession(sessionKey)
                memory.clearHistory(sessionKey)
                bot.sendMessage(ChatId.fromId(chatId), "Session cleared. Starting fresh!")
            }
            text == "/help" -> {
                bot.sendMessage(
                    ChatId.fromId(chatId),
                    "/new or /reset — clear conversation history\n/help — show this message"
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
                message(Filter.Text) {
                    val chatId = message.chat.id
                    val text = message.text ?: return@message
                    scope.launch {
                        writeChatId(chatId)
                        if (handleCommand(bot, chatId, text)) return@launch
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
