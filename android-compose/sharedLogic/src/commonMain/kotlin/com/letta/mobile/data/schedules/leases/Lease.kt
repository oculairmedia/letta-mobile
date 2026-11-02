package com.letta.mobile.data.schedules.leases

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val minimumVisibleLeaseDuration = 1.minutes

/** Final state reported by a lease producer; future values remain unknown. */
enum class LeaseState {
    /** Work is still in progress. */
    ACTIVE,
    /** Work completed before closure. */
    DONE,
    /** The lease expired. */
    EXPIRED,
    /** Work recovered after an interruption. */
    RECOVERED,
    /** The producer sent an unknown state. */
    UNKNOWN,
}

/** A read-only wrapper lease, independent of its producer. */
data class Lease(
    /** Producer-supplied lease identity. */
    val id: String,
    /** Agent that owns this lease. */
    val owner: String,
    /** Human-readable work label. */
    val label: String,
    /** Start instant. */
    val startedAt: Instant,
    /** Deadline instant. */
    val dueAt: Instant,
    /** Last reported state. */
    val state: LeaseState,
    /** Optional identifier of the run that created this lease. */
    val runId: String? = null,
    /** Optional producer detail. */
    val detail: String? = null,
)

/** The visible wall-clock portion of a lease on one local calendar day. */
data class LeaseSpan(
    /** The underlying lease. */
    val lease: Lease,
    /** Start in wall-clock minutes since local midnight. */
    val startMinute: Float,
    /** Visible duration in grid minutes. */
    val durationMinutes: Float,
)

/**
 * Clips a lease to a local day. The `minimumVisibleLeaseDuration` rule treats a due time at
 * or before its start as one minute after the start, preventing negative or invisible grid blocks.
 */
fun leaseSpan(lease: Lease, date: LocalDate, zone: TimeZone): LeaseSpan? {
    val dayStart = date.atStartOfDayIn(zone)
    val dayEnd = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)
    val effectiveEnd = if (lease.dueAt <= lease.startedAt) lease.startedAt + minimumVisibleLeaseDuration else lease.dueAt
    if (lease.startedAt >= dayEnd || effectiveEnd <= dayStart) return null
    val start = if (lease.startedAt <= dayStart) 0f else localMinute(lease.startedAt, zone)
    val end = if (effectiveEnd >= dayEnd) 1440f else localMinute(effectiveEnd, zone)
    return LeaseSpan(lease, start, (end - start).coerceAtLeast(0f))
}

private fun localMinute(instant: Instant, zone: TimeZone): Float {
    val local = instant.toLocalDateTime(zone)
    return local.hour * 60f + local.minute + local.second / 60f + local.nanosecond / 60_000_000_000f
}
