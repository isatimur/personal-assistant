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
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
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

    @Test
    fun `delayUntilTime returns correct delay for future time same day`() {
        val runner = HeartbeatRunner(
            config = HeartbeatConfig(),
            gateway = mockk(),
            send = {},
            chatIdFile = File(tmpDir, "last-chat-id")
        )
        val now = LocalDateTime.of(2024, 1, 1, 8, 0, 0)
        val delay = runner.delayUntilTime("08:30", now)
        assertEquals(30.minutes, delay)
    }

    @Test
    fun `delayUntilTime returns next-day delay when time already passed`() {
        val runner = HeartbeatRunner(
            config = HeartbeatConfig(),
            gateway = mockk(),
            send = {},
            chatIdFile = File(tmpDir, "last-chat-id")
        )
        val now = LocalDateTime.of(2024, 1, 1, 9, 0, 0)
        val delay = runner.delayUntilTime("08:30", now)
        assertEquals(23.hours + 30.minutes, delay)
    }

    @Test
    fun `time-based heartbeat fires at computed delay`() = runTest {
        val chatIdFile = File(tmpDir, "last-chat-id").also { it.writeText("12345") }
        coEvery { mockk<Gateway>().handle(any()) } returns "focus update"
        val gateway = mockk<Gateway>()
        coEvery { gateway.handle(any()) } returns "focus update"
        val sent = mutableListOf<String>()

        val runner = HeartbeatRunner(
            config = HeartbeatConfig(enabled = true, time = "00:00"),
            gateway = gateway,
            send = { sent.add(it) },
            chatIdFile = chatIdFile,
            scope = this
        )
        runner.start()
        // advance 25 hours — guarantees at least one time-based fire
        advanceTimeBy(25.hours)
        runner.stop()
        assertTrue(sent.isNotEmpty(), "Expected at least one time-based heartbeat message")
    }

    @Test
    fun `cron path fires after delay`() = runTest {
        val chatIdFile = File(tmpDir, "last-chat-id").also { it.writeText("12345") }
        val gateway = mockk<Gateway>()
        coEvery { gateway.handle(any()) } returns "cron update"
        val sent = mutableListOf<String>()

        val runner = HeartbeatRunner(
            config = HeartbeatConfig(enabled = true, cron = "* * * * *", prompt = "cron check"),
            gateway = gateway,
            send = { sent.add(it) },
            chatIdFile = chatIdFile,
            scope = this
        )
        runner.start()
        // every-minute cron: delay is at most 60s; advance 65s to guarantee one fire
        advanceTimeBy(65.seconds)
        runner.stop()
        assertTrue(sent.isNotEmpty(), "Expected at least one cron heartbeat message")
    }

    @Test
    fun `multi-agent launches separate coroutines and stop cancels all`() = runTest {
        val chatIdFile = File(tmpDir, "last-chat-id").also { it.writeText("12345") }
        val gateway = mockk<Gateway>()
        coEvery { gateway.handle(any()) } returns "agent reply"
        val prompts = mutableListOf<String>()

        val runner = HeartbeatRunner(
            config = HeartbeatConfig(
                enabled = true,
                agents = listOf(
                    HeartbeatAgent("agent1", "* * * * *", "prompt1"),
                    HeartbeatAgent("agent2", "* * * * *", "prompt2")
                )
            ),
            gateway = gateway,
            send = { prompts.add(it) },
            chatIdFile = chatIdFile,
            scope = this
        )
        runner.start()
        advanceTimeBy(65.seconds)
        runner.stop()
        assertTrue(prompts.isNotEmpty(), "Expected messages from multi-agent heartbeat")
    }

    @Test
    fun `stop cancels all jobs`() = runTest {
        val chatIdFile = File(tmpDir, "last-chat-id").also { it.writeText("12345") }
        val gateway = mockk<Gateway>()
        coEvery { gateway.handle(any()) } returns "reply"
        val sent = mutableListOf<String>()

        val runner = HeartbeatRunner(
            config = HeartbeatConfig(enabled = true, every = "1s"),
            gateway = gateway,
            send = { sent.add(it) },
            chatIdFile = chatIdFile,
            scope = this
        )
        runner.start()
        advanceTimeBy(2.seconds)
        runner.stop()
        val countAfterStop = sent.size
        advanceTimeBy(5.seconds)
        assertEquals(countAfterStop, sent.size, "No more messages should be sent after stop()")
    }
}
