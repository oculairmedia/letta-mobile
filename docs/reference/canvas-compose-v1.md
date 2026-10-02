# canvas.compose v1 reference

`canvas.compose` lets an agent put notes, checklists, cards, text and labelled groups on a canvas by meaning; the board places and sizes them. `canvas.compose_guide` answers the guide below, which an agent reads once before composing. The guide is generated from the contract constants in `CanvasComposeGuide.kt` and this copy is held equal to it by `CanvasComposeGuideDocTest` (sharedLogic jvmTest); when the guide changes, paste `android-compose/sharedLogic/build/canvas-compose-guide.md` (written by that test) between the markers.

For how compose is built (pipeline, ids, receipt, placement, auto-fit) and how to add a kind, see the package README, `android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/canvas/compose/README.md`, and the plan with its "As built" section, `docs/design/canvas-compose-plan.md`.

## The guide (`canvas.compose_guide`)

<!-- canvas.compose_guide:start -->
# canvas.compose (letta.canvas.compose, version 1)

Read this once before composing. You say WHAT goes on the board; the board decides where and how big. A request has no coordinates, sizes or layers.
One call makes one artifact, all or nothing: if anything is wrong, nothing is published and every problem is listed.
Use canvas.apply_ops instead to draw (shapes, arrows, paths, images), to put a note at a frame you choose (set_document with a frame), or to change or remove anything already on the board: compose only creates.

## Request
- items (required): what to make, in reading order; 1 to 24, the children of groups counted.
- artifact_id: names the artifact; lowercase letters, digits, _ and -, at most 48. Without one it is derived from the tool call (a- and 12 hex digits).
- title: names the artifact in the chat, at most 120 characters.
- canvas_id: omit it for the canvas of the conversation you are in.
- dry_run: true runs every check and answers the receipt with status "dry_run"; nothing is published.
- catalog, version: optional; "letta.canvas.compose" and 1. The whole request is at most 64 KiB.

## Kinds
Every item has a "kind" and an optional "key": lowercase letters, digits, _ and -, at most 32, unique in the request.
- NOTE {markdown, title?, color?}: markdown up to 4000 characters.
- CHECKLIST {items: [{text, checked?}], title?, color?}: 1 to 40 entries of up to 200 characters; checked defaults to false.
- CARD {title, fields?: [{label, value}], markdown?, color?}: a titled note: up to 8 fields (label up to 60, value up to 200) shown as bold "label: value" lines, then up to 500 characters of markdown. Not a container.
- TEXT {text, size: heading|body}: up to 500 characters on the board itself, outside any note.
- GROUP {children, label?}: a labelled frame (label up to 60) around NOTE, CHECKLIST, CARD and TEXT items. Groups do not nest.
Titles are at most 120 characters.

