package com.letta.mobile.data.timeline

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val timelineIoDispatcher: CoroutineDispatcher = Dispatchers.Default

actual fun isTimelineNetworkFailure(t: Throwable): Boolean {
    val message = generateSequence(t) { it.cause }
        .mapNotNull(Throwable::message)
        .joinToString(" ")
        .lowercase()
    return NETWORK_FAILURE_MARKERS.any(message::contains)
}

private val NETWORK_FAILURE_MARKERS = listOf(
    "connection",
    "disconnected",
    "network",
    "timed out",
    "timeout",
    "transport",
    "websocket",
)
