package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.plugin.CanvasPluginElementSchema

/**
 * What one op of a batch names that is not there (letta-mobile-qygvv.30). The projector upserts
 * and tombstones whatever it is given, so an update of a missing element quietly creates a
 * half-written one and a remove of a missing one leaves a tombstone: both have to be caught
 * before the op is logged.
 */
internal object CanvasOpReferences {
    /** What [op] needs of [scene], the scene just before it applies. */
    fun before(op: CanvasOp, scene: CanvasSceneIndex): CanvasStateViolation? = when (op) {
        is CanvasOp.AddElementOp -> if (scene.hasElement(op.elementId)) alreadyThere(op.elementId) else null
        is CanvasOp.UpdateElementOp -> missingElement(op.elementId, "update_element", scene, "add it with add_element")
        is CanvasOp.RemoveElementOp -> missingElement(op.elementId, "remove_element", scene, "re-read it with canvas_get_scene")
        is CanvasOp.RemoveDocumentOp -> missingDocument(op.documentId, "remove_document", scene)
        is CanvasOp.SetDocumentOp -> undecodableDocument(op)
        is CanvasOp.SetPluginElementOp -> incompleteFirstWrite(op, scene)
        is CanvasOp.RemovePluginElementOp -> missingPluginElement(op, scene)
        else -> null
    }

    /** What [op] needs of [scene], the scene the whole batch leaves. */
    fun after(op: CanvasOp, scene: CanvasSceneIndex): CanvasStateViolation? = when (op) {
        is CanvasOp.SetArrowBindingOp -> missingElement(op.elementId, "set_arrow_binding", scene, "bind a connector that exists")
        is CanvasOp.SetLabelOwnerOp -> op.shapeId?.let { missingDocument(op.documentId, "set_label_owner", scene) }
        else -> null
    }

    private fun alreadyThere(elementId: String) = CanvasStateViolation(
        CanvasStateInvariant.ELEMENT_DUPLICATE_ID, elementId,
        "add_element names element '$elementId', which is already on the canvas; change it with update_element",
    )

    private fun missingElement(elementId: String, verb: String, scene: CanvasSceneIndex, hint: String): CanvasStateViolation? =
        if (scene.hasElement(elementId)) null
        else CanvasStateViolation(
            CanvasStateInvariant.ELEMENT_EXISTS, elementId, "$verb names element '$elementId', which is not on the canvas; $hint",
        )

    private fun missingDocument(documentId: String, verb: String, scene: CanvasSceneIndex): CanvasStateViolation? =
        if (scene.hasDocument(documentId)) null
        else CanvasStateViolation(
            CanvasStateInvariant.DOCUMENT_EXISTS, documentId, "$verb names document '$documentId', which is not on the canvas",
        )

    private fun missingPluginElement(op: CanvasOp.RemovePluginElementOp, scene: CanvasSceneIndex): CanvasStateViolation? =
        if (scene.hasPluginElement(op.elementId)) null
        else CanvasStateViolation(
            CanvasStateInvariant.PLUGIN_ELEMENT_EXISTS, op.elementId,
            "remove_plugin_element names plugin element '${op.elementId}', which is not on the canvas",
        )

    /** A plugin element the board does not hold yet must arrive whole enough for every app to draw it. */
    private fun incompleteFirstWrite(op: CanvasOp.SetPluginElementOp, scene: CanvasSceneIndex): CanvasStateViolation? {
        if (scene.hasPluginElement(op.elementId)) return null
        val missing = CanvasPluginElementSchema.firstWriteProblems(op).takeIf { it.isNotEmpty() } ?: return null
        return CanvasStateViolation(
            CanvasStateInvariant.PLUGIN_ELEMENT_FIRST_WRITE, op.elementId,
            "set_plugin_element places new plugin element '${op.elementId}': " + missing.joinToString("; ") { "${it.path} ${it.reason}" },
        )
    }

    private fun undecodableDocument(op: CanvasOp.SetDocumentOp): CanvasStateViolation? =
        if (CanvasSceneStateChecks.decodes(op.documentJson)) null
        else CanvasStateViolation(
            CanvasStateInvariant.DOCUMENT_DECODES, op.documentId,
            "set_document's documentJson is not a Cascade document: a JSON object with a \"blocks\" array, e.g. {\"blocks\":[]}",
        )
}
