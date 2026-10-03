package com.letta.mobile.data.canvas.compose

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Writes compose content as the block document a canvas note stores and renders (the Cascade
 * editor's v2 JSON), without depending on the editor: cascade-editor is a sharedUI dependency and
 * the Iroh host runs sharedLogic without Compose (letta-mobile-bglj6.8).
 *
 * Every key and type id below is what cascade-editor 1.9.2 writes and reads
 * (`io.github.linreal.cascade.editor.serialization.DocumentSchema` and `RichTextSchema`, over
 * `core.BlockType`, `core.BlockAttributes` and `core.SpanStyle`). They are pinned here rather than
 * guessed: desktop's CanvasCascadeBlocksOracleTest loads this writer's output with the library and
 * compares it with what the library itself writes for the same blocks, so a key that drifts from
 * the library fails there.
 *
 * Block ids are `b1`..`bn` in document order. Nesting is the block's indentation level
 * (`attributes.indentationLevel`), as the library stores it: a flat block list, not nested blocks.
 */
object CanvasCascadeBlocks {
    // cascade-editor 1.9.2 DocumentSchema: the document envelope and a block's keys.
    const val DOCUMENT_VERSION = 2
    const val KEY_VERSION = "version"
    const val KEY_BLOCKS = "blocks"
    const val KEY_ID = "id"
    const val KEY_TYPE = "type"
    const val KEY_ATTRIBUTES = "attributes"
    const val KEY_CONTENT = "content"

    // BlockType: `typeId` plus the type's own property (Heading's level is part of its id).
    const val KEY_TYPE_ID = "typeId"
    const val TYPE_PARAGRAPH = "paragraph"
    const val TYPE_HEADING_PREFIX = "heading_"
    const val TYPE_BULLET_LIST = "bullet_list"
    const val TYPE_NUMBERED_LIST = "numbered_list"
    const val TYPE_TODO = "todo"
    const val TYPE_QUOTE = "quote"
    const val TYPE_CODE = "code"
    const val TYPE_DIVIDER = "divider"
    const val KEY_CHECKED = "checked"
    const val KEY_NUMBER = "number"

    // BlockAttributes: only written when the level is not 0, and only for types that indent.
    const val KEY_INDENTATION_LEVEL = "indentationLevel"

    // BlockContent: text (RichTextSchema v1) or empty (a divider).
    const val KEY_KIND = "kind"
    const val KIND_TEXT = "text"
    const val KIND_EMPTY = "empty"
    const val TEXT_VERSION = 1
    const val KEY_TEXT = "text"
    const val KEY_SPANS = "spans"

    // TextSpan and SpanStyle.
    const val KEY_START = "start"
    const val KEY_END = "end"
    const val KEY_STYLE = "style"
    const val KEY_STYLE_TYPE = "type"
    const val KEY_URL = "url"
    const val STYLE_BOLD = "bold"
    const val STYLE_ITALIC = "italic"
    const val STYLE_INLINE_CODE = "inline_code"
    const val STYLE_LINK = "link"
    const val STYLE_STRIKETHROUGH = "strikethrough"

    /** The card heading level (plan: a CARD is a level-2 heading, its fields, then its body). */
    const val CARD_HEADING_LEVEL = 2

    /** Separates a card field's label from its value; the label and this are bold. */
    const val CARD_FIELD_SEPARATOR = ": "

    /** A note's blocks as a document. */
    fun document(blocks: List<MdBlock>): String = encode(blocks)

    /** A CHECKLIST: one to-do per entry, its text as written. */
    fun checklist(items: List<ComposeChecklistItem>): String =
        encode(items.map { MdBlock.TodoItem(it.checked == true, MdText(it.text)) })

    /**
     * A CARD: the title as a level-2 heading, each field as a `Label: value` paragraph with the
     * label (and its colon) bold, then the body's blocks.
     */
    fun card(title: String, fields: List<ComposeCardField>, body: List<MdBlock>): String {
        val heading = MdBlock.Heading(CARD_HEADING_LEVEL, MdText(title))
        val rows = fields.map { field ->
            val label = field.label + CARD_FIELD_SEPARATOR.trimEnd()
            MdBlock.Paragraph(MdText(field.label + CARD_FIELD_SEPARATOR + field.value, listOf(MdSpan(MdSpanStyle.BOLD, 0, label.length))))
        }
        return encode(listOf(heading) + rows + body)
    }

    private fun encode(blocks: List<MdBlock>): String = buildJsonObject {
        put(KEY_VERSION, DOCUMENT_VERSION)
        put(KEY_BLOCKS, JsonArray(blocks.mapIndexed { i, block -> block(i + 1, block) }))
    }.toString()

    private fun block(ordinal: Int, block: MdBlock): JsonObject = buildJsonObject {
        put(KEY_ID, "b$ordinal")
        put(KEY_TYPE, type(block))
        indent(block).takeIf { it > 0 }?.let { level ->
            put(KEY_ATTRIBUTES, buildJsonObject { put(KEY_INDENTATION_LEVEL, level) })
        }
        put(KEY_CONTENT, content(block))
    }

    private fun type(block: MdBlock): JsonObject = buildJsonObject {
        when (block) {
            is MdBlock.Paragraph -> put(KEY_TYPE_ID, TYPE_PARAGRAPH)
            is MdBlock.Heading -> put(KEY_TYPE_ID, TYPE_HEADING_PREFIX + block.level)
            is MdBlock.BulletItem -> put(KEY_TYPE_ID, TYPE_BULLET_LIST)
            is MdBlock.NumberedItem -> {
                put(KEY_TYPE_ID, TYPE_NUMBERED_LIST)
                put(KEY_NUMBER, block.number)
            }
            is MdBlock.TodoItem -> {
                put(KEY_TYPE_ID, TYPE_TODO)
                put(KEY_CHECKED, block.checked)
            }
            is MdBlock.Quote -> put(KEY_TYPE_ID, TYPE_QUOTE)
            is MdBlock.Code -> put(KEY_TYPE_ID, TYPE_CODE)
            MdBlock.Divider -> put(KEY_TYPE_ID, TYPE_DIVIDER)
        }
    }

    private fun indent(block: MdBlock): Int = when (block) {
        is MdBlock.BulletItem -> block.indent
        is MdBlock.NumberedItem -> block.indent
        is MdBlock.TodoItem -> block.indent
        else -> 0
    }

    private fun content(block: MdBlock): JsonObject = when (block) {
        is MdBlock.Paragraph -> text(block.content)
        is MdBlock.Heading -> text(block.content)
        is MdBlock.BulletItem -> text(block.content)
        is MdBlock.NumberedItem -> text(block.content)
        is MdBlock.TodoItem -> text(block.content)
        is MdBlock.Quote -> text(block.content)
        // The library keeps no spans on a code block.
        is MdBlock.Code -> text(MdText(block.text))
        MdBlock.Divider -> buildJsonObject { put(KEY_KIND, KIND_EMPTY) }
    }

    private fun text(text: MdText): JsonObject = buildJsonObject {
        put(KEY_KIND, KIND_TEXT)
        put(KEY_VERSION, TEXT_VERSION)
        put(KEY_TEXT, text.text)
        put(KEY_SPANS, buildJsonArray { text.spans.forEach { add(span(it)) } })
    }

    private fun span(span: MdSpan): JsonObject = buildJsonObject {
        put(KEY_START, span.start)
        put(KEY_END, span.end)
        put(
            KEY_STYLE,
            buildJsonObject {
                put(KEY_STYLE_TYPE, styleType(span.style))
                if (span.style == MdSpanStyle.LINK) put(KEY_URL, JsonPrimitive(span.url.orEmpty()))
            },
        )
    }

    private fun styleType(style: MdSpanStyle): String = when (style) {
        MdSpanStyle.BOLD -> STYLE_BOLD
        MdSpanStyle.ITALIC -> STYLE_ITALIC
        MdSpanStyle.INLINE_CODE -> STYLE_INLINE_CODE
        MdSpanStyle.LINK -> STYLE_LINK
        MdSpanStyle.STRIKETHROUGH -> STYLE_STRIKETHROUGH
    }
}
