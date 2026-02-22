package com.assistant.memory

import com.assistant.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqliteMemoryStoreTest {
    private lateinit var store: SqliteMemoryStore

    @BeforeAll
    fun setup() {
        store = SqliteMemoryStore(":memory:")
        store.init()
    }

    @Test
    fun `append and retrieve message history`() = runTest {
        val msg = Message("user1", "hello world", Channel.TELEGRAM)
        store.append("session1", msg)
        val history = store.history("session1", 10)
        assertEquals(1, history.size)
        assertEquals("hello world", history.first().text)
    }

    @Test
    fun `history respects limit`() = runTest {
        repeat(5) { i ->
            store.append("session2", Message("user2", "msg $i", Channel.TELEGRAM))
        }
        val history = store.history("session2", 3)
        assertEquals(3, history.size)
    }

    @Test
    fun `save and retrieve facts`() = runTest {
        store.saveFact("user1", "User prefers concise answers")
        val facts = store.facts("user1")
        assertTrue(facts.contains("User prefers concise answers"))
    }
}
