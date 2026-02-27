package com.assistant.discord

import com.assistant.ports.ChannelPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import java.util.logging.Logger
import kotlin.jvm.Volatile

class DiscordAdapter(
    private val token: String
) : ChannelPort {
    private val logger = Logger.getLogger(DiscordAdapter::class.java.name)
    override val name = "discord"
    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var jda: JDA? = null

    override fun start(onMessage: suspend (sessionId: String, userId: String, text: String, imageUrl: String?) -> String) {
        jda = JDABuilder.createDefault(token)
            .enableIntents(GatewayIntent.MESSAGE_CONTENT)
            .addEventListeners(object : ListenerAdapter() {
                override fun onMessageReceived(event: MessageReceivedEvent) {
                    if (event.author.isBot) return
                    val sessionId = "DISCORD:${event.channel.id}"
                    val userId = event.author.id
                    val text = event.message.contentDisplay
                    scope.launch {
                        val reply = onMessage(sessionId, userId, text, null)
                        event.channel.sendMessage(reply).queue(null) { error ->
                            logger.warning("Failed to send Discord reply: ${error.message}")
                        }
                    }
                }
            })
            .build()
            .awaitReady()
        logger.info("Discord adapter started for bot ${jda?.selfUser?.name}")
    }

    override fun send(sessionId: String, text: String) {
        val channelId = sessionId.removePrefix("DISCORD:").toLongOrNull()
        if (channelId == null) {
            logger.warning("Invalid Discord sessionId: $sessionId")
            return
        }
        val channel = jda?.getTextChannelById(channelId)
        if (channel == null) {
            logger.warning("Discord channel not found for id: $channelId")
            return
        }
        channel.sendMessage(text).queue(null) { error ->
            logger.warning("Failed to send Discord message: ${error.message}")
        }
    }
}
