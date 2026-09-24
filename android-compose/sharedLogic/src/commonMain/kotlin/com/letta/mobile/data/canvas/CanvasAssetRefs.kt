package com.letta.mobile.data.canvas

/**
 * The assets an op refers to: every `sha256:<hex>` in the content it carries (an element, a note,
 * a whole scene). Generic on purpose: whatever kind of element comes to hold an asset, its ref is
 * found the same way, and a string that only looks like one is harmless - an app sends an asset
 * only when it holds bytes under that ref.
 */
object CanvasAssetRefs {
    private val REF = Regex("sha256:[a-f0-9]{64}")

    fun of(op: CanvasOp): Set<String> = buildSet { collect(op) }

    private fun MutableSet<String>.collect(op: CanvasOp) {
        when (op) {
            is CanvasOp.AddElementOp -> scan(op.elementJson)
            is CanvasOp.UpdateElementOp -> scan(op.elementJson)
            is CanvasOp.ReplaceSceneOp -> scan(op.sceneJson)
            is CanvasOp.SetDocumentOp -> scan(op.documentJson)
            is CanvasOp.BatchOp -> op.ops.forEach { collect(it) }
            else -> Unit
        }
    }

    private fun MutableSet<String>.scan(json: String) {
        REF.findAll(json).forEach { add(it.value) }
    }
}
