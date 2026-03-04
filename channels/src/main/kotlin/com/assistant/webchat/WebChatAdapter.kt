package com.assistant.webchat

import com.assistant.agent.TokenTracker
import com.assistant.domain.Channel
import com.assistant.domain.Message
import com.assistant.gateway.Gateway
import com.assistant.memory.SqliteMemoryStore
import com.assistant.ports.ChannelPort
import com.assistant.ports.STREAM_TOKEN_PREFIX
import com.assistant.workspace.WorkspaceLoader
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

data class AgentInfo(val name: String, val channels: List<String>, val dbPath: String)

class WebChatAdapter(
    private val gateway: Gateway,
    private val port: Int = 8080,
    private val basePath: String = "",
    private val password: String = "",
    private val memory: SqliteMemoryStore? = null,
    private val workspace: WorkspaceLoader? = null,
    private val tokenTracker: TokenTracker? = null,
    private val agents: List<AgentInfo> = emptyList()
) : ChannelPort, Closeable {

    private val logger = Logger.getLogger(WebChatAdapter::class.java.name)
    override val name = "webchat"
    private val scope = CoroutineScope(Dispatchers.IO)
    private var server: ApplicationEngine? = null

    // In-memory token store: token -> expiry millis
    private val validTokens = ConcurrentHashMap<String, Long>()

    private fun generateToken(): String {
        val token = UUID.randomUUID().toString()
        validTokens[token] = System.currentTimeMillis() + 24 * 3_600_000L
        return token
    }

    private fun isValidToken(token: String?): Boolean {
        if (token == null) return false
        val expiry = validTokens[token] ?: return false
        if (System.currentTimeMillis() > expiry) { validTokens.remove(token); return false }
        return true
    }

    private fun extractToken(call: ApplicationCall): String? =
        call.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
            ?: call.request.queryParameters["token"]

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
                // Serve React SPA — all non-API, non-chat routes return index.html
                get("$prefix/") {
                    call.respondText(indexHtml, ContentType.Text.Html)
                }
                get("$prefix/{...}") {
                    val path = call.request.path()
                    if (!path.startsWith("$prefix/api") && path != "$prefix/chat") {
                        call.respondText(indexHtml, ContentType.Text.Html)
                    }
                }

                // Auth — no token required
                post("$prefix/api/auth") {
                    val body = call.receiveText()
                    val json = try {
                        Json.parseToJsonElement(body).jsonObject
                    } catch (_: Exception) {
                        call.respondText("""{"error":"invalid json"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    val pwd = json["password"]?.jsonPrimitive?.content ?: ""
                    if (password.isNotBlank() && pwd != password) {
                        call.respondText("""{"error":"wrong password"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                        return@post
                    }
                    val token = generateToken()
                    call.respondText("""{"token":"$token"}""", ContentType.Application.Json)
                }

                // WebSocket chat — validate token if password is set
                webSocket("$prefix/chat") {
                    val token = call.request.queryParameters["token"]
                    if (password.isNotBlank() && !isValidToken(token)) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                        return@webSocket
                    }
                    val sessionId = "WEBCHAT:${UUID.randomUUID()}"
                    val userId = sessionId

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
                                        val tok = progressMsg.removePrefix(STREAM_TOKEN_PREFIX)
                                        val jsonToken = JsonObject(mapOf("token" to JsonPrimitive(tok))).toString()
                                        outgoing.trySend(Frame.Text(jsonToken))
                                    }
                                }
                                outgoing.send(Frame.Text("""{"done":true}"""))
                            } catch (e: Exception) {
                                logger.warning("WebChat error for session $sessionId: ${e.message}")
                                outgoing.trySend(Frame.Text("""{"error":"Something went wrong"}"""))
                            }
                        }
                    }
                }

                // Protected API routes (token required if password is set)
                route("$prefix/api") {
                    intercept(ApplicationCallPipeline.Plugins) {
                        if (password.isNotBlank() && !isValidToken(extractToken(call))) {
                            call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                            finish()
                        }
                    }

                    get("/sessions") {
                        val rows = memory?.listSessions() ?: emptyList()
                        val json = rows.joinToString(",", "[", "]") {
                            """{"id":${Json.encodeToString(it.sessionId)},"messages":${it.messageCount},"lastActivity":${it.lastActivity}}"""
                        }
                        call.respondText(json, ContentType.Application.Json)
                    }

                    get("/memory") {
                        val q = call.request.queryParameters["q"] ?: ""
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                        val rows = if (q.isBlank()) memory?.listChunks(limit) ?: emptyList()
                                   else memory?.searchChunks(q, limit) ?: emptyList()
                        val json = rows.joinToString(",", "[", "]") {
                            """{"id":${it.id},"text":${Json.encodeToString(it.text)},"sessionId":${Json.encodeToString(it.sessionId)},"createdAt":${it.createdAt}}"""
                        }
                        call.respondText(json, ContentType.Application.Json)
                    }

                    delete("/memory/{id}") {
                        val id = call.parameters["id"]?.toLongOrNull()
                            ?: run { call.respondText("""{"error":"bad id"}""", ContentType.Application.Json, HttpStatusCode.BadRequest); return@delete }
                        memory?.deleteChunk(id)
                        call.respondText("""{"ok":true}""", ContentType.Application.Json)
                    }

                    get("/metrics") {
                        val stats = tokenTracker?.globalStats()
                        val sessionCount = memory?.listSessions(1000)?.size ?: 0
                        val json = """{"inputTokens":${stats?.inputTokens ?: 0},"outputTokens":${stats?.outputTokens ?: 0},"totalTokens":${stats?.totalTokens ?: 0},"sessions":$sessionCount}"""
                        call.respondText(json, ContentType.Application.Json)
                    }

                    val workspaceFiles = listOf("soul.md", "identity.md", "skills.md", "user.md", "bootstrap.md")

                    get("/workspace") {
                        val dir = workspace?.workspaceDir
                        val json = workspaceFiles.joinToString(",", "[", "]") { name ->
                            val exists = dir?.let { java.io.File(it, name).exists() } ?: false
                            """{"name":${Json.encodeToString(name)},"exists":$exists}"""
                        }
                        call.respondText(json, ContentType.Application.Json)
                    }

                    get("/workspace/{file}") {
                        val name = call.parameters["file"]
                            ?: run { call.respond(HttpStatusCode.BadRequest); return@get }
                        if (name !in workspaceFiles) { call.respond(HttpStatusCode.Forbidden); return@get }
                        val content = workspace?.workspaceDir?.let {
                            java.io.File(it, name).takeIf { f -> f.exists() }?.readText()
                        } ?: ""
                        call.respondText("""{"content":${Json.encodeToString(content)}}""", ContentType.Application.Json)
                    }

                    put("/workspace/{file}") {
                        val name = call.parameters["file"]
                            ?: run { call.respond(HttpStatusCode.BadRequest); return@put }
                        if (name !in workspaceFiles) { call.respond(HttpStatusCode.Forbidden); return@put }
                        val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                        val content = body["content"]?.jsonPrimitive?.content ?: ""
                        workspace?.workspaceDir?.let { java.io.File(it, name).writeText(content) }
                        call.respondText("""{"ok":true}""", ContentType.Application.Json)
                    }

                    get("/agents") {
                        val json = agents.joinToString(",", "[", "]") { a ->
                            """{"name":${Json.encodeToString(a.name)},"channels":${a.channels.joinToString(",","[","]") { c -> Json.encodeToString(c) }},"dbPath":${Json.encodeToString(a.dbPath)}}"""
                        }
                        call.respondText(json, ContentType.Application.Json)
                    }
                }
            }
        }.start(wait = false)

        logger.info("WebChat UI started on http://localhost:$port${prefix}/")
    }

    override fun send(sessionId: String, text: String) {
        logger.fine("WebChat send() ignored for session $sessionId")
    }

    override fun close() {
        server?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
    }
}
