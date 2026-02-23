package com.assistant.heartbeat

import com.assistant.domain.*
import com.assistant.gateway.Gateway
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatRunnerTest {

    @TempDir
    lateinit var tmpDir: File

    @Test
    fun `no-op when disabled`() = runTest {
        val gateway = mockk<Gateway>()
        var called = false
        val runner = HeartbeatRunner(
            config = HeartbeatConfig(enabled = false),
            gateway = gateway,
            send = { called = true },
            chatIdFile = File(tmpDir, "last-chat-id"),
            scope = this
        )
        runner.start()
        advanceTimeBy(2.seconds)
        runner.stop()
        assertFalse(called)
        coVerify(exactly = 0) { gateway.handle(any()) }
    }

    @Test
    fun `skips when chatIdFile absent`() = runTest {
        val gateway = mockk<Gateway>()
        var called = false
        val runner = HeartbeatRunner(
            config = HeartbeatConfig(enabled = true, every = "1s"),
            gateway = gateway,
            send = { called = true },
            chatIdFile = File(tmpDir, "last-chat-id"),  // does not exist
            scope = this
        )
        runner.start()
        advanceTimeBy(2.seconds)
        runner.stop()
        assertFalse(called)
        coVerify(exactly = 0) { gateway.handle(any()) }
    }

    @Test
    fun `fires after interval when chatIdFile present`() = runTest {
        val gateway = mockk<Gateway>()
        val chatIdFile = File(tmpDir, "last-chat-id").also { it.writeText("12345") }
        coEvery { gateway.handle(any()) } returns "Proactive update"
        val sent = mutableListOf<String>()
        val runner = HeartbeatRunner(
            config = HeartbeatConfig(enabled = true, every = "1s", prompt = "Check things"),
            gateway = gateway,
            send = { sent.add(it) },
            chatIdFile = chatIdFile,
            scope = this
        )
        runner.start()
        advanceTimeBy(2.seconds)
        runner.stop()
        assertTrue(sent.isNotEmpty(), "Expected at least one proactive message to be sent")
        assertEquals("Proactive update", sent.first())
    }

    @Test
    fun `parseInterval handles all units`() {
        val runner = HeartbeatRunner(
            config = HeartbeatConfig(),
            gateway = mockk(),
            send = {},
            chatIdFile = File(tmpDir, "last-chat-id")
        )
        assertEquals(30.seconds, runner.parseInterval("30s"))
        assertEquals((30 * 60).seconds, runner.parseInterval("30m"))
        assertEquals((60 * 60).seconds, runner.parseInterval("1h"))
        assertEquals((24 * 60 * 60).seconds, runner.parseInterval("1d"))
    }

    @Test
    fun `parseInterval throws on invalid input`() {
        val runner = HeartbeatRunner(
            config = HeartbeatConfig(),
            gateway = mockk(),
            send = {},
            chatIdFile = File(tmpDir, "last-chat-id")
        )
        assertThrows(IllegalArgumentException::class.java) { runner.parseInterval("bad") }
    }
}
