package com.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.TimeUnit

class ConfigWatcher(
    private val paths: List<Path>,
    private val scope: CoroutineScope,
    private val onChange: suspend () -> Unit
) {
    fun start(): Job = scope.launch(Dispatchers.IO) {
        if (paths.isEmpty()) return@launch
        val dir = paths.first().parent ?: return@launch
        val ws = dir.fileSystem.newWatchService()
        dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY)
        // Record startup time; ignore stale events from files modified before we started
        val startedAt = System.currentTimeMillis()
        var lastTriggered = 0L
        while (isActive) {
            val key = ws.poll(1, TimeUnit.SECONDS) ?: continue
            val triggered = key.pollEvents().any { ev ->
                @Suppress("UNCHECKED_CAST")
                val changed = dir.resolve(ev.context() as Path)
                if (paths.none { it == changed }) return@any false
                val modifiedAt = changed.toFile().lastModified()
                modifiedAt > startedAt
            }
            key.reset()
            if (triggered) {
                val now = System.currentTimeMillis()
                if (now - lastTriggered > 5_000) { // debounce: ignore repeats within 5s
                    lastTriggered = now
                    onChange()
                }
            }
        }
    }
}
