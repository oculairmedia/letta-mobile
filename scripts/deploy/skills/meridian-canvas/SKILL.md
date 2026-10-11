---
name: meridian-canvas
description: Read and change this conversation's canvas (notes, checklists, cards, diagrams) with the `meridian canvas` CLI.
---

# meridian canvas

This conversation has a shared canvas the user sees in the Meridian app. Change it from your shell
with the `meridian` CLI. Every command prints JSON on stdout. Input is ONE JSON object on stdin,
passed with a quoted heredoc (`<<'JSON'`) so the shell never touches it. Never put JSON in argv.

```bash
meridian canvas compose <<'JSON'
{"title": "Weekend plan", "items": [{"kind": "NOTE", "markdown": "- Pasta\n- Roast"}]}
JSON
```

## Verbs

| Command | Does |
|---|---|
| `meridian canvas compose [--dry-run] [--canvas ID] <<'JSON'` | Add notes, checklists, cards, text and groups. The board places them. |
| `meridian canvas layout [--limit N] [--cursor C]` | What is on the board, with ids and frames. Read this before you change anything. |
| `meridian canvas scene` | The full drawing (big). Prefer `layout`. |
| `meridian canvas apply-ops [--dry-run] <<'JSON'` | Draw shapes, arrows and images; move, restyle or delete existing elements. |
| `meridian canvas replace-scene [--dry-run] <<'JSON'` | Replace the whole drawing. Rarely right. |
| `meridian canvas list` / `meridian canvas create [--title T]` | The conversation's canvases; make another. |
| `meridian canvas <verb> --help` | Flags and input for one verb. |
| `meridian schema canvas <verb>` | Its JSON input schema. |
| `meridian guide compose` / `guide ops` / `guide scene` | The full reference. Only needed when this page is not enough. |

Leave out `--canvas` to use this conversation's canvas. `meridian --help` lists the other command
groups (agents, agent-message, plugin, tool).

## Compose quick reference

Request: `items` (required, 1 to 24 in reading order, group children counted), `title` (up to 120
characters), `artifact_id` (lowercase letters, digits, `_`, `-`, at most 48), `dry_run`. No
coordinates, sizes or layers. One call makes one artifact, all or nothing. Compose only creates; use
`apply-ops` to change or remove what is on the board.

Kinds (each item may also have a `key`: lowercase letters, digits, `_`, `-`, at most 32, unique):

- `NOTE {markdown, title?, color?}`: markdown up to 4000 characters.
- `CHECKLIST {items: [{text, checked?}], title?, color?}`: 1 to 40 entries of up to 200 characters.
- `CARD {title, fields?: [{label, value}], markdown?, color?}`: up to 8 fields (label 60, value 200), then up to 500 characters of markdown.
- `TEXT {text, size: "heading"|"body"}`: up to 500 characters on the board itself.
- `GROUP {children, label?}`: a labelled frame around NOTE, CHECKLIST, CARD and TEXT. Groups do not nest.

Markdown: paragraphs, `#` to `###` headings, `>` quotes, fenced code, `---`, bullets, numbered lists
starting at 1, tasks (`- [ ]`, `- [x]`), `**bold**`, `*italic*`, `` `code` ``, `[text](https://url)`,
`~~strike~~`, one nested list level. Refused: HTML, images, tables, footnotes, and lists starting at
another number.

Colours: `red`, `orange`, `yellow`, `green`, `cyan`, `purple`, or any `#rrggbb`.

Same `artifact_id` and same content is a harmless retry; same `artifact_id` with other content is
`ARTIFACT_EXISTS`, so pick a new id. A refusal is `{"ok": false, "code", "problems": [{"path",
"code", "message"}]}`, where `path` is a JSON pointer into your request. Fix every problem and send
the whole request again. Use `--dry-run` to check a large request first.

## Exit codes

`0` ok. `2` refused input: fix it using the `{"error": ...}` object and stderr. `3` denied or rate
limited: do not retry in a loop. `4` host unavailable: wait a few seconds and retry once.
