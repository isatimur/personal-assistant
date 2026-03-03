package com.assistant.webchat

import com.assistant.domain.Channel
import com.assistant.domain.Message
import com.assistant.gateway.Gateway
import com.assistant.ports.ChannelPort
import com.assistant.ports.STREAM_TOKEN_PREFIX
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.time.Duration
import java.util.UUID
import java.util.logging.Logger

class WebChatAdapter(
    private val gateway: Gateway,
    private val port: Int = 8080,
    private val basePath: String = ""
) : ChannelPort, Closeable {

    private val logger = Logger.getLogger(WebChatAdapter::class.java.name)
    override val name = "webchat"
    private val scope = CoroutineScope(Dispatchers.IO)
    private var server: ApplicationEngine? = null

    private val indexHtml: String by lazy {
        WebChatAdapter::class.java.getResourceAsStream("/static/index.html")
            ?.bufferedReader()?.readText()
            ?: error("static/index.html not found in resources")
    }

    override fun start(onMessage: suspend (sessionId: String, userId: String, text: String, imageUrl: String?) -> String) {
        val prefix = basePath.trimEnd('/')
        server = embeddedServer(Netty, port = port) {
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(15)
                timeout = Duration.ofSeconds(60)
            }
            routing {
                get("$prefix/") {
                    call.respondText(indexHtml, io.ktor.http.ContentType.Text.Html)
                }
                webSocket("$prefix/chat") {
                    val sessionId = "WEBCHAT:${UUID.randomUUID()}"
                    val userId = sessionId   // anonymous — one session per connection

                    incoming.consumeEach { frame ->
                        if (frame !is Frame.Text) return@consumeEach
                        val raw = frame.readText()
                        val text = try {
                            Json.parseToJsonElement(raw).jsonObject["text"]?.jsonPrimitive?.content ?: return@consumeEach
                        } catch (_: Exception) {
                            return@consumeEach
                        }

                        scope.launch {
                            try {
                                gateway.handle(
                                    Message(sender = userId, text = text, channel = Channel.WEBCHAT)
                                ) { progressMsg ->
                                    if (progressMsg.startsWith(STREAM_TOKEN_PREFIX)) {
                                        // Streaming token — send as JSON to the browser.
                                        // trySend() is non-blocking and safe from a (String)->Unit callback.
                                        val token = progressMsg.removePrefix(STREAM_TOKEN_PREFIX)
                                        val jsonToken = JsonObject(mapOf("token" to JsonPrimitive(token))).toString()
                                        outgoing.trySend(Frame.Text(jsonToken))
                                    }
                                    // Tool progress messages are silently dropped
                                }

                                // Signal end of streaming response (suspend send is OK here: inside coroutine)
                                outgoing.send(Frame.Text("""{"done":true}"""))
                            } catch (e: Exception) {
                                logger.warning("WebChat error for session $sessionId: ${e.message}")
                                outgoing.trySend(Frame.Text("""{"error":"Something went wrong"}"""))
                            }
                        }
                    }
                }
            }
        }.start(wait = false)

        logger.info("WebChat UI started on http://localhost:$port${prefix}/")
    }

    override fun send(sessionId: String, text: String) {
        // Proactive messages not supported for WebChat (session dies with WebSocket)
        logger.fine("WebChat send() ignored for session $sessionId")
    }

    override fun close() {
        server?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
    }
}
