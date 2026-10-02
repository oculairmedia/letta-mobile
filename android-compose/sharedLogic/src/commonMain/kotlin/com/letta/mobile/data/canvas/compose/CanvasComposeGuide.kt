package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.compose.CanvasComposeContract as Contract

/**
 * What `canvas.compose_guide` answers: the "read me first" an agent calls once before composing
 * (Excalidraw MCP's `read_me`, Miro's composer skill), kept out of the `canvas.compose`
 * description so that one stays short (letta-mobile-bglj6.14).
 *
 * Built from the code it describes, so it cannot drift: the caps from [CanvasComposeContract],
 * the colours from [CanvasComposeColors], the ids from [CanvasComposeIds], the markdown limits from
 * [CanvasComposeMarkdown], the placement numbers from [CanvasComposePlacement], and every error and
 * problem code from its enum (a new code without an entry here does not compile). Each problem
 * example is a real request that CanvasComposeGuideTest sends and checks is refused with that code
 * at that path. docs/reference/canvas-compose-v1.md carries a copy, held equal by a jvmTest.
 */
object CanvasComposeGuide {
    /** The guide is read once per session; it has to stay a short read. */
    const val MAX_CHARS = 8_000

    /** The worked example, the same request as the v1 `request.json` fixture. */
    const val EXAMPLE_REQUEST = """{
  "catalog": "letta.canvas.compose",
  "version": 1,
  "artifact_id": "weekend-plan",
  "title": "Weekend plan",
  "items": [
    { "kind": "TEXT", "key": "heading", "text": "Weekend plan", "size": "heading" },
    { "kind": "CHECKLIST", "key": "shopping", "title": "Shopping", "color": "yellow",
      "items": [ { "text": "Milk" }, { "text": "Eggs", "checked": true }, { "text": "Bread" } ] },
    { "kind": "NOTE", "key": "meals", "title": "Meals", "color": "green",
      "markdown": "## Saturday\n- Pasta\n\n## Sunday\n- Roast\n- [ ] Buy a chicken" },
    { "kind": "GROUP", "key": "selfcare", "label": "Self-care",
      "children": [
        { "kind": "CARD", "key": "walk", "title": "Walk",
          "fields": [ { "label": "When", "value": "Sat 09:00" }, { "label": "Where", "value": "Park" } ] },
        { "kind": "NOTE", "key": "read", "markdown": "Finish *Dune* before Sunday." }
      ] }
  ]
}"""

    /**
     * One worked refusal: [request] is refused with [code] at [path]. The guide shows [shown] (the
     * request itself when short, else what it does); CanvasComposeGuideTest sends [request].
     */
    data class ProblemExample(val code: ComposeProblemCode, val path: String, val shown: String, val request: String)

    /** One example for every problem code, in the enum's order. */
    val PROBLEM_EXAMPLES: List<ProblemExample> = ComposeProblemCode.entries.map(::exampleOf)

