package com.letta.mobile.data.canvas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Typed ops for the batch-validation tests (letta-mobile-qygvv.30): drawable elements, a note, its owner. */
internal object CanvasBatchFixtures {
    const val EMPTY_NOTE = "{\"version\":2,\"blocks\":[]}"

    fun shapeJson(id: String): String =
        JsonObject(CanvasSceneSchema.shape.example + ("id" to JsonPrimitive(id))).toString()

    fun addShape(id: String): CanvasOp = CanvasOp.AddElementOp("", "", 0L, id, shapeJson(id))

    fun updateShape(id: String): CanvasOp = CanvasOp.UpdateElementOp("", "", 0L, id, shapeJson(id))

    /** An update that names only the text: a Text element with no `textTopLeft`. */
    fun partialUpdate(id: String): CanvasOp = CanvasOp.UpdateElementOp("", "", 0L, id, "{\"type\":\"Text\",\"text\":\"hi\"}")

    fun remove(id: String): CanvasOp = CanvasOp.RemoveElementOp("", "", 0L, id)

    fun note(id: String, documentJson: String = EMPTY_NOTE): CanvasOp = CanvasOp.SetDocumentOp("", "", 0L, id, documentJson)

    fun removeNote(id: String): CanvasOp = CanvasOp.RemoveDocumentOp("", "", 0L, id)

    fun owner(documentId: String, shapeId: String?): CanvasOp = CanvasOp.SetLabelOwnerOp("", "", 0L, documentId, shapeId)

    /** A box with a label note it owns, as a batch. */
    fun labelledBox(shapeId: String, labelId: String): List<CanvasOp> = listOf(addShape(shapeId), note(labelId), owner(labelId, shapeId))

    /** [ops] projected from nothing, each stamped after the last, as a log would hold them. */
    fun sceneOf(ops: List<CanvasOp>): String =
        CanvasOpProjector.project("", ops.mapIndexed { index, op -> op.withStamp("seed-$index", index + 1L) })
}
