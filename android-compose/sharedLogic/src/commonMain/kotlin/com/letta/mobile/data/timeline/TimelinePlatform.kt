package com.letta.mobile.data.timeline

expect class TimelineInstant

expect fun timelineNow(): TimelineInstant

expect fun timelineCurrentTimeMillis(): Long

expect fun parseTimelineInstant(value: String): TimelineInstant

expect fun parseTimelineInstantOrNull(value: String): TimelineInstant?

expect fun compareTimelineInstants(left: TimelineInstant, right: TimelineInstant): Int

expect fun newTimelineClientId(): String
