package com.letta.mobile.data.controller.fanout

import kotlinx.coroutines.CancellationException

/**
 * letta-mobile-qygvv.16: the cause a turn subscriber's channel is closed with when the router
 * detaches, i.e. when the transport session that carried the turn is gone (connection closed,
 * redial, wrapper restart). A turn collector that sees it knows the session was lost, not that the
 * turn was cancelled, and ends the turn with a terminal of its own.
 */
class RuntimeStreamDetachedCancellation(
    message: String = "AppServerRuntimeEventRouter detached",
) : CancellationException(message)

/** Whether this throwable, or one of its causes, is a [RuntimeStreamDetachedCancellation]. */
fun Throwable.isRuntimeStreamDetached(): Boolean =
    generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is RuntimeStreamDetachedCancellation }

private const val MAX_CAUSE_DEPTH = 8
