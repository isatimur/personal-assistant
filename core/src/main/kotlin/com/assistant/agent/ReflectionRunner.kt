package com.assistant.agent

import com.assistant.heartbeat.CronScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.logging.Logger

class ReflectionRunner(
    private val config: ReflectionServiceConfig,
    private val service: ReflectionService,
    private val userId: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val logger = Logger.getLogger("ReflectionRunner")

    fun start() {
        if (!config.enabled) return
        scope.launch {
            while (isActive) {
                val delay = CronScheduler.nextCronDelay(config.cron)
                kotlinx.coroutines.delay(delay)
                try {
                    service.reflect(userId)
                } catch (e: Exception) {
                    logger.warning("Reflection failed: ${e.message}")
                }
            }
        }
    }
}
