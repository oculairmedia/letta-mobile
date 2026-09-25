package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/*
 * letta-mobile-qygvv.28: the App Server's own end of a relayed turn.
 *
 * The node's engine turns the server's terminal frames into one lifecycle draft and stops: the
 * `error_message` that failed the turn, the `stop_reason` and the idle loop status after it, and
 * `turn_finished` itself never reach the relay, and neither do the queue updates that follow the
 * turn (a paused queue after an abort, then its resume). The node used to re-synthesize a bare
 * `error_message` (no run id, a cancel reading as a failure) and stop at `turn_finished`.
 *
 * [ServerFrameTap] reads the same frames as the engine, for the turn's runtime only, so the relay
 * can pass the server's real tail on: everything after the last delta the engine relayed, up to the
 * server's `turn_finished`, and afterwards the queue updates of that conversation.
 */

/** One tapped frame: its idempotency key, type, and the raw frame as received. */
internal class TapFrame(val key: String?, val type: String?, val raw: JsonObject) {
    val deltaKind: String?
        get() = ((raw["delta"] as? JsonObject)?.get("message_type") as? JsonPrimitive)?.contentOrNull

    /** A delta the App Server ends a run with (a recoverable `loop_error` is not one). */
    val isTerminalDelta: Boolean
        get() = type == STREAM_DELTA && when (deltaKind) {
            "stop_reason", "error_message" -> true
            "loop_error" -> ((raw["delta"] as? JsonObject)?.get("is_terminal") as? JsonPrimitive)?.booleanOrNull != false
            else -> false
        }

    val loopStatus: String?
        get() = ((raw["loop_status"] as? JsonObject)?.get("status") as? JsonPrimitive)?.contentOrNull

    companion object {
        const val STREAM_DELTA = "stream_delta"
        const val TURN_FINISHED = "turn_finished"
        const val UPDATE_QUEUE = "update_queue"
        const val UPDATE_LOOP_STATUS = "update_loop_status"

        fun of(received: AppServerReceivedFrame): TapFrame = TapFrame(
            key = (received.raw["idempotency_key"] as? JsonPrimitive)?.contentOrNull,
            type = (received.raw["type"] as? JsonPrimitive)?.contentOrNull,
            raw = received.raw,
        )
    }
}

/** The server frames the engine did not relay before its terminal, and the server's `turn_finished`. */
internal class ServerTurnTail(val frames: List<TapFrame>, val turnFinished: TapFrame)

internal class ServerFrameTap private constructor() {
    private val lock = Any()
    private val buffer = ArrayList<TapFrame>()
    private val size = MutableStateFlow(0)
    private val relayedKeys = HashSet<String>()
    private val relayedDeltaKeys = HashSet<String>()
    private var lastRelayedDeltaKey: String? = null
    private var turnFinishedAt = -1
    private var job: Job? = null

    /** The engine relayed [draft]: its frame is not part of the tail. */
    fun noteRelayed(draft: RuntimeEventDraft) {
        when (val payload = draft.payload) {
            is RuntimeEventPayload.RemoteStreamFrame -> {
                relayedKeys += payload.frameId
                relayedDeltaKeys += payload.frameId
                lastRelayedDeltaKey = payload.frameId
            }
            is RuntimeEventPayload.ExternalTransportFrame -> relayedKeys += payload.frameId
            else -> Unit
        }
    }

    /**
     * Waits up to [timeoutMs] for the server's `turn_finished` after the last delta the engine
     * relayed; null when it does not come (or the engine's frames cannot be placed in the tap).
     */
    suspend fun awaitTurnTail(timeoutMs: Long): ServerTurnTail? =
        withTimeoutOrNull(timeoutMs) {
            var tail: ServerTurnTail? = null
            size.first { tail = tailOrNull(); tail != null }
            tail
        }

    /**
     * After `turn_finished`: passes on the conversation's queue updates to [write] until the queue
     * is empty, a new turn shows activity, or [windowMs] passes.
     */
    suspend fun relayQueueAfterTurn(windowMs: Long, write: suspend (TapFrame) -> Unit) {
        var next = turnFinishedAt + 1
        if (next <= 0) return
        withTimeoutOrNull(windowMs) {
            while (true) {
                size.first { it > next }
                val frame = synchronized(lock) { buffer[next] }
                next += 1
                if (startsNextTurn(frame)) return@withTimeoutOrNull
                if (frame.type != TapFrame.UPDATE_QUEUE) continue
                write(frame)
                if (queueIsEmpty(frame)) return@withTimeoutOrNull
            }
        }
    }

    fun close() {
        job?.cancel()
    }

    /**
     * The tail starts after the NEWEST frame (in server order) the engine relayed a delta from: the
     * engine may relay a buffered delta late (its turn tail buffer), so the last one relayed is not
     * necessarily the newest. The last relayed delta must be in the tap, so the tap has caught up.
     */
    private fun tailOrNull(): ServerTurnTail? = synchronized(lock) {
        val last = lastRelayedDeltaKey
        if (last != null && buffer.none { it.key == last }) return null
        val start = buffer.indexOfLast { it.key != null && it.key in relayedDeltaKeys }
        val end = (start + 1 until buffer.size).firstOrNull { buffer[it].type == TapFrame.TURN_FINISHED } ?: return null
        turnFinishedAt = end
        val frames = buffer.subList(start + 1, end).filterNot { it.key != null && it.key in relayedKeys }
        ServerTurnTail(frames, buffer[end])
    }

    private fun append(frame: TapFrame) {
        val count = synchronized(lock) {
            buffer += frame
            buffer.size
        }
        size.value = count
    }

    private fun startsNextTurn(frame: TapFrame): Boolean = when (frame.type) {
        TapFrame.STREAM_DELTA -> true
        TapFrame.UPDATE_LOOP_STATUS -> frame.loopStatus != LOOP_IDLE
        else -> false
    }

    private fun queueIsEmpty(frame: TapFrame): Boolean = (frame.raw["queue"] as? JsonArray)?.isEmpty() ?: true

    companion object {
        private const val LOOP_IDLE = "WAITING_ON_INPUT"

        /** Starts reading [frames] now (before the turn's input goes out); null without a source. */
        fun open(frames: Flow<AppServerReceivedFrame>?, scope: CoroutineScope): ServerFrameTap? {
            frames ?: return null
            val tap = ServerFrameTap()
            tap.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                frames.collect { tap.append(TapFrame.of(it)) }
            }
            return tap
        }
    }
}
