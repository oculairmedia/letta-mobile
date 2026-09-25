package com.letta.mobile.data.canvas

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One session's way of taking a relayed op: the op, and the actor the host vouches for on it. */
internal typealias CanvasRelayApplier = suspend (op: CanvasOp, vouchedActor: String?) -> Unit

/** A relay topic, as the gate tracks it. */
internal data class CanvasRelayTopic(val name: String)

/** A canvas of a topic, with the sessions open on it here. */
internal class CanvasRelayTarget(val canvasId: CanvasId, val appliers: List<CanvasRelayApplier>)

/**
 * Where a relayed op goes in this app (letta-mobile-qygvv.23): to the sessions open on its canvas,
 * or, with none open, straight into the stored canvas ([CanvasClosedBoardApplier]). An op nothing
 * took leaves a gap on its topic, and the relay client must not move its cursor past a gap: the
 * next join asks the host for everything after the last op this app really has.
 *
 * Applying and a session attaching are one step apart, so an op is either handed to the new
 * session or already in the store the session is about to read; and a session that attaches just
 * after a closed-board apply is handed that op again, which it skips as a duplicate and takes the
 * stored canvas instead of the copy it loaded a moment earlier.
 */
internal class CanvasRelayApplyGate(private val closedBoard: CanvasClosedBoardApplier?) {
    private val mutex = Mutex()
    private val appliedWhileClosed = mutableMapOf<CanvasId, Pair<CanvasOp, String?>>()
    /** Topics with an op nothing took, and whether a join has since been sent to replay it. */
    private val gaps = mutableMapOf<CanvasRelayTopic, Boolean>()

    /** [op] applied to [targets]; false when nothing here took it. */
    suspend fun apply(targets: suspend () -> List<CanvasRelayTarget>, op: CanvasOp, vouchedActor: String?): Boolean =
        mutex.withLock {
            val canvases = targets()
            val open = canvases.flatMap { it.appliers }
            if (open.isNotEmpty()) {
                open.forEach { it(op, vouchedActor) }
                return@withLock true
            }
            canvases.count { applyClosed(it.canvasId, op, vouchedActor) } > 0
        }

    private suspend fun applyClosed(canvasId: CanvasId, op: CanvasOp, vouchedActor: String?): Boolean {
        val applied = closedBoard?.apply(canvasId, op, vouchedActor) == true
        if (applied) appliedWhileClosed[canvasId] = op to vouchedActor
        return applied
    }

    /** Runs [register] between applies; returns the last op [canvasId] took while closed, to hand to it. */
    suspend fun attach(canvasId: CanvasId, register: suspend () -> Unit): Pair<CanvasOp, String?>? = mutex.withLock {
        register()
        appliedWhileClosed.remove(canvasId)
    }

    /** Nothing took an op of [topic]: its cursor stays where it is until a join has replayed it. */
    suspend fun markGap(topic: CanvasRelayTopic) = mutex.withLock { gaps[topic] = false }

    suspend fun hasGap(topic: CanvasRelayTopic): Boolean = mutex.withLock { topic in gaps }

    /** Whether [topic]'s cursor may move to the op just applied: not while an earlier op is missing. */
    suspend fun mayAdvance(topic: CanvasRelayTopic): Boolean = mutex.withLock { topic !in gaps }

    /** A join of [topic] was sent from the stored cursor: its catch-up replays the gap. */
    suspend fun joining(topic: CanvasRelayTopic) = mutex.withLock { if (topic in gaps) gaps[topic] = true }

    /**
     * [topic]'s catch-up is complete: true when its cursor may move to the end of it, the gap
     * (if any) closed by a join sent after it and every op since taken.
     */
    suspend fun caughtUp(topic: CanvasRelayTopic): Boolean = mutex.withLock {
        when (gaps[topic]) {
            null -> true
            true -> true.also { gaps.remove(topic) }
            false -> false
        }
    }
}
