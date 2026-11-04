package com.letta.mobile.data.schedules.leases

import com.letta.mobile.data.schedules.CalendarQuery
import com.letta.mobile.data.schedules.ScheduleCalendar
import com.letta.mobile.data.schedules.ScheduleClock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class LeaseCommonTest {
    @Test
    fun fallBackLeaseUsesElapsedDurationAcrossRepeatedHour() {
        val lease = Lease(
            "dst", "owner", "label", Instant.parse("2026-11-01T05:50:00Z"),
            Instant.parse("2026-11-01T06:10:00Z"), LeaseState.ACTIVE,
        )
        val span = leaseSpan(lease, LocalDate(2026, 11, 1), TimeZone.of("America/New_York"))!!
        assertEquals(110f, span.startMinute)
        assertEquals(20f, span.durationMinutes)
    }

    @Test
    fun midnightSplitAndMinimumVisibleDurationRuleArePortable() {
        val lease = Lease(
            "lease", "owner", "label", Instant.parse("2026-11-02T23:30:00Z"),
            Instant.parse("2026-11-03T01:15:00Z"), LeaseState.ACTIVE,
        )
        val firstDate = LocalDate(2026, 11, 2)
        val secondDate = LocalDate(2026, 11, 3)
        val days = ScheduleCalendar.days(
            defs = emptyList(),
            dates = listOf(firstDate, secondDate),
            query = CalendarQuery(ScheduleClock(Instant.parse("2026-11-02T12:00:00Z"), TimeZone.UTC), leases = listOf(lease)),
        )
        val first = days[0].leases.single()
        val second = days[1].leases.single()
        assertEquals(30f, first.durationMinutes)
        assertEquals(75f, second.durationMinutes)
        assertNull(leaseSpan(lease, LocalDate(2026, 11, 4), TimeZone.UTC))
        assertEquals(1f, leaseSpan(lease.copy(dueAt = lease.startedAt), firstDate, TimeZone.UTC)!!.durationMinutes)
        assertEquals(1f, leaseSpan(lease.copy(dueAt = Instant.parse("2026-11-01T00:00:00Z")), firstDate, TimeZone.UTC)!!.durationMinutes)
    }
}
