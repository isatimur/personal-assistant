package com.assistant.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

interface AgentBus {
    fun registerAgent(name: String, handler: suspend (from: String, message: String) -> String)
    suspend fun request(from: String, to: String, message: String, timeoutMs: Long = 30_000): String
}

class InProcessAgentBus(private val scope: CoroutineScope) : AgentBus {

    private data class AgentRequest(
        val from: String,
        val message: String,
        val reply: CompletableDeferred<String>
    )

    private val queues = ConcurrentHashMap<String, Channel<AgentRequest>>()

    override fun registerAgent(name: String, handler: suspend (from: String, message: String) -> String) {
        val queue = Channel<AgentRequest>(capacity = 64)
        queues[name] = queue
        scope.launch {
            for (req in queue) {
                val result = runCatching { handler(req.from, req.message) }
                if (result.isSuccess) req.reply.complete(result.getOrThrow())
                else req.reply.completeExceptionally(result.exceptionOrNull()!!)
            }
        }
    }

    override suspend fun request(from: String, to: String, message: String, timeoutMs: Long): String {
        val queue = queues[to] ?: return "Error: no agent named '$to'"
        val reply = CompletableDeferred<String>()
        queue.send(AgentRequest(from, message, reply))
        return withTimeoutOrNull(timeoutMs) { reply.await() }
            ?: "Error: agent '$to' timed out after ${timeoutMs}ms"
    }
}
