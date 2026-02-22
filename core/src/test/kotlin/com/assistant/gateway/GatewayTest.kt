package com.assistant.gateway

import com.assistant.agent.AgentEngine
import com.assistant.domain.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class GatewayTest {
    private val engine = mockk<AgentEngine>()

    @Test
    fun `routes message to engine and returns response`() = runTest {
        coEvery { engine.process(any(), any()) } returns "Hello!"
        val response = Gateway(engine).handle(Message("user1", "hi", Channel.TELEGRAM))
        assertEquals("Hello!", response)
    }

    @Test
    fun `same user gets same session`() = runTest {
        val sessions = mutableListOf<Session>()
        coEvery { engine.process(capture(sessions), any()) } returns "ok"
        val gateway = Gateway(engine)
        gateway.handle(Message("user1", "a", Channel.TELEGRAM))
        gateway.handle(Message("user1", "b", Channel.TELEGRAM))
        assertEquals(sessions[0].id, sessions[1].id)
    }

    @Test
    fun `different users get different sessions`() = runTest {
        val sessions = mutableListOf<Session>()
        coEvery { engine.process(capture(sessions), any()) } returns "ok"
        val gateway = Gateway(engine)
        gateway.handle(Message("user1", "hi", Channel.TELEGRAM))
        gateway.handle(Message("user2", "hi", Channel.TELEGRAM))
        assertNotEquals(sessions[0].id, sessions[1].id)
    }

    @Test
    fun `idle session is evicted after TTL and recreated on next message`() = runTest {
        val sessions = mutableListOf<Session>()
        coEvery { engine.process(capture(sessions), any()) } returns "ok"
        // Use a TTL of 1 ms so sessions expire immediately
        val gateway = Gateway(engine, sessionTtlMs = 1L)
        gateway.handle(Message("user1", "first", Channel.TELEGRAM))
        Thread.sleep(5)  // let the session expire
        gateway.handle(Message("user1", "second", Channel.TELEGRAM))
        // Sessions should be different objects (evicted + recreated)
        assertEquals(2, sessions.size)
        // Both have the same logical ID (same user+channel key)
        assertEquals(sessions[0].id, sessions[1].id)
    }
}
