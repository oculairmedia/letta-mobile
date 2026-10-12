package com.letta.mobile.data.document

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
 * The one total order on document ops: (lamport, actorId, opId).
 *
 * Every client settles conflicts by it (last writer wins) and replays in it, so all of them reach
 * the same snapshot whatever order ops arrived in. The op id is the final tie-break because every
 * device edits as the same user actor with its own clock: two devices can write at an equal
 * (lamport, actorId), and without it the later arrival would win, differently on each device.
 */
object DocumentOpOrder : Comparator<DocumentOp> {
    override fun compare(a: DocumentOp, b: DocumentOp): Int =
        compare(a.lamport, a.actorId, a.opId, b.lamport, b.actorId, b.opId)

    @Suppress("LongParameterList") // the three keys of two ops, compared without allocating
    fun compare(
        lamportA: Long,
        actorA: String,
        opIdA: String,
        lamportB: Long,
        actorB: String,
        opIdB: String,
    ): Int = when {
        lamportA != lamportB -> lamportA.compareTo(lamportB)
        actorA != actorB -> actorA.compareTo(actorB)
        else -> opIdA.compareTo(opIdB)
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
