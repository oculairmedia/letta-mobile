package com.letta.mobile.data.stream

import com.letta.mobile.data.model.LettaMessage

sealed interface SseFrame {
    data class Message(val message: LettaMessage) : SseFrame
    data class RawEvent(
        val event: String? = null,
        val data: String,
        val id: String? = null,
    ) : SseFrame
    data object Heartbeat : SseFrame
    data object Done : SseFrame
}
