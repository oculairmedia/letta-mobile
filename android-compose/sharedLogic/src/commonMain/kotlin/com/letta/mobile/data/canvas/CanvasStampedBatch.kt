package com.letta.mobile.data.canvas

/**
 * A batch an agent writes as one thing (letta-mobile-bglj6.12): every op rebound to [actorId] and
 * stamped after [afterLamport] in order, each with an id of its own, wrapped in one
 * [CanvasOp.BatchOp] that carries the newest of their clocks.
 *
 * One op is what makes it all or nothing on a relay: the host's log appends it, acknowledges it and
 * fans it out as one entry, so a failure anywhere leaves none of it on the board, and an app takes
 * it in one revision. The Iroh host (`HostCanvasBackend.publish(atomic = true)`) and an app's live
 * session ([CanvasSession.applyAgentBatch]) build it here, so the two publish the same ops.
 */
object CanvasStampedBatch {
    fun of(ops: List<CanvasOp>, actorId: String, afterLamport: Long, newOpId: () -> String): CanvasOp.BatchOp {
        val stamped = separately(ops, actorId, afterLamport, newOpId)
        return CanvasOp.BatchOp(opId = newOpId(), actorId = actorId, lamport = stamped.lastOrNull()?.lamport ?: afterLamport, ops = stamped)
    }

    /**
     * [ops] rebound to [actorId] and stamped after [afterLamport] in order, each its own op: what
     * `canvas_apply_ops` publishes on both hosts (letta-mobile-s416w.5), so an update an agent sends
     * is newer than the board it read, whatever clock the model wrote.
     */
    fun separately(ops: List<CanvasOp>, actorId: String, afterLamport: Long, newOpId: () -> String): List<CanvasOp> {
        var lamport = afterLamport
        return ops.map { it.withActor(actorId).withStamp(newOpId(), ++lamport) }
    }
}
