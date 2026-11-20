package com.letta.mobile.data.media

/** A player clock reading of [positionMs]: "0:42", "12:05", "75:00" (minutes never roll into hours). */
fun formatMediaClock(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0L) / MILLIS_PER_SECOND
    return "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
