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
     * Undoing a batch means undoing its parts in the opposite order, each against the documents as
     * they were IMMEDIATELY BEFORE that part ran.
     *
     * Both halves matter and they are different. Reverse order is needed because a batch that
     * removes one note and moves another has to put the moved one back first. Sequential state is
     * needed because children can depend on each other: a batch that creates a note and then edits
     * it has a second child whose "before" is the note the FIRST child created, not the scene the
     * batch started from. Computing every inverse against the pre-batch state undoes that edit to
     * a document that did not exist yet, and the undo writes a note back rather than removing it.
     */
    private fun inverseOfBatch(change: CanvasOp.BatchOp, before: List<CanvasSceneDocument>): CanvasOp? {
        var state = before
        val inversesInOrder = change.ops.map { child ->
            val inverse = inverseOf(child, state)
            state = project(child, state)
            inverse
        }
        val inverses = inversesInOrder.reversed().filterNotNull()
        if (inverses.isEmpty()) return null
        return CanvasOp.BatchOp(
            opId = CanvasOpDiffer.generateOpId("undo"),
            actorId = change.actorId,
            lamport = change.lamport,
            ops = inverses,
        )
    }

    /**
     * [documents] with [change] applied, for walking a batch child by child.
     *
     * This mirrors what the projector does to the scene, for documents only: it exists so an
     * inverse can be taken against the state its own child actually saw.
     */
    private fun project(change: CanvasOp, documents: List<CanvasSceneDocument>): List<CanvasSceneDocument> =
        when (change) {
            is CanvasOp.SetDocumentOp -> {
                val existing = documents.firstOrNull { it.id == change.documentId }
                val updated = CanvasSceneDocument(
                    id = change.documentId,
                    json = change.documentJson,
                    // A null field means "leave it alone", which is what setDocument does, so the
                    // projection has to keep the old value rather than clearing it.
                    frame = change.frame ?: existing?.frame,
                    color = change.color ?: existing?.color,
                    style = change.style ?: existing?.style,
                )
                if (existing == null) documents + updated else documents.map { if (it.id == updated.id) updated else it }
            }
            is CanvasOp.RemoveDocumentOp -> documents.filterNot { it.id == change.documentId }
            is CanvasOp.BatchOp -> change.ops.fold(documents) { acc, child -> project(child, acc) }
            else -> documents
        }

    private fun inverseOfSet(change: CanvasOp.SetDocumentOp, previous: CanvasSceneDocument?): CanvasOp? {
        if (previous == null) return removeFor(change)
        return if (documentDiffers(change, previous)) restore(previous) else null
    }

    private fun documentDiffers(change: CanvasOp.SetDocumentOp, previous: CanvasSceneDocument): Boolean =
        previous.json != change.documentJson ||
            (change.frame != null && change.frame != previous.frame) ||
            (change.color != null && change.color != previous.color) ||
            (change.style != null && change.style != previous.style)

    private fun removeFor(change: CanvasOp.SetDocumentOp): CanvasOp.RemoveDocumentOp =
        CanvasOp.RemoveDocumentOp(
            opId = CanvasOpDiffer.generateOpId("undo"),
            actorId = change.actorId,
            lamport = change.lamport,
            documentId = change.documentId,
        )

    /**
     * The history step that turns [before] into [after], or null when nothing changed.
     *
     * Taken as a diff of the documents rather than from the ops that did it. A single board action
     * can touch several documents through several calls - a marquee drag moves every selected
     * note - and what undo owes the person is the state they had, not a transcript of the calls
     * that left it.
     */
    fun stepBetween(
        before: List<CanvasSceneDocument>,
        after: List<CanvasSceneDocument>,
        label: String = "",
    ): CanvasHistory.Step.Documents? {
        val beforeById = before.associateBy { it.id }
        val afterById = after.associateBy { it.id }
        val undo = mutableListOf<CanvasOp>()
        val redo = mutableListOf<CanvasOp>()

        (beforeById.keys + afterById.keys).sorted().forEach { id ->
            val was = beforeById[id]
            val now = afterById[id]
            when {
                was == now -> Unit
                was == null && now != null -> {
                    undo += remove(id)
                    redo += restore(now)
                }
                was != null && now == null -> {
                    undo += restore(was)
                    redo += remove(id)
                }
                was != null && now != null -> {
                    undo += restore(was)
                    redo += restore(now)
                }
            }
        }
        if (undo.isEmpty()) return null
        return CanvasHistory.Step.Documents(undo = undo, redo = redo, label = label)
    }

    private fun remove(documentId: String): CanvasOp.RemoveDocumentOp = CanvasOp.RemoveDocumentOp(
        opId = CanvasOpDiffer.generateOpId("undo"),
        actorId = CanvasSession.LOCAL_USER_ACTOR_ID,
        lamport = 0L,
        documentId = documentId,
    )

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
