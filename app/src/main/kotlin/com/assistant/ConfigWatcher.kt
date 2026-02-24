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
        while (isActive) {
            val key = ws.poll(1, TimeUnit.SECONDS) ?: continue
            val triggered = key.pollEvents().any { ev ->
                @Suppress("UNCHECKED_CAST")
                val changed = dir.resolve(ev.context() as Path)
                paths.any { it == changed }
            }
            key.reset()
            if (triggered) onChange()
        }
    }
}
