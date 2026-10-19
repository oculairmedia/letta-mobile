package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Delivers one socket's inbound frames to its collectors.
 *
 * ## Why one ordered sequence
 *
 * The App Server writes every frame of a connection on ONE socket, in `event_seq` order. Until
 *  the transport split them into a control lane and a stream lane, drained each lane with
 * its own job, and `mergedFrames` merged the lanes back with two unordered collectors. Frames that
 * arrived in order were therefore delivered out of order: a `turn_finished` (control lane) could
 * overtake the last `stream_delta` (stream lane), the turn engine completed and unsubscribed, and
 * the trailing deltas were dropped (a truncated reply until the next timeline resync). On a direct
 * socket the `event_seq` gap detector then read the self-inflicted reorder as a lost frame and
 * forced a resync.
 *
 * [inboundFrames] is now the primary path: every frame, in socket arrival order, through one
 * queue and one delivery job. The runtime router and the fanout consume it, so a turn sees its
 * deltas before its terminal and `StreamIntegrityMonitor` sees `event_seq` in order.
 *
 * ## What the lane split was for, and how it is kept
 *
 * The split existed so a control frame (an approval, a request response) was never stuck behind
 * a flood of deltas held up by a slow stream collector. That goal now holds by construction on
 * the ordered path: its consumers never wait on anyone. The router's per-frame work is
 * `StreamIntegrityMonitor.observe` plus `RuntimeEventFanout.route`, which since offers
 * with `trySend` and detaches a full subscriber instead of suspending. So a control frame behind
 * N deltas waits only for the fanout to route those N deltas, never for any subscriber to
 * consume them.
 *
 * [controlFrames] and [streamFrames] are kept as derived views of the same arrival sequence:
 * each lane gets the frames of its type, in arrival order, from its own queue and job. They are
 * the non-blocking side path. `AppServerRequestRegistry` correlates responses on [controlFrames],
 * so a stalled [streamFrames] collector cannot delay a request response, and neither view can
 * stall [inboundFrames] (the read loop only `trySend`s into the lane queues). Do not merge the
 * two views to rebuild the full sequence: that loses the ordering. Use [inboundFrames].
 *
 * ## Bounds
 *
 * Every queue holds at most [DELIVERY_QUEUE_CAPACITY] frames. A full queue throws
 * [AppServerDeliveryOverflowException] from [Session.accept], which fails the generation. Silently
 * evicting frames would corrupt client state, and an unbounded queue trades a clear, recoverable
 * failure for unbounded memory under a stuck consumer.
 */
internal class AppServerInboundDelivery {
    private val orderedFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER_CAPACITY)
    private val controlFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER_CAPACITY)
    private val streamFlow = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = FRAME_BUFFER_CAPACITY)

    /** Every frame, in the order the socket delivered it. */
    val inboundFrames: Flow<AppServerReceivedFrame> = orderedFlow.asSharedFlow()

    /** The [AppServerChannel.Control] frames of [inboundFrames], in arrival order. */
    val controlFrames: Flow<AppServerReceivedFrame> = controlFlow.asSharedFlow()

    /** The [AppServerChannel.Stream] frames of [inboundFrames], in arrival order. */
    val streamFrames: Flow<AppServerReceivedFrame> = streamFlow.asSharedFlow()

    /** Starts delivery for one socket session; its jobs are children of [scope]. */
    fun open(scope: CoroutineScope): Session = Session(scope)

    inner class Session internal constructor(scope: CoroutineScope) {
        private val ordered = Channel<AppServerReceivedFrame>(DELIVERY_QUEUE_CAPACITY)
        private val control = Channel<AppServerReceivedFrame>(DELIVERY_QUEUE_CAPACITY)
        private val stream = Channel<AppServerReceivedFrame>(DELIVERY_QUEUE_CAPACITY)

        /** The delivery jobs; they finish once [closeQueues] ran and every accepted frame is emitted. */
        val jobs: List<Job> = listOf(
            scope.drain(ordered, orderedFlow),
            scope.drain(control, controlFlow),
            scope.drain(stream, streamFlow),
        )

        /**
         * Hands one received frame to delivery. Never suspends.
         *
         * @throws AppServerDeliveryOverflowException when a queue is full.
         */
        fun accept(frame: AppServerReceivedFrame) {
            ordered.enqueueOrFail(frame, "inbound")
            when (frame.channel) {
                AppServerChannel.Control -> control.enqueueOrFail(frame, "control")
                AppServerChannel.Stream -> stream.enqueueOrFail(frame, "stream")
            }
        }

        /** No more frames; the jobs drain what was accepted and finish. */
        fun closeQueues() {
            ordered.close()
            control.close()
            stream.close()
        }
    }

    private fun CoroutineScope.drain(
        queue: Channel<AppServerReceivedFrame>,
        sink: MutableSharedFlow<AppServerReceivedFrame>,
    ): Job = launch {
        for (frame in queue) {
            sink.emit(frame)
        }
    }

    private fun Channel<AppServerReceivedFrame>.enqueueOrFail(frame: AppServerReceivedFrame, queueName: String) {
        if (!trySend(frame).isSuccess) {
            throw AppServerDeliveryOverflowException(queueName, DELIVERY_QUEUE_CAPACITY)
        }
    }

    internal companion object {
        const val FRAME_BUFFER_CAPACITY = 64

        /**
         * 256 was an arbitrary round number, not derived from real traffic.
         * Production wrapper logs showed it exceeded 4 times in 28h, each time tearing down the
         * whole shared session. Raising it to 1024 made real bursts hit the ceiling less often.
         *
         *  made the router never wait on a subscriber, and made the router read one
         * ordered queue, so this queue now drains as fast as the fanout routes. It can only fill if
         * a direct collector of [inboundFrames] or of a lane view stops collecting, or if this
         * process gets no CPU for over 1024 frames. The bound stays: failing the generation is a
         * clear, recoverable error; an unbounded queue under a stuck collector is a memory leak.
         */
        const val DELIVERY_QUEUE_CAPACITY = 1_024
    }
}

internal class AppServerDeliveryOverflowException(
    queueName: String,
    capacity: Int,
) : IllegalStateException(
    "App Server $queueName delivery queue exceeded its bounded capacity of $capacity frames",
)
