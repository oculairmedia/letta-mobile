package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasComposeProvenance
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSceneDocument
import com.letta.mobile.data.canvas.compose.CanvasComposeCompiler.ELEMENT_COMPOSE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * The pieces of an artifact already on the board, by board id, each as its content without
 * geometry: a person may have moved a note since, and that does not make a retry a conflict.
 */
internal class ExistingArtifact(
    val signatures: Map<String, JsonObject>,
    private val documents: List<CanvasSceneDocument>,
    private val elements: List<JsonObject>,
) {
    fun isEmpty(): Boolean = signatures.isEmpty()

    /** Whether [emitted] is this very artifact again: the same pieces with the same content. */
    fun matches(emitted: List<Emitted>): Boolean = signatures == emitted.associate { idOf(it.op) to signature(it.op) }

    /** Board id to the frame that piece has now, so a retry's receipt names where it sits. */
    fun frames(): Map<String, List<Int>> {
        val notes = documents.mapNotNull { document -> document.frame?.let { document.id to slotOf(it).frameInts() } }
        val drawn = elements.mapNotNull { element ->
            val id = element.string("id") ?: return@mapNotNull null
            elementSlot(element)?.let { id to it.frameInts() }
        }
        return (notes + drawn).toMap()
    }

    /** The rectangle around every piece, where they sit now. */
    fun bounds(): ComposeBounds? = CanvasComposePlacement.union(pieceSlots())

    private fun pieceSlots(): List<Slot> =
        documents.mapNotNull { document -> document.frame?.let(::slotOf) } + elements.mapNotNull(::elementSlot)

    private fun elementSlot(element: JsonObject): Slot? = when (kindOf(element)) {
        ComposeKind.GROUP.name -> groupFrameSlot(element)
        ComposeKind.TEXT.name -> textSlot(element)
        else -> null
    }

    private fun slotOf(frame: CanvasDocumentFrame): Slot = Slot(frame.x, frame.y, frame.width, frame.height)

    /** A group's frame; its label, a Text element of the same kind, is not counted. */
    private fun groupFrameSlot(element: JsonObject): Slot? =
        element.takeIf { it.string("type") == "Shape" }?.let { CanvasComposePlacement.elementBounds(it, conservative = false) }

    /**
     * TEXT stores a top-left and a wrap width, not a height, so a retry reports the reserved
     * slot again rather than a height read off the element.
     */
    private fun textSlot(element: JsonObject): Slot? {
        val (x, y) = element.string("textTopLeft")?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 2 } ?: return null
        val size = if ((element.number("fontSize") ?: 0.0) >= CanvasComposeReserve.TEXT_HEADING_FONT) ComposeTextSize.HEADING else ComposeTextSize.BODY
        val width = element.number("wrapWidth")?.toFloat() ?: CanvasComposeContract.width(ComposeKind.TEXT, size)
        return Slot(x, y, width, CanvasComposeReserve.reserveText(element.string("text").orEmpty(), size, width))
    }

    companion object {
        /** What an element's content is made of; position and DrawBox defaults are not content. */
        private val ELEMENT_CONTENT = listOf("type", "shapeType", "text", "fontSize", ELEMENT_COMPOSE)

        private val json = Json { ignoreUnknownKeys = true }

        fun of(sceneJson: String, artifactId: String): ExistingArtifact {
            val documents = CanvasOpProjector.documentsOf(sceneJson).filter { it.compose?.artifactId == artifactId }
            val root = runCatching { json.parseToJsonElement(sceneJson) }.getOrNull() as? JsonObject
            val elements = (root?.get("elements") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { artifactOf(it) == artifactId }
            val signatures = documents.associate { it.id to DocumentContent(it.json, it.color, it.title, it.compose).signature() } +
                elements.mapNotNull { element -> element.string("id")?.let { it to elementSignature(element) } }
            return ExistingArtifact(signatures, documents, elements)
        }

        private fun idOf(op: CanvasOp): String = when (op) {
            is CanvasOp.SetDocumentOp -> op.documentId
            is CanvasOp.AddElementOp -> op.elementId
            else -> error("compose emits no ${op::class.simpleName}")
        }

        private fun signature(op: CanvasOp): JsonObject = when (op) {
            is CanvasOp.SetDocumentOp -> DocumentContent(op.documentJson, op.color, op.title, op.compose).signature()
            is CanvasOp.AddElementOp -> elementSignature(json.parseToJsonElement(op.elementJson) as JsonObject)
            else -> error("compose emits no ${op::class.simpleName}")
        }

        private fun elementSignature(element: JsonObject) = buildJsonObject {
            ELEMENT_CONTENT.forEach { key ->
                val value = element[key] ?: return@forEach
                val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                put(key, if (number != null) JsonPrimitive(number) else value)
            }
        }

        private fun composeOf(element: JsonObject): JsonObject? = element[ELEMENT_COMPOSE] as? JsonObject

        private fun artifactOf(element: JsonObject): String? = composeOf(element)?.string("artifactId")

        private fun kindOf(element: JsonObject): String? = composeOf(element)?.string("kind")

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    }
}

/** A document piece's content, as stored on the board or as a `set_document` would write it. */
private class DocumentContent(
    private val documentJson: String,
    private val color: String?,
    private val title: String?,
    private val compose: CanvasComposeProvenance?,
) {
    fun signature(): JsonObject = buildJsonObject {
        put("document", runCatching { json.parseToJsonElement(documentJson) }.getOrElse { JsonPrimitive(documentJson) })
        put("color", color?.lowercase())
        put("title", title?.takeIf { it.isNotBlank() })
        put("compose", compose?.let { json.encodeToJsonElement(CanvasComposeProvenance.serializer(), it) } ?: JsonNull)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
