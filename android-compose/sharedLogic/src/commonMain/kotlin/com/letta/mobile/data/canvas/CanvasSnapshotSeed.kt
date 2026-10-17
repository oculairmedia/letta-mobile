package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.CanvasOpId

/**
 * Ops that rebuild a board from its stored snapshot, for a canvas's first join to a host when its
 * op log no longer reaches back to the board's start (letta-mobile-ckmub.17.9): a bounded log drops
 * settled history, so uploading only the log would give the host the kept tail, not the board.
 */
fun interface CanvasSnapshotSeed {
    /** The seed ops for [canvasId]; none when its op log still holds everything. Never written to the log. */
    suspend fun seed(canvasId: CanvasId): List<CanvasOp>
}

/**
 * [CanvasSnapshotSeed] over the app's own [store] and [opLog]. The seed upserts what the stored board
 * holds - its elements and background, block documents and plugin elements - and removes nothing,
 * so on a host where the conversation's canvas already has content it adds this board's beside it.
 *
 * Each seed op is stamped just below the oldest op the log kept: what the log still holds (sent
 * beside the seed at its own Lamport) and any newer edit elsewhere win over it. Its id names only what
 * it writes, so a seed queued on one connection is found again on the next, and sent once.
 * Seeds stay out of the bounded log, which would let go of them as it lets go of the history.
 */
class StoreCanvasSnapshotSeed(
    private val store: CanvasDocumentStore,
    private val opLog: CanvasOpLog,
) : CanvasSnapshotSeed {
    override suspend fun seed(canvasId: CanvasId): List<CanvasOp> {
        if (!opLog.hasDroppedHistory(canvasId)) return emptyList()
        val board = store.get(canvasId) ?: return emptyList()
        val kept = opLog.getOps(canvasId, Long.MIN_VALUE)
        val lamport = ((kept.minOfOrNull { it.lamport } ?: CanvasOpProjector.maxLamport(board.sceneJson)) - 1).coerceAtLeast(0L)
        return CanvasSnapshotOps.of(board.sceneJson, SnapshotStamp(lamport))
    }
}

/** Where seed ops are stamped: one Lamport for all, ids from what each op writes. */
internal data class SnapshotStamp(val lamport: Long) {
    fun opId(kind: String, id: String): String = "seed-$kind-$id"
}

/** The upserts that put [sceneJson]'s content on an empty board, as the board's own user. */
internal object CanvasSnapshotOps {
    private val actor = CanvasSession.LOCAL_USER_ACTOR_ID

    fun of(sceneJson: String, stamp: SnapshotStamp): List<CanvasOp> =
        drawing(sceneJson, stamp) +
            CanvasOpProjector.documentsOf(sceneJson).map { document(it, stamp) } +
            CanvasOpProjector.pluginElementsOf(sceneJson).map { pluginElement(it, stamp) }

    /** The elements and background, as the differ writes them from an empty scene. */
    private fun drawing(sceneJson: String, stamp: SnapshotStamp): List<CanvasOp> =
        CanvasOpDiffer.diff("", sceneJson, CanvasSession.LOCAL_USER_ACTOR) { stamp.lamport }.map { op ->
            val target = (op as? CanvasOp.AddElementOp)?.elementId ?: "background"
            op.withStamp(CanvasOpId(stamp.opId("drawing", target)), stamp.lamport)
        }

    private fun document(document: CanvasSceneDocument, stamp: SnapshotStamp) = CanvasOp.SetDocumentOp(
        opId = stamp.opId("document", document.id),
        actorId = actor,
        lamport = stamp.lamport,
        documentId = document.id,
        documentJson = document.json,
        frame = document.frame,
        color = document.color,
        style = document.style,
        title = document.title,
        owner = document.owner,
        compose = document.compose,
    )

    private fun pluginElement(element: CanvasPluginElement, stamp: SnapshotStamp) = CanvasOp.SetPluginElementOp(
        opId = stamp.opId("plugin", element.id),
        actorId = actor,
        lamport = stamp.lamport,
        elementId = element.id,
        elementType = element.type,
        v = element.v,
        frame = element.frame,
        owner = element.owner,
        ref = element.ref,
        props = element.props,
        snapshot = element.snapshot,
        fallback = element.fallback,
        meta = element.meta,
    )
}
