package com.assistant.agent

import com.assistant.ports.TokenUsage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class TokenStats(val inputTokens: Long, val outputTokens: Long, val totalTokens: Long)

class TokenTracker {
    private val inputBySession  = ConcurrentHashMap<String, AtomicLong>()
    private val outputBySession = ConcurrentHashMap<String, AtomicLong>()

    fun record(sessionId: String, usage: TokenUsage?) {
        if (usage == null) return
        inputBySession.getOrPut(sessionId) { AtomicLong() }.addAndGet(usage.inputTokens.toLong())
        outputBySession.getOrPut(sessionId) { AtomicLong() }.addAndGet(usage.outputTokens.toLong())
    }

    fun sessionStats(sessionId: String): TokenStats {
        val i = inputBySession[sessionId]?.get() ?: 0L
        val o = outputBySession[sessionId]?.get() ?: 0L
        return TokenStats(i, o, i + o)
    }

    fun globalStats(): TokenStats {
        val i = inputBySession.values.sumOf { it.get() }
        val o = outputBySession.values.sumOf { it.get() }
        return TokenStats(i, o, i + o)
    }
}
