package com.assistant.discord

import com.assistant.domain.Channel
import com.assistant.domain.Message
import com.assistant.gateway.Gateway
import com.assistant.ports.ChannelPort
import com.assistant.ports.STREAM_TOKEN_PREFIX
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import java.io.Closeable
import java.util.logging.Logger
import kotlin.jvm.Volatile

class DiscordAdapter(
    private val token: String,
    private val gateway: Gateway
) : ChannelPort, Closeable {
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
                    val channelId = event.channel.id

                    scope.launch {
                        var streamMessageId: Long? = null
                        val tokenBuffer = StringBuilder()
                        var bufferSnapshot = ""

                        // Periodic updater: edits the streaming message every 500ms
                        val streamJob = launch {
                            while (isActive) {
                                delay(500)
                                val mid = streamMessageId ?: continue
                                val current = synchronized(tokenBuffer) { tokenBuffer.toString() }
                                if (current != bufferSnapshot && current.isNotBlank()) {
                                    bufferSnapshot = current
                                    try {
                                        val ch = jda?.getTextChannelById(channelId)
                                            ?: jda?.getThreadChannelById(channelId)
                                        ch?.editMessageById(mid, "$current\u25ae")?.queue()
                                    } catch (_: Exception) {}
                                }
                            }
                        }

                        val reply = try {
                            gateway.handle(
                                Message(sender = userId, text = text, channel = Channel.DISCORD)
                            ) { progressMsg ->
                                if (progressMsg.startsWith(STREAM_TOKEN_PREFIX)) {
                                    val token = progressMsg.removePrefix(STREAM_TOKEN_PREFIX)
                                    synchronized(tokenBuffer) { tokenBuffer.append(token) }
                                    if (streamMessageId == null) {
                                        // First token — send placeholder synchronously
                                        val sentMsg = event.channel.sendMessage("\u25ae").complete()
                                        streamMessageId = sentMsg.idLong
                                    }
                                }
                                // Tool progress messages are silently dropped for Discord
                                // (could be sent as separate messages if desired)
                            }
                        } catch (e: Exception) {
                            logger.warning("Failed to process Discord message: ${e.message}")
                            streamJob.cancel()
                            null
                        }

                        streamJob.cancelAndJoin()

                        if (reply == null) return@launch

                        val mid = streamMessageId
                        if (mid != null) {
                            // Final edit with complete response
                            val ch = jda?.getTextChannelById(channelId)
                                ?: jda?.getThreadChannelById(channelId)
                            ch?.editMessageById(mid, reply)?.queue(null) { error ->
                                logger.warning("Failed to edit Discord message: ${error.message}")
                            }
                        } else {
                            event.channel.sendMessage(reply).queue(null) { error ->
                                logger.warning("Failed to send Discord reply: ${error.message}")
                            }
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
            ?: jda?.getThreadChannelById(channelId)
        if (channel == null) {
            logger.warning("Discord channel not found for id: $channelId")
            return
        }
        channel.sendMessage(text).queue(null) { error ->
            logger.warning("Failed to send Discord message: ${error.message}")
        }
    }

    override fun close() {
        scope.cancel()
        jda?.shutdown()
    }
}
