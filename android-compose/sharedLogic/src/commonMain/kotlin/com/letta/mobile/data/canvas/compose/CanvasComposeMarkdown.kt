package com.letta.mobile.data.canvas.compose

/**
 * The markdown subset canvas_compose accepts (letta-mobile-bglj6.8, docs/design/canvas-compose-plan.md
 * section 3.3), read into [MdBlock]s that [CanvasCascadeBlocks] writes as the board's block JSON.
 *
 * The subset: CommonMark paragraphs, ATX headings 1-3, `-`/`*`/`+` bullet items, `1.` numbered
 * items, GFM task items (`- [ ]`, `- [x]`), `>` quotes of plain text, fenced code (``` or ~~~),
 * thematic breaks (`---`), and inline `**bold**`, `*italic*`, `` `code` ``, `[text](url)` and
 * `~~strike~~`, with backslash escapes. Lists nest one level.
 *
 * Strict by design: a construct outside the subset (raw HTML, images, tables, reference links,
 * footnotes, setext headings, deeper nesting, ...) is refused with the character offset where it
 * starts. Nothing degrades to plain text, because an agent that sees its table arrive as a
 * paragraph of pipes has no way to learn it was not understood. Text inside the subset is taken as
 * written: entities are not decoded, nothing else is interpreted.
 *
 * Owned rather than a library: the subset is small, the Iroh host runs sharedLogic without
 * Compose, and refusing (rather than rendering) what is outside the subset is the point.
 *
 * The block structure is read by [MdBlockReader] (line syntax in [MdLine]), inline syntax by
 * [MdInlineReader].
 */
object CanvasComposeMarkdown {
    /** Problems reported for one field; past this the agent has enough to fix, and the refusal stays small. */
    const val MAX_PROBLEMS = 10

    /** List nesting the board's blocks carry (the item and one level of children). */
    const val MAX_LIST_DEPTH = 2

    const val MAX_HEADING_LEVEL = 3

    /**
     * [markdown] read into blocks, or every problem found (at most [MAX_PROBLEMS]), each at [path]
     * (`/items/2/markdown`) with the character offset in its message. Empty or blank markdown is
     * MISSING_FIELD: a note with nothing in it is not something to compose.
     */
    fun parse(markdown: String, path: String): MdParse {
        if (markdown.isBlank()) {
            return MdParse.Refused(listOf(ComposeProblem(path, ComposeProblemCode.MISSING_FIELD, "the markdown is empty")))
        }
        val problems = MdProblems(path)
        val blocks = MdBlockReader(SourceLine.split(markdown), problems).read()
        return if (problems.isEmpty()) MdParse.Parsed(blocks) else MdParse.Refused(problems.list())
    }
}

/** Markdown read: either the blocks, or why not. */
sealed interface MdParse {
    data class Parsed(val blocks: List<MdBlock>) : MdParse

    data class Refused(val problems: List<ComposeProblem>) : MdParse
}

enum class MdSpanStyle { BOLD, ITALIC, INLINE_CODE, LINK, STRIKETHROUGH }

/** A style over `[start, end)` of a block's text; [url] only for [MdSpanStyle.LINK]. */
data class MdSpan(val style: MdSpanStyle, val start: Int, val end: Int, val url: String? = null)

/** A block's text with its inline styles, the markdown syntax already removed. */
data class MdText(val text: String, val spans: List<MdSpan> = emptyList())

/**
 * One block of a note. [indent] is the list nesting level (0, or 1 for a child item), which the
 * board stores as the block's indentation rather than as nested blocks.
 */
sealed interface MdBlock {
    data class Paragraph(val content: MdText) : MdBlock

    data class Heading(val level: Int, val content: MdText) : MdBlock

    data class BulletItem(val content: MdText, val indent: Int = 0) : MdBlock

    data class NumberedItem(val number: Int, val content: MdText, val indent: Int = 0) : MdBlock

    data class TodoItem(val checked: Boolean, val content: MdText, val indent: Int = 0) : MdBlock

    data class Quote(val content: MdText) : MdBlock

    /** Verbatim lines; code carries no inline styles. */
    data class Code(val text: String) : MdBlock

    data object Divider : MdBlock
}
