package com.assistant.telegram

import com.assistant.agent.TokenTracker
import com.assistant.domain.*
import com.assistant.gateway.Gateway
import com.assistant.ports.ChannelPort
import com.assistant.ports.MemoryPort
import com.assistant.ports.STREAM_TOKEN_PREFIX
import com.assistant.ports.TtsPort
import com.assistant.reminder.ReminderManager
import com.assistant.reminder.parseReminderDuration
import com.assistant.workspace.WorkspaceLoader
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatAction
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.extensions.filters.Filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class TelegramAdapter(
    private val token: String,
    private val gateway: Gateway,
    private val memory: MemoryPort,
    private val timeoutMs: Long = 120_000L,
    private val lastChatIdFile: File = File(System.getProperty("user.home"), ".assistant/last-chat-id"),
    private val workspaceDir: File = File(System.getProperty("user.home"), ".assistant"),
    private val modelName: String = "",
    private val startTime: Long = System.currentTimeMillis(),
    private val tokenTracker: TokenTracker? = null,
    private val voiceEnabled: Boolean = false,
    private val voiceApiKey: String = "",
    private val ttsPort: TtsPort? = null
) : ChannelPort {
    private val logger = Logger.getLogger(TelegramAdapter::class.java.name)
    override val name = "telegram"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val semaphore = Semaphore(4)
    private var telegramBot: Bot? = null
    private val onboarding = OnboardingManager(memory, workspaceDir)
    private val workspaceLoader = WorkspaceLoader(workspaceDir)
    var reminderManager: ReminderManager? = null

    /** Per-chat voice mode: when true, bot replies with audio synthesized via TTS. */
    private val voiceMode = ConcurrentHashMap<Long, Boolean>()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

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

    /** Downloads a Telegram voice file and transcribes it via Whisper. Returns null on failure. */
    internal suspend fun transcribeVoice(bot: Bot, fileId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val (fileResp, _) = bot.getFile(fileId)
                val filePath = fileResp?.body()?.result?.filePath ?: return@withContext null
                val fileUrl = "https://api.telegram.org/file/bot$token/$filePath"

                val downloadReq = Request.Builder().url(fileUrl).build()
                val audioBytes = httpClient.newCall(downloadReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.bytes() ?: return@withContext null
                }

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", "audio.ogg",
                        audioBytes.toRequestBody("audio/ogg".toMediaType())
                    )
                    .addFormDataPart("model", "whisper-1")
                    .build()

                val whisperReq = Request.Builder()
                    .url("https://api.openai.com/v1/audio/transcriptions")
                    .header("Authorization", "Bearer $voiceApiKey")
                    .post(requestBody)
                    .build()

                val responseText = httpClient.newCall(whisperReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        logger.warning("Whisper API error: ${resp.code} ${resp.message}")
                        return@withContext null
                    }
                    resp.body?.string() ?: return@withContext null
                }

                Json.parseToJsonElement(responseText).jsonObject["text"]?.jsonPrimitive?.content
            } catch (e: Exception) {
                logger.warning("transcribeVoice failed: ${e.message}")
                null
            }
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
            text == "/user" -> {
                val content = workspaceLoader.loadUser()
                if (content == null) {
                    bot.sendMessage(ChatId.fromId(chatId), "No USER.md yet. Complete /start to create one.")
                } else {
                    bot.sendMessage(ChatId.fromId(chatId), content)
                }
            }
            text.startsWith("/user set ") -> {
                val rest = text.removePrefix("/user set ").trim()
                val spaceIdx = rest.indexOf(' ')
                if (spaceIdx < 0) {
                    bot.sendMessage(ChatId.fromId(chatId), "Usage: /user set <key> <value>")
                } else {
                    val key = rest.substring(0, spaceIdx).trim()
                    val value = rest.substring(spaceIdx + 1).trim()
                    workspaceLoader.setUserField(key, value)
                    bot.sendMessage(ChatId.fromId(chatId), "Updated $key \u2192 $value")
                }
            }
            text == "/status" -> {
                val stats = memory.stats(chatId.toString())
                val uptimeMs = System.currentTimeMillis() - startTime
                val uptimeMin = uptimeMs / 60_000
                val uptimeStr = if (uptimeMin < 60) "${uptimeMin}m" else "${uptimeMin / 60}h ${uptimeMin % 60}m"
                val tokenLines = if (tokenTracker != null) {
                    val session = tokenTracker.sessionStats(sessionKey)
                    val global = tokenTracker.globalStats()
                    "Tokens (session): ${session.inputTokens} in / ${session.outputTokens} out\n" +
                    "Tokens (total):   ${global.inputTokens} in / ${global.outputTokens} out\n"
                } else ""
                val voiceModeStr = if (voiceMode.getOrDefault(chatId, false)) " | voice: on" else ""
                bot.sendMessage(
                    ChatId.fromId(chatId),
                    "Bot status\n" +
                    "Facts: ${stats.factsCount}\n" +
                    "Chunks: ${stats.chunkCount}\n" +
                    "Messages: ${stats.messageCount}\n" +
                    tokenLines +
                    (if (modelName.isNotBlank()) "Model: $modelName\n" else "") +
                    "Uptime: $uptimeStr$voiceModeStr"
                )
            }
            text == "/voice on" -> {
                if (ttsPort == null) {
                    bot.sendMessage(ChatId.fromId(chatId), "Voice responses are not enabled. Set voice.tts: true in config.")
                } else {
                    voiceMode[chatId] = true
                    bot.sendMessage(ChatId.fromId(chatId), "Voice responses enabled. I'll reply with audio.")
                }
            }
            text == "/voice off" -> {
                voiceMode.remove(chatId)
                bot.sendMessage(ChatId.fromId(chatId), "Voice responses disabled.")
            }
            text == "/help" -> {
                bot.sendMessage(
                    ChatId.fromId(chatId),
                    "/start — set up your assistant\n" +
                    "/new or /reset — clear conversation history\n" +
                    "/memory — show what I know about you\n" +
                    "/forget <n> — remove fact #n from my memory\n" +
                    "/remind <duration> <text> — set a reminder (e.g. /remind 30m call Artur)\n" +
                    "/user — show your USER.md profile\n" +
                    "/user set <key> <value> — update a field in your profile\n" +
                    "/status — show memory stats and bot info\n" +
                    "/voice on|off — toggle voice (TTS) responses\n" +
                    "/help — show this message"
                )
            }
            else -> {
                bot.sendMessage(ChatId.fromId(chatId), "Unknown command. Try /help.")
            }
        }
        return true
    }

    /**
     * Runs the LLM pipeline and streams the response.
     * Real tokens from the LLM synthesis step arrive via [onProgress] prefixed with
     * [STREAM_TOKEN_PREFIX] and are used to progressively edit a single Telegram message.
     * Tool-use progress messages (no prefix) are sent as separate messages.
     * Falls back to a plain sendMessage when no streaming occurred (simple text responses).
     */
    internal suspend fun handleProcessedMessage(bot: Bot, chatId: Long, msg: Message) {
        val typingJob = scope.launch {
            while (isActive) {
                telegramBot?.sendChatAction(ChatId.fromId(chatId), ChatAction.TYPING)
                delay(4_000)
            }
        }

        // Streaming state — written by onProgress lambda (IO thread), read by streamJob (IO thread)
        var streamMessageId: Long? = null          // null = initial message not sent yet
        val tokenBuffer = StringBuilder()
        var bufferSnapshot = ""

        // Periodic updater: edits the streaming message every 500ms while tokens arrive
        val streamJob = scope.launch {
            while (isActive) {
                delay(500)
                val mid = streamMessageId ?: continue
                val current = synchronized(tokenBuffer) { tokenBuffer.toString() }
                if (current != bufferSnapshot && current.isNotBlank()) {
                    bufferSnapshot = current
                    try {
                        bot.editMessageText(
                            chatId = ChatId.fromId(chatId),
                            messageId = mid,
                            text = "$current\u25ae"    // ▮ cursor indicator
                        )
                    } catch (_: Exception) {
                        // Rate-limited or message unchanged — skip this edit
                    }
                }
            }
        }

        try {
            semaphore.withPermit {
                try {
                    val response = withTimeout(this@TelegramAdapter.timeoutMs) {
                        gateway.handle(msg) { progressMsg ->
                            if (progressMsg.startsWith(STREAM_TOKEN_PREFIX)) {
                                val token = progressMsg.removePrefix(STREAM_TOKEN_PREFIX)
                                synchronized(tokenBuffer) { tokenBuffer.append(token) }
                                if (streamMessageId == null) {
                                    // First token: cancel typing indicator, send placeholder
                                    typingJob.cancel()
                                    val sent = bot.sendMessage(ChatId.fromId(chatId), "\u25ae")
                                    streamMessageId = sent.getOrNull()?.messageId
                                }
                            } else {
                                // Tool-use progress message — send as a separate message
                                scope.launch { bot.sendMessage(ChatId.fromId(chatId), progressMsg) }
                            }
                        }
                    }

                    streamJob.cancelAndJoin()
                    typingJob.cancel()

                    val mid = streamMessageId
                    if (mid != null) {
                        // Final edit — replace streaming placeholder with complete response
                        bot.editMessageText(
                            chatId = ChatId.fromId(chatId),
                            messageId = mid,
                            text = response
                        )
                    } else {
                        // No streaming (simple text response) — send directly
                        streamToChat(bot, chatId, response)
                    }

                    // TTS: if voice mode is on and TTS provider is configured, reply with audio
                    if (voiceMode.getOrDefault(chatId, false) && ttsPort != null) {
                        try {
                            val audioBytes = ttsPort.synthesize(response)
                            bot.sendVoice(chatId = ChatId.fromId(chatId), audio = TelegramFile.ByByteArray(audioBytes, "voice.ogg"))
                        } catch (e: Exception) {
                            logger.warning("TTS synthesis failed: ${e.message}")
                        }
                    }

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
            streamJob.cancel()
        }
    }

    /** Sends text as a new message. Used as fallback when no LLM streaming occurred. */
    internal suspend fun streamToChat(bot: Bot, chatId: Long, text: String) {
        bot.sendMessage(ChatId.fromId(chatId), text)
    }

    override fun start(onMessage: suspend (sessionId: String, userId: String, text: String, imageUrl: String?) -> String) {
        startPolling()
    }

    override fun send(sessionId: String, text: String) {
        val chatId = sessionId.removePrefix("TELEGRAM:").toLongOrNull() ?: return
        sendProactive(chatId, text)
    }

    private fun startPolling() {
        val voiceFilter = Filter.Custom { voice != null }
        telegramBot = bot {
            this.token = this@TelegramAdapter.token
            dispatch {
                message(Filter.Text or Filter.Command or Filter.Photo or voiceFilter) {
                    val chatId = message.chat.id
                    scope.launch {
                        writeChatId(chatId)

                        // 1. Voice message
                        val voice = message.voice
                        if (voice != null) {
                            if (!voiceEnabled || voiceApiKey.isBlank()) {
                                bot.sendMessage(ChatId.fromId(chatId), "Voice input is not enabled.")
                                return@launch
                            }
                            voiceMode[chatId] = true  // Voice in → voice out
                            val transcript = transcribeVoice(bot, voice.fileId)
                            if (transcript == null) {
                                bot.sendMessage(ChatId.fromId(chatId), "Sorry, I couldn't transcribe that audio.")
                                return@launch
                            }
                            handleProcessedMessage(bot, chatId, normalize(chatId.toString(), transcript))
                            return@launch
                        }

                        // 2. Photo message
                        val photos = message.photo
                        if (!photos.isNullOrEmpty()) {
                            val photo = photos.maxByOrNull { ps -> ps.fileSize ?: 0 }!!
                            val (fileResp, _) = bot.getFile(photo.fileId)
                            val filePath = fileResp?.body()?.result?.filePath
                            if (filePath == null) {
                                bot.sendMessage(ChatId.fromId(chatId), "Could not retrieve the image.")
                                return@launch
                            }
                            val photoUrl = "https://api.telegram.org/file/bot$token/$filePath"
                            val caption = message.caption ?: "What's in this image?"
                            val imgMsg = normalize(chatId.toString(), caption).copy(imageUrl = photoUrl)
                            handleProcessedMessage(bot, chatId, imgMsg)
                            return@launch
                        }

                        // 3. Text / command message — switch off voice mode for text
                        val text = message.text ?: return@launch
                        voiceMode[chatId] = false
                        if (!text.startsWith("/") && onboarding.isActive(chatId)) {
                            onboarding.handle(bot, chatId, text)
                            return@launch
                        }
                        if (handleCommand(bot, chatId, text)) return@launch
                        handleProcessedMessage(bot, chatId, normalize(chatId.toString(), text))
                    }
                }
            }
        }
        telegramBot!!.startPolling()
    }
}
