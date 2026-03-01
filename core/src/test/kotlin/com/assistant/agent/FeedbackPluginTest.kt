package com.assistant.agent

import com.assistant.domain.Channel
import com.assistant.domain.Observation
import com.assistant.domain.Session
import com.assistant.domain.ToolCall
import com.assistant.ports.FeedbackPort
import com.assistant.ports.Signal
import com.assistant.ports.SignalType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FeedbackPluginTest {
    private lateinit var store: FeedbackPort
    private lateinit var plugin: FeedbackPlugin
    private val session = Session("sess-1", "user1", Channel.TELEGRAM)
    private val call = ToolCall("shell_run", mapOf("command" to "echo hi"))

    @BeforeEach
    fun setup() {
        store = mockk(relaxed = true)
        plugin = FeedbackPlugin(store, "default")
    }

    // ── afterTool ─────────────────────────────────────────────────────────────

    @Test
    fun `afterTool records TOOL_ERROR on Observation Error`() = runTest {
        plugin.afterTool(session, call, Observation.Error("connection refused"), 100L)

        val signalSlot = slot<Signal>()
        coVerify { store.recordSignal(capture(signalSlot)) }
        assertEquals(SignalType.TOOL_ERROR, signalSlot.captured.type)
        assertTrue(signalSlot.captured.context.contains("shell_run"))
        assertEquals("sess-1", signalSlot.captured.sessionId)
    }

    @Test
    fun `afterTool does not record signal on Observation Success`() = runTest {
        plugin.afterTool(session, call, Observation.Success("done"), 50L)
        coVerify(exactly = 0) { store.recordSignal(any()) }
    }

    @Test
    fun `afterTool truncates long error message in context`() = runTest {
        val longMessage = "x".repeat(200)
        plugin.afterTool(session, call, Observation.Error(longMessage), 10L)

        val signalSlot = slot<Signal>()
        coVerify { store.recordSignal(capture(signalSlot)) }
        // context = "${call.name}: ${message.take(120)}" — allow for tool name + separator
        assertTrue(signalSlot.captured.context.length <= 135, "Context should be truncated")
        assertFalse(signalSlot.captured.context.length >= 200, "Context should not contain full long message")
    }

    // ── onResponse ────────────────────────────────────────────────────────────

    @Test
    fun `onResponse records HIGH_STEPS when steps greater than 5`() = runTest {
        plugin.onResponse(session, "response", 6)

        val signalSlot = slot<Signal>()
        coVerify { store.recordSignal(capture(signalSlot)) }
        assertEquals(SignalType.HIGH_STEPS, signalSlot.captured.type)
        assertTrue(signalSlot.captured.context.contains("6"))
    }

    @Test
    fun `onResponse records APPROVAL when steps equals 1`() = runTest {
        plugin.onResponse(session, "quick reply", 1)

        val signalSlot = slot<Signal>()
        coVerify { store.recordSignal(capture(signalSlot)) }
        assertEquals(SignalType.APPROVAL, signalSlot.captured.type)
    }

    @Test
    fun `onResponse does not record signal for moderate step count`() = runTest {
        plugin.onResponse(session, "response", 3)
        coVerify(exactly = 0) { store.recordSignal(any()) }
    }

    @Test
    fun `onResponse does not record signal for exactly 5 steps`() = runTest {
        plugin.onResponse(session, "response", 5)
        coVerify(exactly = 0) { store.recordSignal(any()) }
    }

    // ── recordUserMessage ─────────────────────────────────────────────────────

    @Test
    fun `recordUserMessage records CORRECTION for text containing wrong`() = runTest {
        plugin.recordUserMessage("sess-1", "that is wrong, please fix it")

        val signalSlot = slot<Signal>()
        coVerify { store.recordSignal(capture(signalSlot)) }
        assertEquals(SignalType.CORRECTION, signalSlot.captured.type)
        assertEquals("sess-1", signalSlot.captured.sessionId)
        assertEquals("default", signalSlot.captured.userId)
    }

    @Test
    fun `recordUserMessage records CORRECTION for text with correction keyword`() = runTest {
        plugin.recordUserMessage("sess-2", "actually that is not right")

        val signalSlot = slot<Signal>()
        coVerify { store.recordSignal(capture(signalSlot)) }
        assertEquals(SignalType.CORRECTION, signalSlot.captured.type)
    }

    @Test
    fun `recordUserMessage does not record signal for normal message`() = runTest {
        plugin.recordUserMessage("sess-3", "can you help me with this task?")
        coVerify(exactly = 0) { store.recordSignal(any()) }
    }

    @Test
    fun `recordUserMessage is case-insensitive for keywords`() = runTest {
        plugin.recordUserMessage("sess-4", "WRONG answer, try again")

        coVerify(exactly = 1) { store.recordSignal(any()) }
    }

    @Test
    fun `recordUserMessage truncates long text in context`() = runTest {
        val longText = "actually " + "x".repeat(200)
        plugin.recordUserMessage("sess-5", longText)

        val signalSlot = slot<Signal>()
        coVerify { store.recordSignal(capture(signalSlot)) }
        assertTrue(signalSlot.captured.context.length <= 120, "Context should be truncated to 120 chars")
    }
}
