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
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class HeartbeatConfig(
    val enabled: Boolean = false,
    val every: String = "1h",
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
    private var job: Job? = null

    fun start() {
        if (!config.enabled) return
        val interval = parseInterval(config.every)
        job = scope.launch {
            while (true) {
                delay(interval)
                if (!chatIdFile.exists()) {
                    logger.info("Heartbeat: chatIdFile absent, skipping tick")
                    continue
                }
                try {
                    val syntheticMsg = Message(
                        sender = "heartbeat",
                        text = config.prompt,
                        channel = Channel.TELEGRAM
                    )
                    val reply = gateway.handle(syntheticMsg)
                    send(reply)
                } catch (e: Exception) {
                    logger.severe("Heartbeat tick failed: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
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
