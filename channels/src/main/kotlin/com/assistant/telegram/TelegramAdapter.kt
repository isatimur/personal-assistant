package com.assistant.telegram

import com.assistant.domain.*
import com.assistant.gateway.Gateway
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.extensions.filters.Filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.logging.Logger

class TelegramAdapter(
    private val token: String,
    private val gateway: Gateway,
    private val timeoutMs: Long = 120_000L
) {
    private val logger = Logger.getLogger(TelegramAdapter::class.java.name)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val semaphore = Semaphore(4)

    fun normalize(senderId: String, text: String): Message =
        Message(sender = senderId, text = text, channel = Channel.TELEGRAM)

    fun start() {
        val telegramBot = bot {
            this.token = this@TelegramAdapter.token
            dispatch {
                message(Filter.Text) {
                    val chatId = message.chat.id
                    val text = message.text ?: return@message
                    val normalizedMsg = normalize(chatId.toString(), text)
                    scope.launch {
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
                    }
                }
            }
        }
        telegramBot.startPolling()
    }
}
