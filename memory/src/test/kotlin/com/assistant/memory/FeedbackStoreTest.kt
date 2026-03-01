package com.assistant.memory

import com.assistant.ports.Signal
import com.assistant.ports.SignalType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedbackStoreTest {
    private lateinit var store: FeedbackStore

    @BeforeEach
    fun setup() {
        store = FeedbackStore(":memory:")
        store.init()
    }

    @Test
    fun `recordSignal persists and signalsFor retrieves by userId and time`() = runTest {
        val now = System.currentTimeMillis()
        store.recordSignal(Signal(sessionId = "s1", userId = "u1", type = SignalType.APPROVAL, context = "ok"))
        store.recordSignal(Signal(sessionId = "s2", userId = "u2", type = SignalType.TOOL_ERROR, context = "err"))

        val u1Signals = store.signalsFor("u1", now - 1000)
        assertEquals(1, u1Signals.size)
        assertEquals(SignalType.APPROVAL, u1Signals.first().type)
        assertEquals("ok", u1Signals.first().context)
        assertEquals("s1", u1Signals.first().sessionId)
    }

    @Test
    fun `signalsFor excludes signals before sinceMs`() = runTest {
        val past = System.currentTimeMillis() - 10_000
        store.recordSignal(Signal(sessionId = "s1", userId = "u1", type = SignalType.CORRECTION, context = "old", createdAt = past))
        store.recordSignal(Signal(sessionId = "s2", userId = "u1", type = SignalType.APPROVAL, context = "new"))

        val recent = store.signalsFor("u1", System.currentTimeMillis() - 5_000)
        assertEquals(1, recent.size)
        assertEquals(SignalType.APPROVAL, recent.first().type)
    }

    @Test
    fun `unreflectedSessions returns sessions with signals that are not yet reflected`() = runTest {
        val since = System.currentTimeMillis() - 1000
        store.recordSignal(Signal(sessionId = "sess-a", userId = "u1", type = SignalType.APPROVAL, context = ""))
        store.recordSignal(Signal(sessionId = "sess-b", userId = "u1", type = SignalType.HIGH_STEPS, context = ""))

        val sessions = store.unreflectedSessions("u1", since)
        assertTrue("sess-a" in sessions)
        assertTrue("sess-b" in sessions)
    }

    @Test
    fun `markReflected removes sessions from unreflected list`() = runTest {
        val since = System.currentTimeMillis() - 1000
        store.recordSignal(Signal(sessionId = "sess-a", userId = "u1", type = SignalType.APPROVAL, context = ""))
        store.recordSignal(Signal(sessionId = "sess-b", userId = "u1", type = SignalType.TOOL_ERROR, context = ""))

        store.markReflected(listOf("sess-a"))

        val remaining = store.unreflectedSessions("u1", since)
        assertFalse("sess-a" in remaining, "sess-a should be reflected")
        assertTrue("sess-b" in remaining, "sess-b should still be unreflected")
    }

    @Test
    fun `markReflected is idempotent`() = runTest {
        val since = System.currentTimeMillis() - 1000
        store.recordSignal(Signal(sessionId = "sess-x", userId = "u1", type = SignalType.APPROVAL, context = ""))
        store.markReflected(listOf("sess-x"))
        store.markReflected(listOf("sess-x")) // second call must not throw
        val remaining = store.unreflectedSessions("u1", since)
        assertFalse("sess-x" in remaining)
    }

    @Test
    fun `stats counts signal types correctly`() = runTest {
        val since = System.currentTimeMillis() - 1000
        store.recordSignal(Signal(sessionId = "s1", userId = "u1", type = SignalType.CORRECTION, context = ""))
        store.recordSignal(Signal(sessionId = "s1", userId = "u1", type = SignalType.CORRECTION, context = ""))
        store.recordSignal(Signal(sessionId = "s2", userId = "u1", type = SignalType.APPROVAL, context = ""))
        store.recordSignal(Signal(sessionId = "s3", userId = "u1", type = SignalType.TOOL_ERROR, context = ""))
        store.recordSignal(Signal(sessionId = "s3", userId = "u1", type = SignalType.HIGH_STEPS, context = ""))

        val stats = store.stats("u1", since)
        assertEquals(3, stats.totalSessions)  // s1, s2, s3
        assertEquals(2, stats.corrections)
        assertEquals(1, stats.approvals)
        assertEquals(1, stats.toolErrors)
        assertEquals(1, stats.highSteps)
    }

    @Test
    fun `stats returns zeros when no signals for userId`() = runTest {
        val stats = store.stats("nobody", System.currentTimeMillis() - 1000)
        assertEquals(0, stats.totalSessions)
        assertEquals(0, stats.corrections)
    }
}
