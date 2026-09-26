package com.letta.mobile.data.canvas

/** One op of a batch as the projector applies it: nested batch ops opened out, each labelled by where it sat. */
internal data class CanvasBatchStep(val label: String, val op: CanvasOp, val scratch: CanvasOp)

internal object CanvasBatchSteps {
    /**
     * [ops] opened out in the order the projector applies them. Each carries a [CanvasBatchStep.scratch]
     * copy stamped after [baseLamport], the scene's newest write: published, the host stamps them
     * after everything in the log, so they win, and they must win on the scratch copy too.
     */
    fun of(ops: List<CanvasOp>, baseLamport: Long): List<CanvasBatchStep> {
        val flat = ops.flatMapIndexed { index, op -> open(index.toString(), op) }
        return flat.mapIndexed { position, (label, op) ->
            CanvasBatchStep(label, op, op.withStamp("validate-$position", baseLamport + position + 1))
        }
    }

    private fun open(label: String, op: CanvasOp): List<Pair<String, CanvasOp>> =
        if (op is CanvasOp.BatchOp) op.ops.flatMapIndexed { index, inner -> open("$label.$index", inner) } else listOf(label to op)

    /** `update_element 'box-1'`: the op's wire type and what it names. */
    fun describe(op: CanvasOp): String = subjectOf(op).let { subject -> if (subject.isEmpty()) wireType(op) else "${wireType(op)} '$subject'" }

    fun subjectOf(op: CanvasOp): String = when (op) {
        is CanvasOp.AddElementOp -> op.elementId
        is CanvasOp.UpdateElementOp -> op.elementId
        is CanvasOp.RemoveElementOp -> op.elementId
        is CanvasOp.SetArrowBindingOp -> op.elementId
        is CanvasOp.SetLabelOwnerOp -> op.documentId
        is CanvasOp.SetDocumentOp -> op.documentId
        is CanvasOp.RemoveDocumentOp -> op.documentId
        is CanvasOp.ReplaceSceneOp, is CanvasOp.SetBackgroundOp, is CanvasOp.SetBackgroundPatternOp, is CanvasOp.BatchOp -> ""
    }

    private fun wireType(op: CanvasOp): String = when (op) {
        is CanvasOp.ReplaceSceneOp -> "replace_scene"
        is CanvasOp.AddElementOp -> "add_element"
        is CanvasOp.UpdateElementOp -> "update_element"
        is CanvasOp.RemoveElementOp -> "remove_element"
        is CanvasOp.SetBackgroundOp -> "set_background"
        is CanvasOp.SetBackgroundPatternOp -> "set_background_pattern"
        is CanvasOp.SetArrowBindingOp -> "set_arrow_binding"
        is CanvasOp.SetLabelOwnerOp -> "set_label_owner"
        is CanvasOp.SetDocumentOp -> "set_document"
        is CanvasOp.RemoveDocumentOp -> "remove_document"
        is CanvasOp.BatchOp -> "batch"
    }
}
