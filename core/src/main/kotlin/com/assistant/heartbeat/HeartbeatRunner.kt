package com.assistant.heartbeat

import com.assistant.domain.*
import com.assistant.gateway.Gateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class HeartbeatAgent(
    val name: String,
    val cron: String,
    val prompt: String,
    val timezone: String = ZoneId.systemDefault().id
)

data class HeartbeatConfig(
    val enabled: Boolean = false,
    val every: String = "1h",
    val time: String? = null,
    val cron: String? = null,
    val agents: List<HeartbeatAgent> = emptyList(),
    val prompt: String = "Check if there's anything proactive you should do."
)

class HeartbeatRunner(
    private val config: HeartbeatConfig,
    private val gateway: Gateway,
    private val send: suspend (String) -> Unit,
    private val chatIdFile: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val logger = Logger.getLogger(HeartbeatRunner::class.java.name)
    private val jobs: MutableList<Job> = mutableListOf()

    fun start() {
        if (!config.enabled) return
        if (config.agents.isNotEmpty()) {
            // Multi-agent mode: launch one coroutine per agent
            config.agents.forEach { agent ->
                val tz = runCatching { ZoneId.of(agent.timezone) }.getOrDefault(ZoneId.systemDefault())
                jobs += scope.launch {
                    while (true) {
                        val waitDuration = CronScheduler.nextCronDelay(agent.cron, tz)
                        delay(waitDuration)
                        fireHeartbeat(agent.prompt)
                    }
                }
            }
        } else if (config.cron != null) {
            jobs += scope.launch {
                while (true) {
                    val waitDuration = CronScheduler.nextCronDelay(config.cron)
                    delay(waitDuration)
                    fireHeartbeat(config.prompt)
                }
            }
        } else if (config.time != null) {
            jobs += scope.launch {
                while (true) {
                    val waitDuration = delayUntilTime(config.time)
                    delay(waitDuration)
                    fireHeartbeat(config.prompt)
                }
            }
        } else {
            val interval = parseInterval(config.every)
            jobs += scope.launch {
                while (true) {
                    delay(interval)
                    fireHeartbeat(config.prompt)
                }
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    internal suspend fun fireHeartbeat(prompt: String = config.prompt) {
        if (!chatIdFile.exists()) {
            logger.info("Heartbeat: chatIdFile absent, skipping tick")
            return
        }
        try {
            val syntheticMsg = Message(
                sender = "heartbeat",
                text = prompt,
                channel = Channel.TELEGRAM
            )
            val reply = gateway.handle(syntheticMsg)
            send(reply)
        } catch (e: Exception) {
            logger.severe("Heartbeat tick failed: ${e.message}")
        }
    }

    internal fun delayUntilTime(timeStr: String, now: LocalDateTime = LocalDateTime.now()): Duration {
        val parts = timeStr.split(":")
        val hour = parts[0].toInt()
        val minute = parts.getOrNull(1)?.toInt() ?: 0
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val millis = java.time.Duration.between(now, target).toMillis()
        return millis.milliseconds
    }

    internal fun parseInterval(s: String): Duration {
        val match = Regex("^(\\d+)([smhd])$").find(s)
            ?: throw IllegalArgumentException("Invalid heartbeat interval: $s. Use format like 30m, 1h, 6h, 1d")
        val value = match.groupValues[1].toLong()
        return when (match.groupValues[2]) {
            "s" -> value.seconds
            "m" -> value.minutes
            "h" -> value.hours
            "d" -> value.days
            else -> throw IllegalArgumentException("Unknown time unit in: $s")
        }
    }
}
