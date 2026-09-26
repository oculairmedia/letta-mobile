package com.letta.mobile.data.canvas

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * The relay's answers to one host publish (letta-mobile-qygvv.21): one Ack or Rejected per sent op,
 * awaited rather than drained. Reading only what had already arrived could miss a rejection that
 * came a moment later and report the publish as done.
 *
 * Published only when every op is acknowledged; any rejection, a refused connection or silence past
 * the timeout is a refusal, so an agent is never told its write landed when it did not.
 */
internal class HostCanvasAcks(sent: List<CanvasOp>) {
    private val pending = sent.mapTo(mutableSetOf()) { it.opId }
    private val rejected = mutableListOf<String>()
    private var head = 0L
    private var refused: String? = null

    suspend fun await(replies: ReceiveChannel<CanvasRelayMessage>, timeout: Duration): HostCanvasPublish {
        withTimeoutOrNull(timeout) {
            while (pending.isNotEmpty() && refused == null) take(replies.receiveCatching().getOrNull())
        }
        return outcome(timeout)
    }

    private fun take(reply: CanvasRelayMessage?) {
        when (reply) {
            null -> refused = "the relay connection closed"
            is CanvasRelayMessage.Ack -> if (pending.remove(reply.opId)) head = maxOf(head, reply.cursor)
            is CanvasRelayMessage.Rejected -> if (pending.remove(reply.opId)) rejected += reply.reason
            is CanvasRelayMessage.Refused -> refused = reply.reason
            else -> Unit
        }
    }

    private fun outcome(timeout: Duration): HostCanvasPublish {
        val refusal = refused
        return when {
            rejected.isNotEmpty() -> HostCanvasPublish.Denied("Rejected by the host: ${rejected.joinToString()}")
            refusal != null -> HostCanvasPublish.Denied("The host refused the write: $refusal")
            pending.isNotEmpty() ->
                HostCanvasPublish.Denied("The host did not acknowledge ${pending.size} op(s) within $timeout; they may not be on the canvas")
            else -> HostCanvasPublish.Published(head)
        }
    }
}
