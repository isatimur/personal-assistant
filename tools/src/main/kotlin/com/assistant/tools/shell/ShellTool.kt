package com.assistant.tools.shell

import com.assistant.domain.*
import com.assistant.ports.CommandSpec
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort
import java.util.concurrent.TimeUnit

class ShellTool(
    private val timeoutSeconds: Long = 30,
    private val maxOutputChars: Int = 10_000
) : ToolPort {
    override val name = "shell"
    override val description = "Executes shell commands. Commands: shell_run(command)"

    private val truncationSuffix = "\n[truncated]"

    override fun commands(): List<CommandSpec> = listOf(
        CommandSpec(
            name = "shell_run",
            description = "Execute a shell command and return its output",
            params = listOf(
                ParamSpec("command", "string", "The shell command to execute")
            )
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        if (call.name != "shell_run") return Observation.Error("Unknown shell command: ${call.name}")
        val command = call.arguments["command"] as? String ?: return Observation.Error("Missing 'command'")

        return runCatching {
            val process = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start()
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) { process.destroyForcibly(); return Observation.Error("Timed out after ${timeoutSeconds}s") }

            val output = process.inputStream.bufferedReader().readText()
            val truncated = if (output.length > maxOutputChars) {
                output.take(maxOutputChars - truncationSuffix.length) + truncationSuffix
            } else {
                output
            }
            Observation.Success(truncated)
        }.getOrElse { Observation.Error(it.message ?: "Unknown error") }
    }
}