    private fun exampleOf(code: ComposeProblemCode): ProblemExample = when (code) {
        ComposeProblemCode.UNKNOWN_KIND -> shortExample(code, "/items/0/kind", """{"kind":"STICKY","markdown":"x"}""")
        ComposeProblemCode.UNKNOWN_FIELD -> shortExample(code, "/items/0/x", """{"kind":"NOTE","markdown":"x","x":40}""")
        ComposeProblemCode.MISSING_FIELD -> shortExample(code, "/items/0/title", """{"kind":"CARD","fields":[]}""")
        ComposeProblemCode.TOO_MANY_ITEMS -> ProblemExample(
            code, "/items/0/items", "a CHECKLIST of ${Contract.MAX_CHECKLIST_ITEMS + 1} entries",
            items("""{"kind":"CHECKLIST","items":[${List(Contract.MAX_CHECKLIST_ITEMS + 1) { """{"text":"e$it"}""" }.joinToString(",")}]}"""),
        )
        ComposeProblemCode.TOO_LONG -> ProblemExample(
            code, "/items/0/title", "a title of ${Contract.MAX_TITLE_CHARS + 1} characters",
            items("""{"kind":"NOTE","title":"${"t".repeat(Contract.MAX_TITLE_CHARS + 1)}","markdown":"x"}"""),
        )
        ComposeProblemCode.DUPLICATE_KEY -> shortExample(code, "/items/1/key", """{"kind":"NOTE","key":"a","markdown":"x"},{"kind":"NOTE","key":"a","markdown":"y"}""")
        ComposeProblemCode.BAD_KEY -> shortExample(code, "/items/0/key", """{"kind":"NOTE","key":"My note","markdown":"x"}""")
        ComposeProblemCode.BAD_COLOR -> shortExample(code, "/items/0/color", """{"kind":"NOTE","color":"blue","markdown":"x"}""")
        ComposeProblemCode.UNSUPPORTED_MARKDOWN -> shortExample(code, "/items/0/markdown", """{"kind":"NOTE","markdown":"<b>Hi</b>"}""")
        ComposeProblemCode.NESTING_TOO_DEEP -> shortExample(
            code, "/items/0/children/0/kind", """{"kind":"GROUP","children":[{"kind":"GROUP","children":[]}]}""",
        )
        ComposeProblemCode.WRONG_TYPE -> shortExample(code, "/items/0/items/0/checked", """{"kind":"CHECKLIST","items":[{"text":"x","checked":"yes"}]}""")
        ComposeProblemCode.BAD_VALUE -> shortExample(code, "/items/0/size", """{"kind":"TEXT","text":"x","size":"huge"}""")
    }

    private fun shortExample(code: ComposeProblemCode, path: String, items: String) =
        ProblemExample(code, path, "items: [$items]", items(items))

    private fun items(items: String) = """{"items":[$items]}"""

    /** What each refusal code means, with what to do about it. */
    private fun meaning(code: ComposeErrorCode): String = when (code) {
        ComposeErrorCode.VALIDATION_FAILED -> "the request breaks the format; the problems below say where."
        ComposeErrorCode.UNSUPPORTED_VERSION ->
            "another catalog or version, e.g. \"version\": 2 is refused at /version; send ${Contract.VERSION} or leave it out."
        ComposeErrorCode.ARTIFACT_EXISTS ->
            "the artifact_id is on the board with other content (path /artifact_id); compose never changes an artifact, so use a new artifact_id."
        ComposeErrorCode.BOARD_REFUSED ->
            "the board's own rules refused the batch: each problem's code is the rule (e.g. element.exists, an id already taken) " +
                "and its path the item; ${CanvasComposeService.PUBLISH_FAILED} means the write did not land, so send the same request again."
        ComposeErrorCode.UNAUTHORIZED -> "you may not write to that canvas."
        ComposeErrorCode.CANVAS_NOT_FOUND -> "no such canvas; omit canvas_id for this conversation's canvas, or pick one from ${CanvasToolContract.LIST}."
    }

