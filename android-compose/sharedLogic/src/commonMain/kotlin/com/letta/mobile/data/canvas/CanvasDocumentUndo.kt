package com.letta.mobile.data.canvas

/**
 * How to put a document change back.
 *
 * The session's op log only moves forward: undoing a note is not a rewind, it is a new op that
 * happens to restore what was there. So every document change is recorded together with the op
 * that reverses it, computed against the scene AS IT WAS before the change - which is the only
 * moment the old text, frame, colour and style are still known.
 *
 * [CanvasSession.setDocument] leaves a field alone when it is given null, so an inverse always
 * names every field explicitly. Restoring a note whose colour was null by omitting the colour
 * would keep whatever colour the change had set.
 */
object CanvasDocumentUndo {

    /**
     * The op that undoes [change], given the documents as they were before it.
     *
     * Null when the change would not alter the documents at all, so nothing is recorded and the
     * undo button does not stop on a step that does nothing.
     */
    fun inverseOf(change: CanvasOp, before: List<CanvasSceneDocument>): CanvasOp? = when (change) {
        is CanvasOp.SetDocumentOp -> inverseOfSet(change, before.firstOrNull { it.id == change.documentId })
        is CanvasOp.RemoveDocumentOp -> before.firstOrNull { it.id == change.documentId }?.let { restore(it) }
        is CanvasOp.BatchOp -> inverseOfBatch(change, before)
        else -> null
    }

    /**
     * Undoing a batch means undoing its parts in the opposite order: a batch that removes a note
     * and then moves another has to put the moved one back first, or the restore lands on a scene
     * the second inverse no longer describes.
     */
    private fun inverseOfBatch(change: CanvasOp.BatchOp, before: List<CanvasSceneDocument>): CanvasOp? {
        val inverses = change.ops.reversed().mapNotNull { inverseOf(it, before) }
        if (inverses.isEmpty()) return null
        return CanvasOp.BatchOp(
            opId = CanvasOpDiffer.generateOpId("undo"),
            actorId = change.actorId,
            lamport = change.lamport,
            ops = inverses,
        )
    }

    private fun inverseOfSet(change: CanvasOp.SetDocumentOp, previous: CanvasSceneDocument?): CanvasOp? {
        // A document that did not exist is undone by removing it again.
        if (previous == null) {
            return CanvasOp.RemoveDocumentOp(
                opId = CanvasOpDiffer.generateOpId("undo"),
                actorId = change.actorId,
                lamport = change.lamport,
                documentId = change.documentId,
            )
        }
        val wouldChange = previous.json != change.documentJson ||
            (change.frame != null && change.frame != previous.frame) ||
            (change.color != null && change.color != previous.color) ||
            (change.style != null && change.style != previous.style)
        return if (wouldChange) restore(previous) else null
    }

    /** The op that puts [document] back exactly as it was, every field named. */
    private fun restore(document: CanvasSceneDocument): CanvasOp.SetDocumentOp = CanvasOp.SetDocumentOp(
        opId = CanvasOpDiffer.generateOpId("undo"),
        actorId = CanvasSession.LOCAL_USER_ACTOR_ID,
        lamport = 0L,
        documentId = document.id,
        documentJson = document.json,
        frame = document.frame,
        color = document.color,
        style = document.style,
    )
}
