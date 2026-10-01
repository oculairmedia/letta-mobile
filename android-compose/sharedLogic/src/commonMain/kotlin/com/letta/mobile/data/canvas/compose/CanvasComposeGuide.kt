package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.compose.CanvasComposeContract as Contract

/**
 * What `canvas.compose_guide` answers: the whole format, kept out of the `canvas.compose`
 * description so that one stays short. Built from [CanvasComposeContract], so a cap or a
 * vocabulary changes here when it changes there. C1 lands the structure and the example
 * (letta-mobile-bglj6.6); the wording is finalised with the end-to-end gate (C9).
 */
object CanvasComposeGuide {
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

    val text: String by lazy {
        buildString {
            appendLine("# ${CanvasToolContract.COMPOSE} (${Contract.CATALOG}, version ${Contract.VERSION})")
            appendLine()
            appendLine("Say what goes on the board; the board decides where and how big. There are no coordinates.")
            appendLine("One call makes one artifact. It is all or nothing: if anything is wrong, nothing is published.")
            appendLine()
            appendLine("## Request")
            appendLine("- items (required): the things to make, in reading order.")
            appendLine("- artifact_id: lowercase letters, digits, _ and -, at most 48. The same id and content again is a safe retry; the same id with other content is ARTIFACT_EXISTS.")
            appendLine("- title: names the artifact in the chat.")
            appendLine("- canvas_id: omit it for the canvas of the conversation you are in.")
            appendLine("- dry_run: true to get the receipt without publishing.")
            appendLine("- catalog, version: optional; \"${Contract.CATALOG}\" and ${Contract.VERSION}.")
            appendLine()
            appendLine("## Kinds")
            appendLine("Every item has a \"kind\" and an optional \"key\" (lowercase letters, digits, _ and -, at most 32; unique in the request).")
            appendLine("- NOTE {markdown, title?, color?}: a note.")
            appendLine("- CHECKLIST {items: [{text, checked?}], title?, color?}: a note of to-dos.")
            appendLine("- CARD {title, fields?: [{label, value}], markdown?, color?}: a titled note with label/value fields and a short body. Not a container.")
            appendLine("- TEXT {text, size: heading|body}: text on the board itself.")
            appendLine("- GROUP {children, label?}: a labelled frame around NOTE, CHECKLIST, CARD and TEXT items. Groups do not nest.")
            appendLine()
            appendLine("## Caps")
            appendLine("- ${Contract.MAX_ITEMS} items per request, the children of groups counted.")
            appendLine("- ${Contract.MAX_CHECKLIST_ITEMS} entries per CHECKLIST, ${Contract.MAX_CARD_FIELDS} fields per CARD.")
            appendLine("- ${Contract.MAX_MARKDOWN_CHARS} characters of NOTE markdown, ${Contract.MAX_CARD_BODY_CHARS} of CARD markdown, ${Contract.MAX_TEXT_CHARS} of TEXT.")
            appendLine("- ${Contract.MAX_TITLE_CHARS} characters per title, ${Contract.MAX_LABEL_CHARS} per label, ${Contract.MAX_VALUE_CHARS} per value or checklist entry.")
            appendLine("- ${Contract.MAX_REQUEST_BYTES / 1024} KiB per request.")
            appendLine()
            appendLine("## Markdown")
            appendLine("Paragraphs, # to ### headings, - or * bullets, 1. numbered lists, - [ ] and - [x] tasks, > quotes, fenced code, --- dividers;")
            appendLine("inline **bold**, *italic*, `code`, [text](url) and ~~strike~~. Lists nest one level.")
            appendLine("Not allowed: raw HTML, images, tables, deeper nesting, reference links, footnotes.")
            appendLine()
            appendLine("## Colours")
            appendLine("${Contract.COLOR_PRESETS.joinToString()}, or #rrggbb.")
            appendLine()
            appendLine("## Errors")
            appendLine("A refusal is {\"ok\": false, \"code\", \"problems\": [{\"path\", \"code\", \"message\"}], \"hint\"}; each path is a JSON pointer into your request.")
            appendLine("Codes: ${ComposeErrorCode.entries.joinToString { it.name }}.")
            appendLine("Problem codes: ${ComposeProblemCode.entries.joinToString { it.name }}; a BOARD_REFUSED problem carries the board rule it broke.")
            appendLine()
            appendLine("## Example")
            appendLine("```json")
            appendLine(EXAMPLE_REQUEST)
            append("```")
        }
    }
}
