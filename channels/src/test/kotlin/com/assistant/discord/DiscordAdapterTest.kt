package com.assistant.discord

import com.assistant.gateway.Gateway
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiscordAdapterTest {

    @Test
    fun `name returns discord`() {
        val adapter = DiscordAdapter(token = "fake", gateway = mockk())
        assertEquals("discord", adapter.name)
    }

    @Test
    fun `send with malformed sessionId does nothing`() {
        // jda is null until start() is called — must not throw
        val adapter = DiscordAdapter(token = "fake", gateway = mockk())
        assertDoesNotThrow { adapter.send("DISCORD:notanumber", "hello") }
    }

    @Test
    fun `send with valid sessionId does nothing when not started`() {
        val adapter = DiscordAdapter(token = "fake", gateway = mockk())
        assertDoesNotThrow { adapter.send("DISCORD:123456789012345678", "hello") }
    }
}
