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
        val rt = Runtime.getRuntime()
        val totalMb = rt.totalMemory() / 1_048_576
        val freeMb = rt.freeMemory() / 1_048_576
        val usedMb = totalMb - freeMb
        return Observation.Success("""
            OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})
            JVM: ${System.getProperty("java.version")}
            CPUs: ${rt.availableProcessors()}
            Memory: ${usedMb}MB used / ${totalMb}MB total
        """.trimIndent())
    }
}
