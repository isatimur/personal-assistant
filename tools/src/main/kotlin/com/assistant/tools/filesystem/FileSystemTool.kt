package com.assistant.tools.filesystem

import com.assistant.domain.*
import com.assistant.ports.ToolPort
import java.io.File

class FileSystemTool : ToolPort {
    override val name = "file_system"
    override val description = "Reads, writes, lists, and deletes files. Commands: file_read(path), file_write(path, content), file_list(path), file_delete(path)"

    override suspend fun execute(call: ToolCall): Observation = runCatching {
        when (call.name) {
            "file_read" -> Observation.Success(File(call.arguments["path"] as String).readText())
            "file_write" -> {
                val file = File(call.arguments["path"] as String)
                file.parentFile?.mkdirs()
                file.writeText(call.arguments["content"] as String)
                Observation.Success("Written to ${call.arguments["path"]}")
            }
            "file_list" -> {
                val files = File(call.arguments["path"] as String).listFiles()
                    ?.joinToString("\n") { it.name } ?: "(empty)"
                Observation.Success(files)
            }
            "file_delete" -> {
                File(call.arguments["path"] as String).delete()
                Observation.Success("Deleted ${call.arguments["path"]}")
            }
            else -> Observation.Error("Unknown file command: ${call.name}")
        }
    }.getOrElse { Observation.Error(it.message ?: "Unknown error") }
}
