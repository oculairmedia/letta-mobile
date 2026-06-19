package com.letta.mobile.data.transport

/**
 * Raw transport frame plus delivery metadata.
 */
data class TransportFrameEvent(
    val frame: ServerFrame,
    val isReplay: Boolean = false,
)
