package com.assistant

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PluginLoaderTest {

    @Test
    fun `loadTools returns empty list when plugins dir does not exist`(@TempDir tmpDir: File) {
        val loader = PluginLoader(pluginsDir = File(tmpDir, "nonexistent"))
        assertTrue(loader.loadTools().isEmpty())
    }

    @Test
    fun `loadChannels returns empty list when plugins dir does not exist`(@TempDir tmpDir: File) {
        val loader = PluginLoader(pluginsDir = File(tmpDir, "nonexistent"))
        assertTrue(loader.loadChannels().isEmpty())
    }

    @Test
    fun `loadTools returns empty list when plugins dir is empty`(@TempDir tmpDir: File) {
        val emptyDir = File(tmpDir, "plugins").also { it.mkdirs() }
        val loader = PluginLoader(pluginsDir = emptyDir)
        assertTrue(loader.loadTools().isEmpty())
    }

    @Test
    fun `loadChannels returns empty list when plugins dir is empty`(@TempDir tmpDir: File) {
        val emptyDir = File(tmpDir, "plugins").also { it.mkdirs() }
        val loader = PluginLoader(pluginsDir = emptyDir)
        assertTrue(loader.loadChannels().isEmpty())
    }

    @Test
    fun `loadTools returns empty list when dir has no jar files`(@TempDir tmpDir: File) {
        val dir = File(tmpDir, "plugins").also { it.mkdirs() }
        File(dir, "notajar.txt").writeText("hello")
        val loader = PluginLoader(pluginsDir = dir)
        assertTrue(loader.loadTools().isEmpty())
    }
}
