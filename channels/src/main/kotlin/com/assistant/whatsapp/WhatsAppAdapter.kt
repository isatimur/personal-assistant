package com.assistant.whatsapp

import com.assistant.domain.Channel
import com.assistant.domain.Message
import com.assistant.gateway.Gateway
import com.assistant.ports.ChannelPort
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * WhatsApp Business Cloud API adapter.
 *
 * Setup requirements (one-time, by the user):
 *  1. Create a Meta App at developers.facebook.com → WhatsApp product
 *  2. Obtain WHATSAPP_TOKEN, PHONE_NUMBER_ID, and set a verify-token
 *  3. Configure webhook URL pointing to https://your-host/whatsapp
 *
 * The adapter starts a Ktor HTTP server on [port] to receive webhook events.
 * If WebChat is also enabled and using the same port, they share the same server
 * (configure via different [port] values or let the shared server handle routing).
 */
class WhatsAppAdapter(
    private val gateway: Gateway,
    private val token: String,
    private val phoneNumberId: String,
    private val verifyToken: String,
    private val port: Int = 8081
) : ChannelPort, Closeable {

    private val logger = Logger.getLogger(WhatsAppAdapter::class.java.name)
    override val name = "whatsapp"
    private val scope = CoroutineScope(Dispatchers.IO)
    private var server: ApplicationEngine? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun start(onMessage: suspend (sessionId: String, userId: String, text: String, imageUrl: String?) -> String) {
        server = embeddedServer(Netty, port = port) {
            routing {
                // Webhook verification (GET)
                get("/whatsapp") {
                    val mode      = call.request.queryParameters["hub.mode"]
                    val challenge = call.request.queryParameters["hub.challenge"]
                    val token     = call.request.queryParameters["hub.verify_token"]
                    if (mode == "subscribe" && token == verifyToken && challenge != null) {
                        call.respondText(challenge)
                    } else {
                        call.respond(io.ktor.http.HttpStatusCode.Forbidden, "Verification failed")
                    }
                }

                // Incoming messages (POST)
                post("/whatsapp") {
                    call.respond(io.ktor.http.HttpStatusCode.OK, "")  // Ack immediately
                    val body = call.receiveText()
                    scope.launch { processWebhook(body, onMessage) }
                }
            }
        }.start(wait = false)

        logger.info("WhatsApp webhook server started on port $port")
    }

    private suspend fun processWebhook(
        body: String,
        onMessage: suspend (String, String, String, String?) -> String
    ) {
        try {
            val root = Json.parseToJsonElement(body).jsonObject
            val entry = root["entry"]?.jsonArray?.firstOrNull()?.jsonObject ?: return
            val changes = entry["changes"]?.jsonArray?.firstOrNull()?.jsonObject ?: return
            val value = changes["value"]?.jsonObject ?: return
            val messages = value["messages"]?.jsonArray ?: return
            val msg = messages.firstOrNull()?.jsonObject ?: return

            val from = msg["from"]?.jsonPrimitive?.content ?: return
            val sessionId = "WHATSAPP:$from"
            val type = msg["type"]?.jsonPrimitive?.content ?: "text"

            val text: String
            val imageUrl: String?

            when (type) {
                "text" -> {
                    text = msg["text"]?.jsonObject?.get("body")?.jsonPrimitive?.content ?: return
                    imageUrl = null
                }
                "image" -> {
                    val mediaId = msg["image"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: return
                    imageUrl = downloadMediaUrl(mediaId)
                    text = msg["image"]?.jsonObject?.get("caption")?.jsonPrimitive?.content
                        ?: "What's in this image?"
                }
                else -> return  // Unsupported message type
            }

            val reply = onMessage(sessionId, from, text, imageUrl)
            sendReply(from, reply)
        } catch (e: Exception) {
            logger.warning("WhatsApp webhook processing error: ${e.message}")
        }
    }

    /** Downloads a WhatsApp media URL from Graph API. Returns the URL string or null on error. */
    private fun downloadMediaUrl(mediaId: String): String? = try {
        val req = Request.Builder()
            .url("https://graph.facebook.com/v21.0/$mediaId")
            .header("Authorization", "Bearer $token")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = Json.parseToJsonElement(resp.body?.string() ?: return null).jsonObject
            json["url"]?.jsonPrimitive?.content
        }
    } catch (e: Exception) {
        logger.warning("Failed to get media URL: ${e.message}")
        null
    }

    /** Sends a text reply to the given WhatsApp number via Meta Cloud API. */
    private fun sendReply(to: String, text: String) {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
        val jsonBody = """{"messaging_product":"whatsapp","to":"$to","type":"text","text":{"body":"$escaped"}}"""
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://graph.facebook.com/v21.0/$phoneNumberId/messages")
            .header("Authorization", "Bearer $token")
            .post(jsonBody)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.warning("WhatsApp send failed: ${response.code} ${response.message}")
                }
            }
        } catch (e: Exception) {
            logger.warning("WhatsApp send error: ${e.message}")
        }
    }

    override fun send(sessionId: String, text: String) {
        val to = sessionId.removePrefix("WHATSAPP:")
        scope.launch { sendReply(to, text) }
    }

    override fun close() {
        server?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
    }
}
