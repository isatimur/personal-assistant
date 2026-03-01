package com.assistant.ports

enum class SignalType { CORRECTION, APPROVAL, HIGH_STEPS, TOOL_ERROR }

data class Signal(
    val id: Long = 0,
    val sessionId: String,
    val userId: String,
    val type: SignalType,
    val context: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class FeedbackStats(
    val totalSessions: Int,
    val corrections: Int,
    val approvals: Int,
    val toolErrors: Int,
    val highSteps: Int
)

interface FeedbackPort {
    suspend fun recordSignal(signal: Signal)
    suspend fun signalsFor(userId: String, sinceMs: Long): List<Signal>
    suspend fun markReflected(sessionIds: List<String>)
    suspend fun unreflectedSessions(userId: String, sinceMs: Long): List<String>
    suspend fun stats(userId: String, sinceMs: Long): FeedbackStats
}
