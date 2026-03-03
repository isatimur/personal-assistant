package com.assistant.ports

import com.assistant.domain.*

data class MemoryStats(val factsCount: Int, val chunkCount: Int, val messageCount: Int)

data class ChatMessage(val role: String, val content: String, val imageUrl: String? = null)

data class ParamSpec(
    val name: String,
    val type: String,        // "string", "integer", "boolean"
    val description: String,
    val required: Boolean = true
)

data class CommandSpec(
    val name: String,
    val description: String,
    val params: List<ParamSpec>
)

data class TokenUsage(val inputTokens: Int, val outputTokens: Int)

sealed class FunctionCompletion {
    data class Text(val content: String, val usage: TokenUsage? = null) : FunctionCompletion()
    data class FunctionCall(val name: String, val argsJson: String, val usage: TokenUsage? = null) : FunctionCompletion()
}

/**
 * Prefix used in `onProgress` callbacks to signal real LLM streaming tokens (vs tool messages).
 * TelegramAdapter and other channel adapters check for this prefix to distinguish token
 * fragments from tool-use progress messages like "Using shell...".
 */
const val STREAM_TOKEN_PREFIX = "\u0001"

interface LlmPort {
    suspend fun complete(messages: List<ChatMessage>): String
    suspend fun completeWithFunctions(messages: List<ChatMessage>, commands: List<CommandSpec>): FunctionCompletion
    /** Uses a faster/cheaper model for tool-selection steps. Defaults to the standard model. */
    suspend fun completeWithFunctionsFast(messages: List<ChatMessage>, commands: List<CommandSpec>): FunctionCompletion =
        completeWithFunctions(messages, commands)
    /**
     * Streams the final text response token-by-token, calling [onToken] for each fragment.
     * Returns the full accumulated text. Does not support function calls — use for the
     * final synthesis step only.
     */
    suspend fun stream(messages: List<ChatMessage>, onToken: suspend (String) -> Unit): String
}

interface TtsPort {
    /** Synthesizes [text] to speech and returns the audio as MP3 bytes. */
    suspend fun synthesize(text: String): ByteArray
}

interface ToolPort {
    val name: String
    val description: String
    fun commands(): List<CommandSpec>
    suspend fun execute(call: ToolCall): Observation
}

interface EmbeddingPort {
    suspend fun embed(text: String): FloatArray
}

interface MemoryPort {
    suspend fun append(sessionId: String, message: Message)
    suspend fun history(sessionId: String, limit: Int): List<Message>
    suspend fun facts(userId: String): List<String>
    suspend fun saveFact(userId: String, fact: String)
    suspend fun deleteFact(userId: String, fact: String)
    suspend fun search(userId: String, query: String, limit: Int = 5): List<String>
    suspend fun clearHistory(sessionId: String)
    suspend fun trimHistory(sessionId: String, deleteCount: Int)
    suspend fun stats(userId: String): MemoryStats
}

interface ChannelPort {
    /** Unique channel identifier, e.g. "telegram", "discord". */
    val name: String
    /**
     * Start receiving messages. The [onMessage] lambda is called for every inbound
     * message and must return the reply string. Implementations run their own
     * polling/webhook loop in a background coroutine.
     */
    fun start(onMessage: suspend (sessionId: String, userId: String, text: String, imageUrl: String?) -> String)
    /** Send a proactive/outbound message to an existing session. */
    fun send(sessionId: String, text: String)
}

interface EnginePlugin {
    val name: String get() = this::class.simpleName ?: "plugin"
    suspend fun onInput(session: Session, message: Message) {}
    suspend fun beforeTool(session: Session, call: ToolCall) {}
    suspend fun afterTool(session: Session, call: ToolCall, result: Observation, durationMs: Long) {}
    suspend fun beforeLlm(session: Session, stepIndex: Int) {}
    suspend fun afterLlm(session: Session, stepIndex: Int, usage: TokenUsage?, durationMs: Long) {}
    suspend fun onResponse(session: Session, text: String, steps: Int) {}
    suspend fun onError(session: Session, error: Exception) {}
}
