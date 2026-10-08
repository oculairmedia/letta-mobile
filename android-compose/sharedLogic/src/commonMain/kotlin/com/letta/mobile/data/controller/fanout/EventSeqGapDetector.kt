package com.letta.mobile.data.controller.fanout

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * letta-mobile-bzvro.6 (F06): notices when an App Server frame went missing.
 *
 * letta-code stamps every runtime-scoped server event (`stream_delta`, `update_loop_status`,
 * `update_queue`, `update_device_status`, `update_subagent_state`, `turn_finished`) with an
 * `event_seq` drawn from ONE counter per listener connection (`nextListenerConnectionEventSeq` in
 * `src/websocket/listener/connection.ts`, 0.29 through 0.33), not one per runtime. On a direct
 * socket the sequence a client sees is therefore contiguous across all of its runtimes, and a jump
 * means a frame was lost in between, whichever runtime it belonged to.
 *
 * Tracking is per connection generation: a reconnect starts a new socket and a new counter, so a
 * generation change resets it. A sequence that goes backwards (a replay, or a restarted server that
 * kept the generation) is never a gap; a `1` restarts tracking.
 *
 * Only meaningful on a transport that delivers every frame of the connection. A relay that filters
 * frames per viewer (the Iroh host fanning out to phones) shows legitimate holes, so callers enable
 * detection for direct sockets only.
 */
class EventSeqGapDetector {
    private val lock = SynchronizedObject()
    private var generation: Long? = null
    private var lastSeq: Long? = null

    /** Folds one sequenced frame in; returns what it says about the stream. */
    fun observe(generation: Long, seq: Long): Observation = synchronized(lock) {
        val last = lastSeq
        when {
            this.generation != generation || last == null || seq == 1L -> {
                this.generation = generation
                lastSeq = seq
                Observation.Start
            }
            seq <= last -> Observation.Stale
            seq == last + 1 -> {
                lastSeq = seq
                Observation.InOrder
            }
            else -> {
                lastSeq = seq
                Observation.Gap(expected = last + 1, received = seq)
            }
        }
    }

    /** Forget the stream (the connection closed). */
    fun reset() = synchronized(lock) {
        generation = null
        lastSeq = null
    }

    sealed interface Observation {
        /** First frame of a connection (or the server's counter restarted). */
        data object Start : Observation

        /** The next frame, as expected. */
        data object InOrder : Observation

        /** At or below the last sequence seen: a replay or duplicate, never a gap. */
        data object Stale : Observation

        /** Frames [expected] until [received] (exclusive) never arrived. */
        data class Gap(val expected: Long, val received: Long) : Observation {
            val missing: Long get() = received - expected
        }
    }
}

/** The frame's connection-wide `event_seq`, when it carries one. */
val AppServerInboundFrame.eventSeqOrNull: Long?
    get() = when (this) {
        is AppServerInboundFrame.StreamDelta -> eventSeq
        is AppServerInboundFrame.TurnFinished -> eventSeq
        is AppServerInboundFrame.UpdateLoopStatus -> eventSeq
        is AppServerInboundFrame.UpdateDeviceStatus -> eventSeq
        is AppServerInboundFrame.UpdateQueue -> eventSeq
        is AppServerInboundFrame.UpdateSubagentState -> eventSeq
        else -> null
    }
