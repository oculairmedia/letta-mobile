package com.letta.mobile.data.canvas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The shape kinds the batch-validation fixtures draw (a subset of [CanvasSceneSchema.shapeTypes]). */
internal enum class FixtureShapeKind { RECTANGLE, ARROW }

/** The note bodies the fixtures write: a Cascade document the editor opens, and a ProseMirror one it cannot. */
internal enum class FixtureNoteBody(val json: String) {
    CASCADE_EMPTY("{\"version\":2,\"blocks\":[]}"),
    PROSE_MIRROR("{\"type\":\"doc\",\"content\":[]}"),
}

/** A drawn element of a fixture board, by id, and the ops that write it (letta-mobile-qygvv.30). */
@kotlin.jvm.JvmInline
internal value class FixtureElement(val id: String) {
    /** The schema's example Shape as this element: [kind], its end bound to [endBinding] when given. */
    fun json(kind: FixtureShapeKind = FixtureShapeKind.RECTANGLE, endBinding: FixtureElement? = null): String {
        val binding = endBinding?.let { mapOf("endBinding" to JsonPrimitive(it.id)) }.orEmpty()
        val fields = mapOf("id" to JsonPrimitive(id), "shapeType" to JsonPrimitive(kind.name))
        return JsonObject(CanvasSceneSchema.shape.example + fields + binding).toString()
    }

    fun add(kind: FixtureShapeKind = FixtureShapeKind.RECTANGLE, endBinding: FixtureElement? = null): CanvasOp =
        CanvasOp.AddElementOp("", "", 0L, id, json(kind, endBinding))

    fun update(): CanvasOp = CanvasOp.UpdateElementOp("", "", 0L, id, json())

    /** An update that names only the text: a Text element with no `textTopLeft`. */
    fun partialUpdate(): CanvasOp = CanvasOp.UpdateElementOp("", "", 0L, id, "{\"type\":\"Text\",\"text\":\"hi\"}")

    fun remove(): CanvasOp = CanvasOp.RemoveElementOp("", "", 0L, id)
}

/** A block-document note of a fixture board, by id, and the ops that write it. */
@kotlin.jvm.JvmInline
internal value class FixtureNote(val id: String) {
    fun set(body: FixtureNoteBody = FixtureNoteBody.CASCADE_EMPTY): CanvasOp = CanvasOp.SetDocumentOp("", "", 0L, id, body.json)

    fun remove(): CanvasOp = CanvasOp.RemoveDocumentOp("", "", 0L, id)

    /** Marks this note the label of [shape] (released when null). */
    fun ownedBy(shape: FixtureElement?): CanvasOp = CanvasOp.SetLabelOwnerOp("", "", 0L, id, shape?.id)
}

/** Boards built from typed ops for the batch-validation tests. */
internal object CanvasBatchFixtures {
    val box1 = FixtureElement("box-1")
    val box2 = FixtureElement("box-2")
    val box7 = FixtureElement("box-7")
    val arrow1 = FixtureElement("arrow-1")
    val ghost = FixtureElement("ghost")
    val label1 = FixtureNote("label-1")
    val note1 = FixtureNote("n-1")

    /** A box with a label note it owns, as a batch. */
    fun labelledBox(shape: FixtureElement, label: FixtureNote): List<CanvasOp> = listOf(shape.add(), label.set(), label.ownedBy(shape))

    /** [ops] wrapped in one batch op. */
    fun batchOf(ops: List<CanvasOp>): CanvasOp = CanvasOp.BatchOp("", "", 0L, ops)

    /** [ops] projected from nothing, each stamped after the last, as a log would hold them. */
    fun sceneOf(ops: List<CanvasOp>): String =
        CanvasOpProjector.project("", ops.mapIndexed { index, op -> op.withStamp("seed-$index", index + 1L) })
}
