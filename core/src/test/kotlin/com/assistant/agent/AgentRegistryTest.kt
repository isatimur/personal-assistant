package com.assistant.agent

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AgentRegistryTest {

    // --- StaticAgentRegistry ---

    @Test
    fun `static registry resolves known agent`() {
        val registry = StaticAgentRegistry(mapOf("worker" to "localhost:9090"))
        assertEquals("localhost:9090", registry.resolve("worker"))
    }

    @Test
    fun `static registry returns null for unknown agent`() {
        val registry = StaticAgentRegistry(emptyMap())
        assertNull(registry.resolve("ghost"))
    }

    @Test
    fun `static registry all returns full map`() {
        val entries = mapOf("a" to "h:1", "b" to "h:2")
        assertEquals(entries, StaticAgentRegistry(entries).all())
    }

    @Test
    fun `static registry register is a no-op`() {
        val registry = StaticAgentRegistry(emptyMap())
        registry.register("anything", "localhost:9999")  // must not throw
        assertNull(registry.resolve("anything"))
    }

    // --- FileSystemAgentRegistry ---

    @Test
    fun `filesystem registry resolve returns null before register`(@TempDir dir: File) {
        val registry = FileSystemAgentRegistry(dir)
        assertNull(registry.resolve("worker"))
    }

    @Test
    fun `filesystem registry register then resolve`(@TempDir dir: File) {
        val registry = FileSystemAgentRegistry(dir)
        registry.register("worker", "localhost:9090")
        assertEquals("localhost:9090", registry.resolve("worker"))
        // cleanup: delete the file to avoid JVM shutdown hook running in wrong context
        File(dir, "worker.address").delete()
    }

    @Test
    fun `filesystem registry all lists registered agents`(@TempDir dir: File) {
        val registry = FileSystemAgentRegistry(dir)
        File(dir, "a.address").writeText("localhost:9001")
        File(dir, "b.address").writeText("localhost:9002")
        val all = registry.all()
        assertEquals("localhost:9001", all["a"])
        assertEquals("localhost:9002", all["b"])
    }
}
