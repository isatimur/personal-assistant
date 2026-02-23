package com.assistant.heartbeat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

class CronSchedulerTest {

    @Test
    fun `every-minute cron returns positive duration`() {
        val delay = CronScheduler.nextCronDelay("* * * * *")
        assertTrue(delay.isPositive(), "Delay should be positive, got $delay")
    }

    @Test
    fun `daily cron at 6am returns delay less than 24 hours`() {
        val delay = CronScheduler.nextCronDelay("0 6 * * *")
        assertTrue(delay < 24.hours, "Daily cron delay should be < 24h, got $delay")
    }

    @Test
    fun `weekday cron returns positive duration`() {
        val delay = CronScheduler.nextCronDelay("0 9 * * 1-5")
        assertTrue(delay.isPositive(), "Weekday cron delay should be positive, got $delay")
    }

    @Test
    fun `invalid expression throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            CronScheduler.nextCronDelay("not a cron")
        }
    }

    @Test
    fun `too many fields throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            CronScheduler.nextCronDelay("0 6 * * * *")  // 6-field, not UNIX 5-field
        }
    }
}
