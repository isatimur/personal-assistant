package com.assistant.heartbeat

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object CronScheduler {
    private val parser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

    /**
     * Returns the duration until the next execution of [expression] (5-field UNIX cron).
     * Throws [IllegalArgumentException] for invalid expressions.
     */
    fun nextCronDelay(expression: String, timezone: ZoneId = ZoneId.systemDefault()): Duration {
        val cron = try {
            parser.parse(expression)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid cron expression: '$expression'", e)
        }
        val executionTime = ExecutionTime.forCron(cron)
        val now = ZonedDateTime.now(timezone)
        val next = executionTime.nextExecution(now)
            .orElseThrow { IllegalArgumentException("No next execution for cron: '$expression'") }
        val millis = java.time.Duration.between(now, next).toMillis()
        return millis.coerceAtLeast(0L).milliseconds
    }
}
