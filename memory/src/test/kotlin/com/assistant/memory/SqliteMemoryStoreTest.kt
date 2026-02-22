package com.assistant.memory

import com.assistant.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.nio.file.Files

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqliteMemoryStoreTest {
    private lateinit var store: SqliteMemoryStore
    private lateinit var tmpDir: File

    @BeforeAll
    fun setup() {
        tmpDir = Files.createTempDirectory("assistant-test").toFile()
        store = SqliteMemoryStore(":memory:", memoryDir = tmpDir)
        store.init()
    }

    @AfterAll
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    // ── existing tests ────────────────────────────────────────────────────────

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

    // ── facts (file-based) ────────────────────────────────────────────────────

    @Test
    fun `saveFact and facts use MEMORY_md file`() = runTest {
        store.saveFact("user1", "User prefers concise answers")
        store.saveFact("user1", "User is a developer")
        val facts = store.facts("user1")
        assertTrue(facts.contains("User prefers concise answers"))
        assertTrue(facts.contains("User is a developer"))
        assertTrue(File(tmpDir, "MEMORY.md").exists())
    }

    @Test
    fun `facts returns empty list when MEMORY_md absent`() = runTest {
        val emptyDir = Files.createTempDirectory("assistant-empty").toFile()
        try {
            val fresh = SqliteMemoryStore(":memory:", memoryDir = emptyDir)
            fresh.init()
            assertEquals(emptyList<String>(), fresh.facts("nobody"))
        } finally {
            emptyDir.deleteRecursively()
        }
    }

    // ── search (FTS5) ─────────────────────────────────────────────────────────

    @Test
    fun `search returns chunks matching query via FTS`() = runTest {
        store.append("session3", Message("userA", "Kotlin coroutines are powerful for async programming", Channel.TELEGRAM))
        store.append("session3", Message("userA", "I enjoy cooking Italian food on weekends", Channel.TELEGRAM))

        val results = store.search("userA", "Kotlin coroutines", limit = 5)
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.contains("Kotlin", ignoreCase = true) || it.contains("coroutines", ignoreCase = true) })
    }

    @Test
    fun `search returns empty list when no match`() = runTest {
        val results = store.search("userA", "xyzzy_no_such_term_12345", limit = 5)
        assertEquals(emptyList<String>(), results)
    }

    @Test
    fun `search returns empty list for blank query`() = runTest {
        val results = store.search("userA", "   ", limit = 5)
        assertEquals(emptyList<String>(), results)
    }

    // ── pure helpers ──────────────────────────────────────────────────────────

    @Test
    fun `chunk returns single chunk for short text`() {
        val result = chunk("short text", maxLen = 512)
        assertEquals(1, result.size)
        assertEquals("short text", result.first())
    }

    @Test
    fun `chunk splits long text with overlap`() {
        val text = "a".repeat(600)
        val result = chunk(text, maxLen = 512, overlap = 80)
        assertTrue(result.size >= 2)
        assertEquals(512, result.first().length)
        // second chunk starts at offset 512-80=432, so it ends at min(432+512, 600)=600
        assertEquals(600 - (512 - 80), result[1].length)
    }

    @Test
    fun `cosine of identical vectors is 1`() {
        val v = floatArrayOf(1f, 2f, 3f)
        assertEquals(1.0f, cosine(v, v), 1e-5f)
    }

    @Test
    fun `cosine of orthogonal vectors is 0`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(0.0f, cosine(a, b), 1e-5f)
    }

    @Test
    fun `FloatArray round-trips through bytes`() {
        val original = floatArrayOf(1.0f, -2.5f, 3.14f)
        val restored = original.toBytes().toFloatArray()
        assertArrayEquals(original, restored, 1e-6f)
    }

    // ── atomicity & concurrency ───────────────────────────────────────────────

    @Test
    fun `append is atomic - message and chunks present together`() = runTest {
        val msg = Message("atomicUser", "Atomic append test message", Channel.TELEGRAM)
        store.append("atomicSession", msg)
        // Both the message row and its chunk must exist after a single append
        val history = store.history("atomicSession", 1)
        assertEquals(1, history.size)
        val results = store.search("atomicUser", "Atomic append test", limit = 5)
        assertTrue(results.isNotEmpty(), "Expected chunk to be searchable after append")
    }

    @Test
    fun `concurrent saveFact calls produce correct file content`() = runTest {
        val concurrentDir = Files.createTempDirectory("assistant-concurrent").toFile()
        try {
            val s = SqliteMemoryStore(":memory:", memoryDir = concurrentDir)
            s.init()
            val facts = (1..10).map { i -> "fact-$i" }
            facts.map { f -> async { s.saveFact("u", f) } }.awaitAll()
            val written = s.facts("u")
            assertEquals(10, written.size, "All 10 facts should be written")
            facts.forEach { f -> assertTrue(written.contains(f), "Missing: $f") }
        } finally {
            concurrentDir.deleteRecursively()
        }
    }
}
