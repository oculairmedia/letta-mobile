package com.letta.mobile.data.canvas

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The board's one undo history.
 *
 * Two different things change on a board and each kept its own history, which is why the undo
 * button never touched a note: the drawing is DrawBox's, and DrawBox owns that stack privately -
 * `undo()`, `redo()` and two booleans, with no way to read or interleave it - while notes, text
 * and labels are ops on the [CanvasSession]'s log, which had no undo at all.
 *
 * So this records the one thing neither side can know on its own: the ORDER the person did things
 * in. A drawing step is a marker that delegates back to DrawBox; a document step carries the ops
 * that put the documents back and the ops that do them again. Undo pops whichever came last,
 * whichever kind it is, which is the only behaviour that matches what the person remembers doing.
 *
 * This holds no Compose, no session and no controller: it is the order and nothing else, so it can
 * be tested as the sequence of steps it is.
 */
class CanvasHistory(private val limit: Int = DEFAULT_LIMIT) {

    /** One thing the person did, in the order they did it. */
    sealed interface Step {
        /**
         * A change DrawBox recorded in its own stack. Undoing it means asking DrawBox to undo,
         * because the elements it holds are not ours to reconstruct.
         *
         * [documents] carries the document work that belongs to the SAME action: deleting a
         * labelled shape takes its label with it, and a step that restored the shape without its
         * text would hand back an empty box and call it undone.
         */
        data class Drawing(val documents: Documents? = null) : Step

        /**
         * A change to the documents, with the ops that undo it and the ops that do it again.
         * Both directions are carried because the session's log only moves forward: putting a
         * note back is a new op, not a rewind.
         */
        data class Documents(
            val undo: List<CanvasOp>,
            val redo: List<CanvasOp>,
            /** What happened, for a status line: "note removed", "text resized". */
            val label: String = "",
        ) : Step
    }

    private val done = ArrayDeque<Step>()
    private val undone = ArrayDeque<Step>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /**
     * Folds [documents] into the most recent drawing step.
     *
     * The board notices a shape's label has to go a moment AFTER the shape itself does - the
     * reconciler runs when the elements settle - so the two halves of one action arrive
     * separately. Joining them here is what makes undo give back the shape and its text together,
     * rather than in two presses with an empty box in between.
     *
     * Returns false when there is no drawing step to fold into, so the caller can record the
     * document work as a step of its own rather than lose it.
     */
    fun addToLastDrawing(documents: Step.Documents): Boolean {
        val last = done.lastOrNull() as? Step.Drawing ?: return false
        val merged = last.documents?.let {
            Step.Documents(
                undo = it.undo + documents.undo,
                redo = it.redo + documents.redo,
                label = it.label,
            )
        } ?: documents
        done.removeLast()
        done.addLast(Step.Drawing(merged))
        return true
    }

    /**
     * Records something the person just did.
     *
     * Anything they had undone is dropped, the way every editor behaves: once you undo and then
     * do something new, the branch you abandoned is gone.
     */
    fun record(step: Step) {
        done.addLast(step)
        while (done.size > limit) done.removeFirst()
        undone.clear()
        publish()
    }

    /**
     * The step to undo, already moved onto the redo side, or null when there is nothing to undo.
     *
     * The caller performs it: a [Step.Drawing] by asking DrawBox, a [Step.Documents] by applying
     * its [Step.Documents.undo] ops. Handing the step back rather than performing it here is what
     * keeps this testable without a session or a controller.
     */
    fun undo(): Step? {
        val step = done.removeLastOrNull() ?: return null
        undone.addLast(step)
        publish()
        return step
    }

    /** The step to redo, moved back onto the done side, or null when there is nothing to redo. */
    fun redo(): Step? {
        val step = undone.removeLastOrNull() ?: return null
        done.addLast(step)
        publish()
        return step
    }

    /**
     * Forgets everything, for a board that has been replaced wholesale - a checkpoint restored,
     * the agent replacing the scene, a different canvas opened. The steps that remain would undo
     * into a board that no longer exists.
     */
    fun clear() {
        done.clear()
        undone.clear()
        publish()
    }

    private fun publish() {
        _canUndo.value = done.isNotEmpty()
        _canRedo.value = undone.isNotEmpty()
    }

    private companion object {
        /** Deep enough that no one reaches the end by hand, shallow enough to stay cheap. */
        const val DEFAULT_LIMIT = 200
    }
}
