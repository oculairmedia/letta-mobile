package com.letta.mobile.data.canvas.compose

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** What the estimator needs of one block: its type, its words, its heading level and nesting depth. */
data class ReserveBlock(
    val type: ReserveBlockType,
    val text: String,
    val level: Int = 1,
    val depth: Int = 0,
)

/** The Cascade block types the estimator tells apart (plan section 3.2). */
enum class ReserveBlockType {
    PARAGRAPH,
    HEADING,
    BULLET,
    NUMBERED,
    TODO,
    QUOTE,
    CODE,
    DIVIDER,
    ;

    companion object {
        private const val HEADING_PREFIX = "heading_"

        /**
         * The level of a cascade-editor heading `typeId`, which carries it (`heading_1` .. `heading_6`,
         * as `BlockTypeCodec` 1.9.2 writes `BlockType.Heading(level)`); null for anything else.
         */
        fun headingLevel(typeId: String?): Int? =
            typeId?.takeIf { it.startsWith(HEADING_PREFIX) }?.removePrefix(HEADING_PREFIX)?.toIntOrNull()

        /** The type of a Cascade `typeId`; anything unknown is booked as a paragraph. */
        fun of(typeId: String?): ReserveBlockType = when {
            headingLevel(typeId) != null -> HEADING
            else -> ofPlain(typeId)
        }

        private fun ofPlain(typeId: String?): ReserveBlockType = when (typeId) {
            // A bare "heading" with a separate level is not what the library writes; read as one anyway.
            "heading" -> HEADING
            "bullet_list" -> BULLET
            "numbered_list" -> NUMBERED
            "todo" -> TODO
            "quote" -> QUOTE
            "code" -> CODE
            "divider" -> DIVIDER
            else -> PARAGRAPH
        }
    }
}

/**
 * The blocks of a stored Cascade v2 document, depth-first, for [CanvasComposeReserve]. A block's
 * depth is its `attributes.indentationLevel` (how cascade-editor 1.9.2 and [CanvasCascadeBlocks]
 * store a nested list item: a flat list, the child carrying the level) plus how deep it sits in
 * nested `children`; each level takes one indent off the line, as the editor indents it. Reads only
 * `type.typeId`, `type.level`, `attributes.indentationLevel` and `content.text`, so a newer
 * document still yields its blocks; a block of a type it does not know is booked as a paragraph,
 * unreadable input as none.
 */
internal object ReserveDocument {
    /** Deeper than this is not an indentation any editor draws; it only stops a hostile value booking nothing. */
    private const val MAX_INDENTATION = 16

    private val json = Json { ignoreUnknownKeys = true }

    fun blocksOf(documentJson: String): List<ReserveBlock> {
        if (documentJson.isBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(documentJson) }.getOrNull() as? JsonObject ?: return emptyList()
        val out = mutableListOf<ReserveBlock>()
        collect(root["blocks"], depth = 0, into = out)
        return out
    }

    private fun collect(blocks: JsonElement?, depth: Int, into: MutableList<ReserveBlock>) {
        (blocks as? JsonArray)?.forEach { element ->
            val block = element as? JsonObject ?: return@forEach
            into += blockOf(block, depth)
            collect(block["children"], depth + 1, into)
        }
    }

    private fun blockOf(block: JsonObject, depth: Int): ReserveBlock {
        val type = block["type"] as? JsonObject
        val typeId = (type?.get("typeId") as? JsonPrimitive)?.contentOrNull
        val level = ReserveBlockType.headingLevel(typeId) ?: (type?.get("level") as? JsonPrimitive)?.intOrNull ?: 1
        val text = ((block["content"] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return ReserveBlock(ReserveBlockType.of(typeId), text, level, depth + indentationOf(block))
    }

    /** A block's `attributes.indentationLevel`, 0 when absent or unreadable. */
    private fun indentationOf(block: JsonObject): Int {
        val value = (block[CanvasCascadeBlocks.KEY_ATTRIBUTES] as? JsonObject)?.get(CanvasCascadeBlocks.KEY_INDENTATION_LEVEL) as? JsonPrimitive
        return (value?.intOrNull ?: value?.contentOrNull?.toIntOrNull() ?: 0).coerceIn(0, MAX_INDENTATION)
    }
}