## Markdown (NOTE and CARD)
- paragraphs; # to ### headings; > quotes of plain text; ``` fenced code; --- dividers
- bullets (- item, * item); numbered lists (1. item), which start at 1; tasks (- [ ] to do, - [x] done)
- inline **bold**, *italic*, `code`, [text](https://url), ~~strike~~; a backslash escapes
- a list item may hold one nested level of list, indented under it. A note is a flat list of lines, so the child is stored as an indented line: a list item is one paragraph and holds no other block.
Refused as UNSUPPORTED_MARKDOWN, with the character offset, never shown as plain text instead:
- raw HTML and `<url>` autolinks: notes do not render HTML; write [text](url).
- images: a note holds text only.
- tables, footnotes, reference links, setext (underlined) headings, indented code: outside the subset; use lists, inline links, # headings and fences.
- a numbered list that starts at another number: the board numbers lists from 1.
- headings past ###; a heading, quote, fence or divider inside a list item; a block inside a quote; an unclosed fence.
- a list nested two levels deep is NESTING_TOO_DEEP.
At most 10 problems are listed per field.

## Colours
color is red (#fbcfe8), orange (#fed7aa), yellow (#fde68a), green (#bbf7d0), cyan (#bfdbfe), purple (#ddd6fe), or any #rrggbb.
The presets are the board's note tints, so red shows as pink, cyan as blue and purple as violet. Without a colour a NOTE or CHECKLIST takes the workspace's note colour and a CARD is white (#ffffff). TEXT and GROUP take no colour.

## Ids and retries
- Each item is on the board as cmp-<artifact_id>-<key>; a group's label is cmp-<artifact_id>-<key>-label.
- An item without a key is keyed by its 0-based place: i0, i1, and i3-c0 for the first child of the group at i3.
- The same artifact_id with the same content again is a safe retry: nothing is written and the receipt comes back with a warning. Where the pieces are does not count, so it is still a retry after a person moved them.
- The same artifact_id with other content is ARTIFACT_EXISTS.

## Receipt
{"ok": true, "artifact_id", "canvas_id", "revision", "status": "published" | "dry_run", "title", "bounds": {x, y, width, height}, "items": [{key, kind, count?, children?}], "warnings"}
bounds is the rectangle the artifact covers, in board units; count is a checklist's entries. The chat shows it as a card with "Show on canvas".

## What you will see
The artifact goes right of what is on the board (below it once the board is wider than 2400), as a grid in reading order: 1 column for 1 item, 2 columns for 2 to 4 items, 3 columns for 5 to 9 items, 4 columns for 10 or more.
Notes and cards are 320 wide (heading TEXT 480) and get a reserved height; the app fits each note to its content inside it and never clips it.
A person may move or resize a note afterwards; it is then theirs, so do not place it again.

## Errors
A refusal is {"ok": false, "code", "problems": [{"path", "code", "message"}], "hint"}; path is a JSON pointer into your request. Fix every problem and send the whole request again.
- VALIDATION_FAILED: the request breaks the format; the problems below say where.
- UNSUPPORTED_VERSION: another catalog or version, e.g. "version": 2 is refused at /version; send 1 or leave it out.
- ARTIFACT_EXISTS: the artifact_id is on the board with other content (path /artifact_id); compose never changes an artifact, so use a new artifact_id.
- BOARD_REFUSED: the board's own rules refused the batch: each problem's code is the rule (e.g. element.exists, an id already taken) and its path the item; publish.failed means the write did not land, so send the same request again.
- UNAUTHORIZED: you may not write to that canvas.
- CANVAS_NOT_FOUND: no such canvas; omit canvas_id for this conversation's canvas, or pick one from canvas.list.
Problem codes, one example each (path: request):
- UNKNOWN_KIND at /items/0/kind: items: [{"kind":"STICKY","markdown":"x"}]
- UNKNOWN_FIELD at /items/0/x: items: [{"kind":"NOTE","markdown":"x","x":40}]
- MISSING_FIELD at /items/0/title: items: [{"kind":"CARD","fields":[]}]
- TOO_MANY_ITEMS at /items/0/items: a CHECKLIST of 41 entries
- TOO_LONG at /items/0/title: a title of 121 characters
- DUPLICATE_KEY at /items/1/key: items: [{"kind":"NOTE","key":"a","markdown":"x"},{"kind":"NOTE","key":"a","markdown":"y"}]
- BAD_KEY at /items/0/key: items: [{"kind":"NOTE","key":"My note","markdown":"x"}]
- BAD_COLOR at /items/0/color: items: [{"kind":"NOTE","color":"blue","markdown":"x"}]
- UNSUPPORTED_MARKDOWN at /items/0/markdown: items: [{"kind":"NOTE","markdown":"<b>Hi</b>"}]
- NESTING_TOO_DEEP at /items/0/children/0/kind: items: [{"kind":"GROUP","children":[{"kind":"GROUP","children":[]}]}]
- WRONG_TYPE at /items/0/items/0/checked: items: [{"kind":"CHECKLIST","items":[{"text":"x","checked":"yes"}]}]
- BAD_VALUE at /items/0/size: items: [{"kind":"TEXT","text":"x","size":"huge"}]

## Example
```json
{
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
}
```
<!-- canvas.compose_guide:end -->
