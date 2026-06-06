package com.letta.mobile.data.timeline

import java.time.Instant
import java.util.UUID

actual typealias TimelineInstant = Instant

actual fun timelineNow(): TimelineInstant = Instant.now()

actual fun parseTimelineInstant(value: String): TimelineInstant = Instant.parse(value)

actual fun parseTimelineInstantOrNull(value: String): TimelineInstant? =
    runCatching { Instant.parse(value) }.getOrNull()

actual fun compareTimelineInstants(left: TimelineInstant, right: TimelineInstant): Int =
    left.compareTo(right)

actual fun newTimelineClientId(): String = UUID.randomUUID().toString()
