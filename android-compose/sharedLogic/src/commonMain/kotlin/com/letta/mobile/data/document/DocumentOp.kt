package com.letta.mobile.data.document

import kotlinx.serialization.Serializable

/**
 * What every op on a [SharedDocument] carries, whatever the document is: an id to deduplicate
 * redelivery, the actor the ACL judges, and a Lamport clock for the one total order.
 *
 * The canvas's ops ([com.letta.mobile.data.canvas.CanvasOp]) are the first implementation; the
 * Agent Tray's Markdown ops ([com.letta.mobile.data.document.markdown.MarkdownOp]) the second.
 */
interface DocumentOp {
    val opId: String
    val actorId: String
    val lamport: Long
}

/**
 * The ordering keys of one op, kept on its own: what a last-writer-wins field records about the op
 * that last wrote it, and compares the next writer against in [DocumentOpOrder].
 */
@Serializable
data class OpStamp(
    override val lamport: Long,
    override val actorId: String,
    override val opId: String,
) : DocumentOp {
    /** Whether [op] comes after this stamp in [DocumentOpOrder], and so wins the field. */
    fun isOlderThan(op: DocumentOp): Boolean = DocumentOpOrder.compare(this, op) < 0

    companion object {
        fun of(op: DocumentOp): OpStamp = OpStamp(op.lamport, op.actorId, op.opId)
    }
}

/**
 * The one total order on document ops: (lamport, actorId, opId).
 *
 * Every client settles conflicts by it (last writer wins) and replays in it, so all of them reach
 * the same snapshot whatever order ops arrived in. The op id is the final tie-break because every
 * device edits as the same user actor with its own clock: two devices can write at an equal
 * (lamport, actorId), and without it the later arrival would win, differently on each device.
 * [com.letta.mobile.data.canvas.CanvasOpOrder] is the same order over the canvas's raw fields.
 */
object DocumentOpOrder : Comparator<DocumentOp> {
    override fun compare(a: DocumentOp, b: DocumentOp): Int = when {
        a.lamport != b.lamport -> a.lamport.compareTo(b.lamport)
        a.actorId != b.actorId -> a.actorId.compareTo(b.actorId)
        else -> a.opId.compareTo(b.opId)
    }
}

/**
 * The persistent state of one shared document at a revision: what a [DocumentStore] keeps and a
 * [SharedDocument] folds ops into. [revision] is the compare-and-set key; [updatedAtEpochMs] orders
 * a library listing.
 */
interface DocumentSnapshot<Id> {
    val id: Id
    val revision: Long
    val updatedAtEpochMs: Long
}
