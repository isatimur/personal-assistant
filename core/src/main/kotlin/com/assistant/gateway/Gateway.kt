package com.assistant.gateway

import com.assistant.agent.AgentEngine
import com.assistant.domain.*
import java.util.concurrent.ConcurrentHashMap

class Gateway(private val engine: AgentEngine, private val sessionTtlMs: Long = 24 * 60 * 60 * 1000L) {
    private val sessions   = ConcurrentHashMap<String, Session>()
    private val lastActive = ConcurrentHashMap<String, Long>()

    private fun evictExpired() {
        val cutoff = System.currentTimeMillis() - sessionTtlMs
        sessions.entries.removeIf { (key, _) -> (lastActive[key] ?: 0L) < cutoff }
        lastActive.entries.removeIf { it.value < cutoff }
    }

    fun clearSession(sessionKey: String) {
        sessions.remove(sessionKey)
        lastActive.remove(sessionKey)
    }

    suspend fun handle(message: Message): String {
        evictExpired()
        val key = "${message.channel}:${message.sender}"
        val session = sessions.getOrPut(key) { Session(id = key, userId = message.sender, channel = message.channel) }
        lastActive[key] = System.currentTimeMillis()
        return engine.process(session, message)
    }
}
