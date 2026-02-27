package com.example

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import com.assistant.ports.CommandSpec
import com.assistant.ports.ToolPort

class SysInfoTool : ToolPort {
    override val name = "sysinfo"
    override val description = "Returns OS, CPU, and memory details for the current system"

    override fun commands() = listOf(
        CommandSpec(
            name = "sysinfo_get",
            description = "Get current system information (OS, CPUs, heap memory)",
            params = emptyList()
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        if (call.name != "sysinfo_get") return Observation.Error("Unknown command: ${call.name}")
        val runtime = Runtime.getRuntime()
        val totalMb = runtime.totalMemory() / 1_048_576
        val freeMb = runtime.freeMemory() / 1_048_576
        val usedMb = totalMb - freeMb
        val info = buildString {
            appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            appendLine("JVM: ${System.getProperty("java.version")}")
            appendLine("CPUs: ${runtime.availableProcessors()}")
            append("Memory: ${usedMb}MB used / ${totalMb}MB total")
        }
        return Observation.Success(info)
    }
}
