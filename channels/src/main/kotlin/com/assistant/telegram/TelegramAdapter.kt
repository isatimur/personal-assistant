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
import kotlinx.coroutines.launch

class TelegramAdapter(private val token: String, private val gateway: Gateway) {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun normalize(senderId: String, text: String): Message =
        Message(sender = senderId, text = text, channel = Channel.TELEGRAM)

    fun start() {
        val telegramBot = bot {
            this.token = this@TelegramAdapter.token
            dispatch {
                message(Filter.Text) {
                    val chatId = message.chat.id.toString()
                    val text = message.text ?: return@message
                    scope.launch {
                        val response = gateway.handle(normalize(chatId, text))
                        bot.sendMessage(ChatId.fromId(message.chat.id), response)
                    }
                }
            }
        }
        telegramBot.startPolling()
    }
}
