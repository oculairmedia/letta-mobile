package com.letta.mobile.data.controller.fanout

import kotlinx.coroutines.CancellationException

/**
 * The cause a turn subscriber's channel is closed with when the router
 * detaches, i.e. when the transport session that carried the turn is gone (connection closed,
 * redial, wrapper restart). A turn collector that sees it knows the session was lost, not that the
 * turn was cancelled, and ends the turn with a terminal of its own.
 */
open class RuntimeStreamDetachedCancellation(
    message: String = "AppServerRuntimeEventRouter detached",
) : CancellationException(message)

/**
 * The cause ONE subscriber's channel is closed with when it fell
 * [capacity] frames behind and [RuntimeEventFanout.route] could not hand it [droppedFrameType]
 * without blocking. The fanout detaches that subscriber alone (every other subscriber, and the
 * router itself, keep going), so to its collector it is a stream detach like
 * [RuntimeStreamDetachedCancellation]: the server-side run is untouched, but this client lost
 * frames of it and must end its turn / resync rather than keep going on a gapped stream.
 */
class RuntimeSubscriberOverflowCancellation(
    val subscriberId: String,
    val agentId: String,
    val conversationId: String,
    val droppedFrameType: String?,
    val capacity: Int,
) : RuntimeStreamDetachedCancellation(
    "Fanout subscriber $subscriberId for $agentId/$conversationId fell $capacity frames behind; " +
        "detached on ${droppedFrameType ?: "unknown"} frame",
)

/** Whether this throwable, or one of its causes, is a [RuntimeSubscriberOverflowCancellation]. */
fun Throwable.isRuntimeSubscriberOverflow(): Boolean =
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is RuntimeSubscriberOverflowCancellation }

/** Whether this throwable, or one of its causes, is a [RuntimeStreamDetachedCancellation]. */
fun Throwable.isRuntimeStreamDetached(): Boolean =
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is RuntimeStreamDetachedCancellation }

private const val MAX_CAUSE_DEPTH = 8
