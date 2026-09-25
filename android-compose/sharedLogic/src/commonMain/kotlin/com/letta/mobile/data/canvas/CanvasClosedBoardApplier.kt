package com.letta.mobile.data.canvas

/**
 * Applies a relayed op to a canvas no board has open in this app (letta-mobile-qygvv.23).
 *
 * The relay stays joined to a canvas's topic after its board closes, and the host keeps fanning ops
 * out to it: an agent drawing while the person is back in chat. Handed to no session, those ops
 * were skipped and never replayed. Written straight into the op log and document store instead,
 * the board is current when it is next opened, and still after a relaunch.
 */
fun interface CanvasClosedBoardApplier {
    /** True when [canvasId] exists here and [op] was settled against it (applied, a duplicate, or refused). */
    suspend fun apply(canvasId: CanvasId, op: CanvasOp, vouchedActor: String?): Boolean

    /** The canvases to keep current with no board open: joined on every connection. */
    suspend fun known(): List<CanvasId> = emptyList()
}

/**
 * [CanvasClosedBoardApplier] over the app's own [store] and [opLog]: a session opened for the one
 * op, so a closed board takes it exactly as an open one would ([CanvasSession.applyRemote]):
 * deduplicated by op id, checked against the canvas's ACL or the host's vouch, projected
 * last-writer-wins and persisted.
 */
class StoreCanvasClosedBoardApplier(
    private val store: CanvasDocumentStore,
    private val opLog: CanvasOpLog,
) : CanvasClosedBoardApplier {
    override suspend fun apply(canvasId: CanvasId, op: CanvasOp, vouchedActor: String?): Boolean {
        val session = CanvasSession.open(store, canvasId, CanvasConversationOptions(opLog = opLog)) ?: return false
        session.applyRemote(op, vouchedActor)
        return true
    }

    /** Every conversation's canvas this app has, the most recently used first, up to [MAX_KNOWN]. */
    override suspend fun known(): List<CanvasId> =
        store.listAll().filter { it.conversationId != null }.take(MAX_KNOWN).map { it.id }

    private companion object {
        /** Each is a topic joined, and caught up, on every connection. */
        const val MAX_KNOWN = 50
    }
}
