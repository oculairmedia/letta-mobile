package com.letta.mobile.data.timeline

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

actual typealias TimelineInstant = Instant

actual fun timelineNow(): TimelineInstant = Clock.System.now()

actual fun timelineCurrentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

actual fun parseTimelineInstant(value: String): TimelineInstant = Instant.parse(value)

actual fun parseTimelineInstantOrNull(value: String): TimelineInstant? =
    runCatching { Instant.parse(value) }.getOrNull()

actual fun compareTimelineInstants(left: TimelineInstant, right: TimelineInstant): Int =
    left.compareTo(right)

actual fun timelineInstantDurationMillis(start: TimelineInstant, end: TimelineInstant): Long =
    (end - start).inWholeMilliseconds

actual fun newTimelineClientId(): String {
    val random = Random.Default
    return listOf(4, 2, 2, 2, 6)
        .joinToString("-") { group ->
            buildString(group * 2) {
                repeat(group) {
                    append(random.nextInt(0, 256).toString(16).padStart(2, '0'))
                }
            }
        }
}
