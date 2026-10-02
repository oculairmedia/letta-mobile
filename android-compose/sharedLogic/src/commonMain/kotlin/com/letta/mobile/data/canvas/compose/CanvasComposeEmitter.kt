package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasComposeProvenance
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.compose.CanvasComposeCompiler.Built
import com.letta.mobile.data.canvas.compose.CanvasComposeCompiler.ELEMENT_COMPOSE
import com.letta.mobile.data.canvas.compose.CanvasComposeCompiler.Entry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One op of the batch and the pointer of the item it was made from. */
internal class Emitted(val op: CanvasOp, val path: String)

/**
 * The ops of [CanvasComposeCompiler]'s batch, placed by [placement]: group frames, then documents,
 * then texts (TEXT items and group labels), so what is drawn under comes first.
 */
internal class ComposeEmitter(private val artifactId: String, private val placement: Placement) {
    fun emit(built: List<Built>): List<Emitted> {
        val all = built.flatMap { if (it is Built.Group) listOf(it) + it.children else listOf(it) }
        val groups = all.filterIsInstance<Built.Group>().map { Emitted(groupFrame(it), it.entry.path) }
        val documents = all.filterIsInstance<Built.Document>().map { Emitted(document(it), it.entry.path) }
        val texts = all.mapNotNull { piece ->
            when (piece) {
                is Built.Text -> Emitted(text(piece), piece.entry.path)
                is Built.Group -> label(piece)
                is Built.Document -> null
            }
        }
        return groups + documents + texts
    }

    private fun slot(entry: Entry): Slot = placement.slots.getValue(entry.key)

    private fun provenance(entry: Entry) = CanvasComposeProvenance(
        artifactId = artifactId,
        key = entry.key,
        kind = entry.item.kind.name,
        catalog = CanvasComposeContract.CATALOG,
        version = CanvasComposeContract.VERSION,
    )

    private fun document(piece: Built.Document): CanvasOp.SetDocumentOp {
        val slot = slot(piece.entry)
        return CanvasOp.SetDocumentOp(
            opId = "",
            actorId = "",
            lamport = 0L,
            documentId = CanvasComposeIds.piece(artifactId, piece.entry.key),
            documentJson = piece.documentJson,
            frame = CanvasDocumentFrame(slot.x, slot.y, slot.width, slot.height),
            color = piece.color,
            title = piece.title,
            owner = CanvasGeometryOwner.AUTO,
            compose = provenance(piece.entry),
        )
    }

    private fun text(piece: Built.Text): CanvasOp.AddElementOp {
        val words = TextLook(piece.text, CanvasComposeReserve.textFont(piece.size), CanvasComposeColors.TEXT, CanvasComposeCompiler.TEXT_Z)
        return element(CanvasComposeIds.piece(artifactId, piece.entry.key), textJson(words, slot(piece.entry), piece.entry))
    }

    /** A group's label as a text element, when it has one. */
    private fun label(group: Built.Group): Emitted? {
        val label = group.label ?: return null
        val slot = placement.labels.getValue(group.entry.key)
        val words = TextLook(
            label,
            CanvasComposePlacement.GROUP_LABEL_FONT.toDouble(),
            CanvasComposeColors.GROUP_LABEL,
            CanvasComposeCompiler.GROUP_LABEL_Z,
        )
        return Emitted(element(CanvasComposeIds.label(artifactId, group.entry.key), textJson(words, slot, group.entry)), group.entry.path)
    }

    private fun groupFrame(group: Built.Group): CanvasOp.AddElementOp {
        val slot = slot(group.entry)
        return element(
            CanvasComposeIds.piece(artifactId, group.entry.key),
            buildJsonObject {
                put("type", "Shape")
                put("shapeType", "RECTANGLE")
                put("points", buildJsonArray { add(JsonPrimitive(slot.topLeftPoint())); add(JsonPrimitive(slot.bottomRightPoint())) })
                put("strokeColor", CanvasComposeColors.GROUP_STROKE)
                put("strokeWidth", CanvasComposeCompiler.GROUP_STROKE_WIDTH)
                put("fillColor", CanvasComposeColors.GROUP_FILL)
                put("cornerRadius", CanvasComposeCompiler.GROUP_CORNER_RADIUS)
                put("zIndex", CanvasComposeCompiler.GROUP_Z)
                put(ELEMENT_COMPOSE, provenanceJson(group.entry))
            },
        )
    }

    private fun textJson(words: TextLook, slot: Slot, entry: Entry): JsonObject = buildJsonObject {
        put("type", "Text")
        put("text", words.text)
        put("textTopLeft", slot.topLeftPoint())
        put("wrapWidth", slot.width.toDouble())
        put("fontSize", words.font)
        put("alignment", CanvasComposeCompiler.TEXT_ALIGNMENT)
        put("fontFamilyKey", CanvasComposeCompiler.TEXT_FONT_FAMILY)
        put("strokeColor", words.color)
        put("zIndex", words.z)
        put(ELEMENT_COMPOSE, provenanceJson(entry))
    }

    private fun provenanceJson(entry: Entry): JsonElement =
        json.encodeToJsonElement(CanvasComposeProvenance.serializer(), provenance(entry))

    private fun element(id: String, body: JsonObject) =
        CanvasOp.AddElementOp(opId = "", actorId = "", lamport = 0L, elementId = id, elementJson = body.toString())

    /** A text element's words and how they are set: font size, colour and stacking. */
    private class TextLook(val text: String, val font: Double, val color: String, val z: Int)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * DrawBox's `"x,y"`, written the same on every target: placement hands out whole numbers, and
 * a whole number is printed with one decimal as the JVM does (Kotlin/JS would drop it).
 */
internal fun Slot.topLeftPoint(): String = "${drawBoxNumber(x)},${drawBoxNumber(y)}"

internal fun Slot.bottomRightPoint(): String = "${drawBoxNumber(right)},${drawBoxNumber(bottom)}"

private fun drawBoxNumber(value: Float): String {
    val whole = value.toLong()
    return if (whole.toFloat() == value) "$whole.0" else value.toString()
}
