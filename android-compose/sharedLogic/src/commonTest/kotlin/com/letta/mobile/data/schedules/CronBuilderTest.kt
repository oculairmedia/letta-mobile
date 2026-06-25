package com.letta.mobile.data.schedules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CronBuilderTest {

    @Test
    fun `buildRecurring creates valid cron expression with all parameters`() {
        val cron = CronBuilder.buildRecurring(
            minute = 30,
            hour = 14,
            dayOfMonth = 15,
            month = 6,
            dayOfWeek = 3
        )
        assertEquals("30 14 15 6 3", cron)
    }

    @Test
    fun `buildRecurring handles defaults`() {
        val cron = CronBuilder.buildRecurring()
        assertEquals("* * * * *", cron)
    }

    @Test
    fun `buildRecurring handles partial parameters`() {
        val cron = CronBuilder.buildRecurring(minute = 0, hour = 12)
        assertEquals("0 12 * * *", cron)
    }

    @Test
    fun `buildRecurring throws on invalid minute`() {
        assertFailsWith<IllegalArgumentException> {
            CronBuilder.buildRecurring(minute = 60)
        }
        assertFailsWith<IllegalArgumentException> {
            CronBuilder.buildRecurring(minute = -1)
        }
    }

    @Test
    fun `buildRecurring throws on invalid hour`() {
        assertFailsWith<IllegalArgumentException> {
            CronBuilder.buildRecurring(hour = 24)
        }
    }

    @Test
    fun `buildRecurring throws on invalid dayOfMonth`() {
        assertFailsWith<IllegalArgumentException> {
            CronBuilder.buildRecurring(dayOfMonth = 32)
        }
        assertFailsWith<IllegalArgumentException> {
            CronBuilder.buildRecurring(dayOfMonth = 0)
        }
    }

    @Test
    fun `buildRecurring throws on invalid month`() {
        assertFailsWith<IllegalArgumentException> {
            CronBuilder.buildRecurring(month = 13)
        }
        assertFailsWith<IllegalArgumentException> {
            CronBuilder.buildRecurring(month = 0)
        }
    }

    @Test
    fun `buildRecurring throws on invalid dayOfWeek`() {
        assertFailsWith<IllegalArgumentException> {
            CronBuilder.buildRecurring(dayOfWeek = 8)
        }
        assertFailsWith<IllegalArgumentException> {
            CronBuilder.buildRecurring(dayOfWeek = -1)
        }
    }

    @Test
    fun `buildOneOff creates precise expression from parameters`() {
        val cron = CronBuilder.buildOneOff(
            minute = 15,
            hour = 9,
            dayOfMonth = 10,
            month = 5
        )
        assertEquals("15 9 10 5 *", cron)
    }

    @Test
    fun `buildOneOff handles edge times correctly`() {
        // Midnight on Jan 1st
        assertEquals("0 0 1 1 *", CronBuilder.buildOneOff(0, 0, 1, 1))

        // Last minute of the year
        assertEquals("59 23 31 12 *", CronBuilder.buildOneOff(59, 23, 31, 12))
    }

    @Test
    fun `buildOneOff throws on invalid parameters`() {
        assertFailsWith<IllegalArgumentException> {
            CronBuilder.buildOneOff(minute = 60, hour = 9, dayOfMonth = 10, month = 5)
        }
        assertFailsWith<IllegalArgumentException> {
            CronBuilder.buildOneOff(minute = 15, hour = 24, dayOfMonth = 10, month = 5)
        }
    }
}
