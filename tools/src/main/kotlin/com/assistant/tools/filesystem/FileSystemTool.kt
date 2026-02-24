package com.assistant.tools.filesystem

import com.assistant.domain.*
import com.assistant.ports.CommandSpec
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort
import java.io.File

private const val MAX_READ_BYTES = 100 * 1024  // 100 KB

class FileSystemTool(allowedPaths: List<String> = listOf(System.getProperty("user.home"))) : ToolPort {
    override val name = "file_system"
    override val description = "Reads, writes, lists, and deletes files. Commands: file_read(path), file_write(path, content), file_list(path), file_delete(path)"

    private val allowedRoots: List<File> = allowedPaths.map { p ->
        File(p.replace("~", System.getProperty("user.home"))).canonicalFile
    }

    override fun commands(): List<CommandSpec> = listOf(
        CommandSpec(
            name = "file_read",
            description = "Read the contents of a file",
            params = listOf(ParamSpec("path", "string", "Absolute or home-relative file path"))
        ),
        CommandSpec(
            name = "file_write",
            description = "Write content to a file, creating parent directories if needed",
            params = listOf(
                ParamSpec("path", "string", "Absolute or home-relative file path"),
                ParamSpec("content", "string", "Content to write to the file")
            )
        ),
        CommandSpec(
            name = "file_list",
            description = "List files and directories in a directory",
            params = listOf(ParamSpec("path", "string", "Directory path to list"))
        ),
        CommandSpec(
            name = "file_delete",
            description = "Delete a file",
            params = listOf(ParamSpec("path", "string", "Absolute or home-relative file path to delete"))
        )
    )

    private fun assertAllowed(path: String) {
        val canonical = File(path).canonicalFile
        check(allowedRoots.any { canonical.startsWith(it) }) {
            "Access denied: $path is outside allowed paths"
        }
    }

    override suspend fun execute(call: ToolCall): Observation = runCatching {
        when (call.name) {
            "file_read" -> {
                val path = call.arguments["path"] as String
                assertAllowed(path)
                val file = File(path)
                val bytes = file.readBytes()
                if (bytes.size > MAX_READ_BYTES) {
                    val truncated = bytes.copyOf(MAX_READ_BYTES).toString(Charsets.UTF_8)
                    Observation.Success("$truncated\n[truncated: file exceeds 100 KB, showing first 100 KB]")
                } else {
                    Observation.Success(bytes.toString(Charsets.UTF_8))
                }
            }
            "file_write" -> {
                val path = call.arguments["path"] as String
                assertAllowed(path)
                val file = File(path)
                file.parentFile?.mkdirs()
                file.writeText(call.arguments["content"] as String)
                Observation.Success("Written to $path")
            }
            "file_list" -> {
                val path = call.arguments["path"] as String
                assertAllowed(path)
                val files = File(path).listFiles()
                    ?.joinToString("\n") { it.name } ?: "(empty)"
                Observation.Success(files)
            }
            "file_delete" -> {
                val path = call.arguments["path"] as String
                assertAllowed(path)
                File(path).delete()
                Observation.Success("Deleted $path")
            }
            else -> Observation.Error("Unknown file command: ${call.name}")
        }
    }.getOrElse { Observation.Error(it.message ?: "Unknown error") }
}