    val text: String by lazy {
        val keyMax = Contract.KEY_PATTERN.maxLength()
        val idMax = Contract.ARTIFACT_ID_PATTERN.maxLength()
        val piece = CanvasComposeIds.piece("<artifact_id>", "<key>")
        buildString {
            appendLine("# ${CanvasToolContract.COMPOSE} (${Contract.CATALOG}, version ${Contract.VERSION})")
            appendLine()
            appendLine("Read this once before composing. You say WHAT goes on the board; the board decides where and how big. A request has no coordinates, sizes or layers.")
            appendLine("One call makes one artifact, all or nothing: if anything is wrong, nothing is published and every problem is listed.")
            appendLine("Use ${CanvasToolContract.APPLY_OPS} instead to draw (shapes, arrows, paths, images), to put a note at a frame you choose (set_document with a frame), or to change or remove anything already on the board: compose only creates.")
            appendLine()
            appendLine("## Request")
            appendLine("- items (required): what to make, in reading order; 1 to ${Contract.MAX_ITEMS}, the children of groups counted.")
            appendLine("- artifact_id: names the artifact; lowercase letters, digits, _ and -, at most $idMax. Without one it is derived from the tool call (${CanvasComposeIds.DERIVED_PREFIX} and ${CanvasComposeIds.DERIVED_HEX} hex digits).")
            appendLine("- title: names the artifact in the chat, at most ${Contract.MAX_TITLE_CHARS} characters.")
            appendLine("- canvas_id: omit it for the canvas of the conversation you are in.")
            appendLine("- dry_run: true runs every check and answers the receipt with status \"dry_run\"; nothing is published.")
            appendLine("- catalog, version: optional; \"${Contract.CATALOG}\" and ${Contract.VERSION}. The whole request is at most ${Contract.MAX_REQUEST_BYTES / 1024} KiB.")
            appendLine()
            appendLine("## Kinds")
            appendLine("Every item has a \"kind\" and an optional \"key\": lowercase letters, digits, _ and -, at most $keyMax, unique in the request.")
            appendLine("- NOTE {markdown, title?, color?}: markdown up to ${Contract.MAX_MARKDOWN_CHARS} characters.")
            appendLine("- CHECKLIST {items: [{text, checked?}], title?, color?}: 1 to ${Contract.MAX_CHECKLIST_ITEMS} entries of up to ${Contract.MAX_VALUE_CHARS} characters; checked defaults to false.")
            appendLine("- CARD {title, fields?: [{label, value}], markdown?, color?}: a titled note: up to ${Contract.MAX_CARD_FIELDS} fields (label up to ${Contract.MAX_LABEL_CHARS}, value up to ${Contract.MAX_VALUE_CHARS}) shown as bold \"label: value\" lines, then up to ${Contract.MAX_CARD_BODY_CHARS} characters of markdown. Not a container.")
            appendLine("- TEXT {text, size: heading|body}: up to ${Contract.MAX_TEXT_CHARS} characters on the board itself, outside any note.")
            appendLine("- GROUP {children, label?}: a labelled frame (label up to ${Contract.MAX_LABEL_CHARS}) around NOTE, CHECKLIST, CARD and TEXT items. Groups do not nest.")
            appendLine("Titles are at most ${Contract.MAX_TITLE_CHARS} characters.")
            appendLine()
            appendLine("## Markdown (NOTE and CARD)")
            appendLine("- paragraphs; # to ${"#".repeat(CanvasComposeMarkdown.MAX_HEADING_LEVEL)} headings; > quotes of plain text; ``` fenced code; --- dividers")
            appendLine("- bullets (- item, * item); numbered lists (1. item), which start at 1; tasks (- [ ] to do, - [x] done)")
            appendLine("- inline **bold**, *italic*, `code`, [text](https://url), ~~strike~~; a backslash escapes")
            appendLine("- a list item may hold one nested level of list, indented under it. A note is a flat list of lines, so the child is stored as an indented line: a list item is one paragraph and holds no other block.")
            appendLine("Refused as UNSUPPORTED_MARKDOWN, with the character offset, never shown as plain text instead:")
            appendLine("- raw HTML and `<url>` autolinks: notes do not render HTML; write [text](url).")
            appendLine("- images: a note holds text only.")
            appendLine("- tables, footnotes, reference links, setext (underlined) headings, indented code: outside the subset; use lists, inline links, # headings and fences.")
            appendLine("- a numbered list that starts at another number: the board numbers lists from 1.")
            appendLine("- headings past ${"#".repeat(CanvasComposeMarkdown.MAX_HEADING_LEVEL)}; a heading, quote, fence or divider inside a list item; a block inside a quote; an unclosed fence.")
            appendLine("- a list nested two levels deep is NESTING_TOO_DEEP.")
            appendLine("At most ${CanvasComposeMarkdown.MAX_PROBLEMS} problems are listed per field.")
            appendLine()
            appendLine("## Colours")
            appendLine("color is ${CanvasComposeColors.PRESETS.entries.joinToString { (name, hex) -> "$name ($hex)" }}, or any #rrggbb.")
            appendLine("The presets are the board's note tints, so red shows as pink, cyan as blue and purple as violet. Without a colour a NOTE or CHECKLIST takes the workspace's note colour and a CARD is white (${CanvasComposeColors.CARD_DEFAULT}). TEXT and GROUP take no colour.")
            appendLine()
            appendLine("## Ids and retries")
            appendLine("- Each item is on the board as $piece; a group's label is $piece${CanvasComposeIds.LABEL_SUFFIX}.")
            appendLine("- An item without a key is keyed by its 0-based place: ${CanvasComposeIds.defaultKey(0)}, ${CanvasComposeIds.defaultKey(1)}, and ${CanvasComposeIds.defaultKey(3, 0)} for the first child of the group at ${CanvasComposeIds.defaultKey(3)}.")
            appendLine("- The same artifact_id with the same content again is a safe retry: nothing is written and the receipt comes back with a warning. Where the pieces are does not count, so it is still a retry after a person moved them.")
            appendLine("- The same artifact_id with other content is ARTIFACT_EXISTS.")
            appendLine()
            appendLine("## Receipt")
            appendLine("{\"ok\": true, \"artifact_id\", \"canvas_id\", \"revision\", \"status\": \"published\" | \"dry_run\", \"title\", \"bounds\": {x, y, width, height}, \"items\": [{key, kind, count?, children?}], \"warnings\"}")
            appendLine("bounds is the rectangle the artifact covers, in board units; count is a checklist's entries. The chat shows it as a card with \"Show on canvas\".")
            appendLine()
            appendLine("## What you will see")
            appendLine("The artifact goes right of what is on the board (below it once the board is wider than ${CanvasComposePlacement.WIDE_CONTENT.toInt()}), as a grid in reading order: ${gridRule()}.")
            appendLine("Notes and cards are ${Contract.NOTE_WIDTH.toInt()} wide (heading TEXT ${Contract.TEXT_HEADING_WIDTH.toInt()}) and get a reserved height; the app fits each note to its content inside it and never clips it.")
            appendLine("A person may move or resize a note afterwards; it is then theirs, so do not place it again.")
            appendLine()
            appendLine("## Errors")
            appendLine("A refusal is {\"ok\": false, \"code\", \"problems\": [{\"path\", \"code\", \"message\"}], \"hint\"}; path is a JSON pointer into your request. Fix every problem and send the whole request again.")
            ComposeErrorCode.entries.forEach { appendLine("- ${it.name}: ${meaning(it)}") }
            appendLine("Problem codes, one example each (path: request):")
            PROBLEM_EXAMPLES.forEach { appendLine("- ${it.code.name} at ${it.path}: ${it.shown}") }
            appendLine()
            appendLine("## Example")
            appendLine("```json")
            appendLine(EXAMPLE_REQUEST)
            append("```")
        }
    }

    private fun gridRule(): String {
        val ranges = mutableListOf<String>()
        var from = 1
        for (n in 1..Contract.MAX_ITEMS) {
            val columns = CanvasComposePlacement.columns(n)
            if (n == Contract.MAX_ITEMS || CanvasComposePlacement.columns(n + 1) != columns) {
                val count = if (from == n) "$n item${if (n == 1) "" else "s"}" else if (n == Contract.MAX_ITEMS) "$from or more" else "$from to $n items"
                ranges += "$columns column${if (columns == 1) "" else "s"} for $count"
                from = n + 1
            }
        }
        return ranges.joinToString(", ")
    }

    /** The length bound in a `^[a-z0-9][a-z0-9_-]{0,N}$` pattern: N + 1. */
    private fun String.maxLength(): Int = Regex("""\{0,(\d+)\}""").find(this)!!.groupValues[1].toInt() + 1
}
