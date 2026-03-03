package com.assistant.slack

import com.assistant.domain.Channel
import com.assistant.domain.Message
import com.assistant.gateway.Gateway
import com.assistant.ports.ChannelPort
import com.assistant.ports.STREAM_TOKEN_PREFIX
import com.slack.api.Slack
import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.model.event.MessageEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.logging.Logger

class SlackAdapter(
    private val gateway: Gateway,
    private val botToken: String,
    private val appToken: String
) : ChannelPort, Closeable {

    private val logger = Logger.getLogger(SlackAdapter::class.java.name)
    override val name = "slack"
    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var socketModeApp: SocketModeApp? = null

    // Slack API client — thread-safe, reusable across all requests
    private val slackClient by lazy { Slack.getInstance().methods(botToken) }

    override fun start(onMessage: suspend (sessionId: String, userId: String, text: String, imageUrl: String?) -> String) {
        val appConfig = AppConfig.builder()
            .singleTeamBotToken(botToken)
            .build()
        val app = App(appConfig)

        app.event(MessageEvent::class.java) { payload, ctx ->
            val event = payload.event
            // Ignore bot messages and subtypes (e.g. message_changed, message_deleted)
            if (event.botId != null || event.subtype != null) {
                return@event ctx.ack()
            }

            val channelId = event.channel ?: return@event ctx.ack()
            val userId = event.user ?: return@event ctx.ack()
            val text = event.text ?: return@event ctx.ack()

            scope.launch {
                val sessionId = "SLACK:$channelId"
                var streamTs: String? = null
                val tokenBuffer = StringBuilder()
                var bufferSnapshot = ""

                // Periodic updater: edits the streaming message every 500ms
                val streamJob = launch {
                    while (isActive) {
                        delay(500)
                        val ts = streamTs ?: continue
                        val current = synchronized(tokenBuffer) { tokenBuffer.toString() }
                        if (current != bufferSnapshot && current.isNotBlank()) {
                            bufferSnapshot = current
                            try {
                                slackClient.chatUpdate { r -> r
                                    .channel(channelId)
                                    .ts(ts)
                                    .text("$current\u25ae")
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                val reply = try {
                    gateway.handle(
                        Message(sender = userId, text = text, channel = Channel.SLACK)
                    ) { progressMsg ->
                        if (progressMsg.startsWith(STREAM_TOKEN_PREFIX)) {
                            val token = progressMsg.removePrefix(STREAM_TOKEN_PREFIX)
                            synchronized(tokenBuffer) { tokenBuffer.append(token) }
                            if (streamTs == null) {
                                // First token — post placeholder message synchronously
                                try {
                                    val postResult = slackClient.chatPostMessage { r -> r
                                        .channel(channelId)
                                        .text("\u25ae")
                                    }
                                    if (postResult.isOk) streamTs = postResult.ts
                                } catch (e: Exception) {
                                    logger.warning("Failed to post Slack placeholder: ${e.message}")
                                }
                            }
                        }
                        // Tool progress messages are silently dropped for Slack
                    }
                } catch (e: Exception) {
                    logger.warning("Failed to process Slack message: ${e.message}")
                    streamJob.cancel()
                    null
                }

                streamJob.cancelAndJoin()

                if (reply == null) return@launch

                val ts = streamTs
                if (ts != null) {
                    // Final edit with complete response
                    try {
                        slackClient.chatUpdate { r -> r
                            .channel(channelId)
                            .ts(ts)
                            .text(reply)
                        }
                    } catch (e: Exception) {
                        logger.warning("Failed to update Slack message: ${e.message}")
                    }
                } else {
                    // No streaming — post new message
                    try {
                        slackClient.chatPostMessage { r -> r
                            .channel(channelId)
                            .text(reply)
                        }
                    } catch (e: Exception) {
                        logger.warning("Failed to send Slack reply: ${e.message}")
                    }
                }
            }

            ctx.ack()
        }

        val sma = SocketModeApp(appToken, app)
        socketModeApp = sma

        // SocketModeApp.start() blocks until the connection closes — run in a daemon thread
        val thread = Thread({
            try {
                sma.start()
            } catch (e: Exception) {
                if (e !is InterruptedException) {
                    logger.warning("Slack Socket Mode ended: ${e.message}")
                }
            }
        }, "slack-socket-mode")
        thread.isDaemon = true
        thread.start()

        logger.info("Slack adapter started (Socket Mode)")
    }

    override fun send(sessionId: String, text: String) {
        val channelId = sessionId.removePrefix("SLACK:")
        scope.launch {
            try {
                slackClient.chatPostMessage { r -> r.channel(channelId).text(text) }
            } catch (e: Exception) {
                logger.warning("Failed to send proactive Slack message: ${e.message}")
            }
        }
    }

    override fun close() {
        socketModeApp?.close()
    }
}
