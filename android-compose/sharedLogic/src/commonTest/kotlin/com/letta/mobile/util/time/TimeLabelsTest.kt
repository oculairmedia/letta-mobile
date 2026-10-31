package com.letta.mobile.util.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class TimeLabelsTest {
    private val now = Instant.parse("2026-10-07T12:00:00Z")

    /** The bucket edges the sidebar, drawer and Conversations rows rely on (moved from ShellRelativeTime). */
    @Test
    fun ageUsesOneCompactUnit() {
        assertEquals("now", TimeLabels.age(now - 30.seconds, now))
        assertEquals("4m", TimeLabels.age(now - 4.minutes, now))
        assertEquals("13h", TimeLabels.age(now - 13.hours, now))
        assertEquals("2d", TimeLabels.age(now - 2.days, now))
        assertEquals("3w", TimeLabels.age(now - 21.days, now))
        assertEquals("2mo", TimeLabels.age(now - 61.days, now))
        assertEquals("now", TimeLabels.age(now + 5.minutes, now), "a time in the future reads now")
    }

    @Test
    fun ageLabelParsesIsoAndPassesOtherTextThrough() {
        assertEquals("1h", TimeLabels.ageLabel("2026-10-07T11:00:00Z", now))
        assertEquals("Remote", TimeLabels.ageLabel("Remote", now))
    }

    @Test
    fun wholeSecondsUnderAMinuteThenTwoUnits() {
        assertEquals("0ms", TimeLabels.duration(0))
        assertEquals("340ms", TimeLabels.duration(340))
        assertEquals("12s", TimeLabels.duration(12_900), "whole seconds truncate")
        assertEquals("1m 12s", TimeLabels.duration(72_000))
        assertEquals("1m 28s", TimeLabels.duration(88_000), "the subagent roster's elapsed label")
        assertEquals("1h 5m", TimeLabels.duration(3_900_000))
        assertEquals("0ms", TimeLabels.duration(-5), "a negative duration reads as zero")
    }

    @Test
    fun tenthsBelowAMinuteRoundBeforeChoosingTheUnit() {
        assertEquals("850ms", TimeLabels.duration(850, tenths = true))
        assertEquals("4.2s", TimeLabels.duration(4_210, tenths = true))
        assertEquals("2.5s", TimeLabels.duration(2_450, tenths = true))
        assertEquals("1m 0s", TimeLabels.duration(59_950, tenths = true), "never 60.0s")
        assertEquals("3m 12s", TimeLabels.duration(192_000, tenths = true))
        assertEquals("2h 0m", TimeLabels.duration(7_200_400, tenths = true))
    }
}
