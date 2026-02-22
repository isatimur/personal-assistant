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
}
