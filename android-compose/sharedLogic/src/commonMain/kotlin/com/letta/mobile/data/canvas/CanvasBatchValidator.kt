package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.plugin.PluginBoardKinds
import com.letta.mobile.data.canvas.plugin.PluginKindCatalog

/** One refused op of a batch: where it is ([opIndex], `2` or `2.0` inside a batch op), what it is and the rule it breaks. */
data class CanvasBatchViolation(val opIndex: String, val op: String, val violation: CanvasStateViolation) {
    override fun toString(): String = "op $opIndex ($op): ${violation.detail} [${violation.invariant.wire} on '${violation.subject}']"
}

/** A writer's batch measured against the scene it would change. */
sealed interface CanvasBatchCheck {
    /** Every op may be published: [ops] normalised as [CanvasSceneValidator] fills them in, [sceneJson] the scene they leave. */
    data class Valid(val ops: List<CanvasOp>, val sceneJson: String) : CanvasBatchCheck

    /** Nothing of the batch may be published. */
    data class Invalid(val violations: List<CanvasBatchViolation>) : CanvasBatchCheck {
        val message: String get() = CanvasBatchValidator.refusal(violations)
    }
}

/**
 * Holds a whole batch to the state the board is left in, not just to the shape of each element
 * in it (letta-mobile-qygvv.30). A batch of well-formed ops can still break a board: an update of
 * an element that is not there, a remove of a shape a label still belongs to, an arrow bound to
 * a note that is gone. The batch is applied to a scratch copy of the current scene with the
 * real projector and the result checked ([CanvasSceneState]); one broken rule refuses all of it.
 *
 * Only what the batch INTRODUCES is refused. A board that already breaks a rule (written before
 * this check existed) must still accept the writes that repair it.
 *
 * A plugin element's props are held to its kind's schema in [PluginKindCatalog] (the host's
 * installed kinds; [PluginKindCatalog.Empty] where none are), the kind read from the board for a
 * write that names none.
 */
object CanvasBatchValidator {
    fun check(sceneJson: String, ops: List<CanvasOp>, kinds: PluginKindCatalog = PluginKindCatalog.Empty): CanvasBatchCheck {
        val board = PluginBoardKinds.of(sceneJson, ops)
        val shaped = ops.mapIndexed { index, op -> index to CanvasSceneValidator.ops(listOf(op), kinds, board) }
        val shapeProblems = shaped.flatMap { (index, check) -> (check as? CanvasOpsCheck.Invalid)?.let { shapeViolations(index, ops[index], it) }.orEmpty() }
        if (shapeProblems.isNotEmpty()) return CanvasBatchCheck.Invalid(shapeProblems)
        val normalised = shaped.flatMap { (_, check) -> (check as CanvasOpsCheck.Valid).ops }
        val projection = CanvasBatchProjection(sceneJson, CanvasBatchSteps.of(normalised, CanvasOpProjector.maxLamport(sceneJson)))
        val violations = projection.violations()
        return if (violations.isEmpty()) CanvasBatchCheck.Valid(normalised, projection.result) else CanvasBatchCheck.Invalid(violations)
    }

    fun refusal(violations: List<CanvasBatchViolation>): String = buildString {
        append("Refused: nothing in this batch was published (the board is unchanged). ")
        append("${violations.size} problem(s):\n")
        violations.forEach { append("- ").append(it).append('\n') }
        append("Fix them and send the whole batch again; pass dry_run: true to check a batch without publishing it.")
    }

    private fun shapeViolations(index: Int, op: CanvasOp, check: CanvasOpsCheck.Invalid): List<CanvasBatchViolation> {
        val located = check.problems.filter { it.path != null }
        if (op !is CanvasOp.SetPluginElementOp || located.isEmpty()) {
            return listOf(violation(index, op, CanvasStateViolation(shapeInvariant(op), CanvasBatchSteps.subjectOf(op), check.message)))
        }
        // One per rule broken, each named by where it is: `pe-1/fallback/title`.
        return located.map { problem ->
            violation(index, op, CanvasStateViolation(CanvasStateInvariant.PLUGIN_ELEMENT_SHAPE, op.elementId + problem.path, "${problem.path} ${problem.reason}"))
        }
    }

    private fun shapeInvariant(op: CanvasOp): CanvasStateInvariant =
        if (op is CanvasOp.SetDocumentOp) CanvasStateInvariant.DOCUMENT_DECODES else CanvasStateInvariant.ELEMENT_SHAPE

    private fun violation(index: Int, op: CanvasOp, violation: CanvasStateViolation) =
        CanvasBatchViolation(index.toString(), CanvasBatchSteps.describe(op), violation)
}
