package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** How many live connections a broadcast was addressed to, and how many accepted the write. */
data class BroadcastResult(val recipients: Int, val delivered: Int)

/**
 * Writes [frame] to every live connection [isRecipient] accepts.
 *
 * Kept beside [ConnectionRegistry] rather than inside it: the registry owns connection identity and
 * per-conversation viewers, while this is a device-wide fan-out built on its snapshot. Writes run
 * concurrently and outside the registry lock, so one slow peer cannot hold up the others.
 */
suspend fun ConnectionRegistry.broadcast(frame: String, isRecipient: (ViewerHandle) -> Boolean): BroadcastResult {
    val recipients = connections().filter(isRecipient)
    val delivered = coroutineScope { recipients.map { async { it.writeFrame(frame) } }.awaitAll() }
    return BroadcastResult(recipients = recipients.size, delivered = delivered.count { it })
}
