# canvas.compose: validated implementation plan (v1)

Date: 2026-10-01. Status: IMPLEMENTED on `feat/canvas-compose`, see section 9 "As built" for what differs from this plan and the follow-ups. Originally: APPROVED PLAN, implementation authorized through the research -> validation -> Opus-implementation pipeline Emmanuel set up. Supersedes the "PLAN ONLY" sections of `letta-mobile-bglj6` for E0-E4 (the canvas.compose parts); the shared chat page (E5, `letta-mobile-bglj6.1`) is already in flight on PR #1724 and is unaffected.

Inputs: `bd show letta-mobile-bglj6` (epic, architecture review of 2026-09-25, delivery order E0-E7), `letta-mobile-bglj6.1`, `letta-mobile-4i2z9.20` (persistence decision), `letta-mobile-8tlf9` (note frames never sized by content), `letta-mobile-le0z3` (closed, PR #1690), `letta-mobile-817ao`, `letta-mobile-99428`, `letta-mobile-dg8f8` (dotted tool names), `docs/design/canvas-compose-prior-art.md`, and the code on `origin/feat/shared-chat-page` (PR #1724) plus `origin/fix/canvas-automerge-oom` (PR #1727) for storage. Every file reference below was read on those refs.

Standing rules this plan obeys (Emmanuel): feature logic in `sharedLogic/commonMain`, shared UI in `sharedUI`; platform modules only bind; no second validator (compiled batches pass `CanvasSceneValidator` via `CanvasBatchValidator`); one op log, history kept but bounded; storage failures non-fatal but loud; no new Gradle module unless a real cycle needs one; canvas.compose is NOT an A2UI catalog; tests for everything; `bd remember no-compat-hacks` (change schemas properly, do not hide meaning in existing fields).

---

## 1. What changed versus the existing plan, and why

| Existing plan (bglj6 review, 2026-09-25) | This plan | Why (prior art / code) |
|---|---|---|
| "Compose compiled into the existing op log" with an unnamed vocabulary | A named, versioned catalog: `catalog: "letta.canvas.compose"`, `version: 1`, stamped on every request, receipt and produced document | A2UI `catalogId` + `version` on every message; Adaptive Cards `version`; the prior-art pitfall "pinning to a moving spec" (section 2.2, 5). |
| Errors "with the reason" (free text, as `apply_ops` does today) | Structured refusal: `{code, problems:[{path, code, message}]}` with JSON-pointer paths, inside `ExternalToolResult.Error` | A2UI `VALIDATION_FAILED` + `path` lets the model self-repair (prior art 2.2). Today's canvas refusals are plain text (`CanvasBatchValidator.refusal`, `CanvasSceneSchemaText.problems`); a structured list exists only for `dry_run`. |
| Model never emits coordinates (already decided) | Confirmed, and tightened: the request has no geometry fields at all, not even an escape hatch; explicit geometry stays with `apply_ops set_document {frame}` | tldraw, Miro grid tools, Excalidraw text-to-diagram all keep the model out of x/y (prior art 2.1, 2.3). Two write paths with different ownership markers is cleaner than one path with optional geometry. |
| "Frameless content sized by renderer-side auto-fit"; infer ownership from `frame == null` | Persist the sizing MODE (`owner: AUTO/EXPLICIT/USER`) and a placement with a RESERVED height; the renderer measures and fits WITHIN the reservation and never writes geometry back | OOXML/Slides/tldraw/Excalidraw store a mode, never a measured scale; python-pptx proves a generator without font metrics cannot be exact; Android and Skiko metrics differ, so measured height must stay local (prior art 2.3, takeaways 4, 5). Also prevents an op per render (history growth). |
| Single receipt as "typed artifact pointers attached to the same canonical assistant timeline event", implying a new persisted field | The receipt IS the compose tool's return body (already persisted on the TOOL_CALL event as `toolReturnContentByCallId`); a deterministic projection rule attaches a `canvas_artifact` part to the narrating ASSISTANT message. No new persisted timeline field, no transport change | Vercel `data-*` parts reconcile by id; MCP `resource_link` in the tool result (prior art 2.2). Code: `TimelineEvent.Confirmed` has no parts field; tool call + return are one TOOL_CALL event, narration is a sibling ASSISTANT event sharing `runId`/`stepId` (`TimelineStreamReducer.kt:268`, `TimelineExactCanonicalWriter.mergeToolReturn`). Deriving the part from persisted data is durable by construction and survives replay/hydration on every device. |
| Markdown as the note contract (decided) | Confirmed, and constrained to a strict subset; CHECKLIST items are structured on the wire and compile to Cascade `todo` blocks; the Cascade JSON is built by our own codec in sharedLogic | JSON Canvas text nodes are markdown; Obsidian Tasks shows inline metadata is fragile (prior art 2.4). Code: `cascade-editor` is a dependency of `sharedUI` only (`sharedUI/build.gradle.kts:90`); there is no markdown codec in the tree, and the Iroh host runs sharedLogic without Compose. |
| JSON Canvas 1.0 as "board structure" format (4i2z9.20 table) | JSON Canvas is IMPORT/EXPORT only (the old-prototype compatibility commitment, per Emmanuel's scope clarification on 4i2z9.20); the live model is the op log plus the notebook's Automerge maps with JSON-Canvas field names where they exist (`x, y, width, height, color, text, label`) and namespaced extensions (`owner`, `compose`) | JSON Canvas has no sizing mode, owner, per-node version or unknown-field rule, and array order as z-order does not merge (prior art takeaway 7, 9). |
| 8tlf9 open as a separate gate | Absorbed into the auto-fit renderer bead (C6); its acceptance criteria are carried over verbatim | Same measurement contract; two beads would re-decide it. |
| v1 kinds NOTE, CHECKLIST, CARD, TEXT, GROUP | Same five, with CARD pinned down as "titled note with at most 8 label/value fields and an optional short body" | Prior art: CARD ambiguity is the likeliest source of model confusion (section 5). |
| Per-call item cap unspecified | 24 items per call (GROUP children count), 40 items per CHECKLIST, 4 000 chars of markdown per item, 64 KiB request | Miro bulk 20 / Slack 50 (prior art 2.2); our receipt must fit a tool return that the timeline may truncate (`toolReturnTruncationByCallId`). |
| Tool names `canvas.compose` | `canvas_compose` and `canvas_compose_guide`, following the provider-safe rename on PR #1720 (`letta-mobile-dg8f8`) | Anthropic-compatible providers reject dotted names. Beads reference `CanvasToolContract` constants, so they follow whichever naming is on main when they land. |

New finding that is NOT part of compose but affects it (flagged for Emmanuel, section 8): PR #1727's "edit one note, rewrite one note" saving does not apply to notes. `CanvasOpProjector` writes `_documents` as a JSON ARRAY of entries (`CanvasOpProjector.kt` `writeDocument`), while `NotebookBoardStorage.writeEnvelope` only splits scene fields whose value is a `JsonObject` into `boardFields`; the oom-branch tests use the object form `"_documents":{"n1":{...}}`. So every note move rewrites the whole `_documents` array into Automerge history. Filed as a follow-up bead (C10) because compose will create many notes.

---

## 2. Resolved decisions

| Id | Decision | Rationale |
|---|---|---|
| D2 | A NEW semantic member of the EXISTING `CanvasToolContract` (`canvas_compose` + `canvas_compose_guide`), compiled into the EXISTING `CanvasOp` log and published through the EXISTING `HostCanvasBackend.publish` / `CanvasSession` paths. Not a new tool family, registry or document store. | Already approved in the 2026-09-25 review; confirmed by the Excalidraw skeleton pattern and Miro's single composer vocabulary. Keeps one op log and one validator. |
| D3 | The compose layer owns placement and sizing MODE; the renderer owns measurement. Placement is a pure function of (request, current scene) in sharedLogic, with no font/density APIs. Each produced document gets an explicit frame (x, y, width fixed per kind, RESERVED height from a conservative estimator) and `owner: AUTO`. The renderer fits content within the reservation (grow to content, never beyond the reservation; overflow scrolls inside the card with an indicator) and never writes geometry back. A human move/resize flips `owner` to `USER`; `apply_ops set_document` with a frame is `EXPLICIT`; a legacy frameless document is laid out by the same placement engine at render time (deterministic over the scene, so peers agree). | Prior art takeaways 2-5, 8tlf9's acceptance criteria, and the 99428 lesson: a measurement that writes back would be an op per render and a silent LWW loser. |
| D4 | The receipt is a data-model rule implemented as a projection: the compose tool return is the receipt (persisted on the TOOL_CALL event); `CanvasArtifactReceipts.attach` (sharedLogic, pure) attaches a `canvas_artifact` part to the narrating ASSISTANT message of the same run (first ASSISTANT text event after the call in the run; else the last one before it in the same step; else the TOOL_CALL event itself, so nothing is ever lost). Same `artifactId` re-sent reconciles in place; status is `pending` while the call is in flight, `published` or `failed` from the return. Message identity is the enclosing event's `serverId`; the receipt carries no message id. | Code: no `parts` field exists and adding one duplicates derivable data; Vercel/A2UI reconcile-by-id precedent. |
| D5 | 4i2z9.20 lands AS AMENDED (section 6) and does not gate compose further: markdown is the agent-facing note contract and the at-rest `.md` projection (4i2z9.20.2); Cascade v2 JSON is the live stored/rendered form, produced by our own codec; JSON Canvas is import/export only. The decision bead is closed with this resolution; its children (.20.1-.20.5, the vault projection) stay open and are not on compose's critical path. | Emmanuel's scope clarification on .20 ("JSON Canvas 1.0 board compatibility binds only OLD prototype; new notebook can define a separately versioned board format"), prior art section 5 note. |
| CARD | Shipped in v1 as "titled note with fields": `title` required, `fields` (<= 8 `{label, value}`), optional `markdown` body (<= 500 chars). Compiles to a document: heading(2) + fields as paragraphs with a bold label span + body blocks. | Removes the container/record ambiguity the prior art warns about; GROUP is the only container. |
| Receipt shape | One part per compose call: `{artifactId, canvasId, revision, status, title, kinds, itemCount, bounds, error?}`. Layout detail stays out of the model's context and the chat card. | Apps SDK `structuredContent` vs `_meta` split. |
| Ids | `artifactId` = request `artifact_id` if given (idempotency key, `^[a-z0-9][a-z0-9_-]{0,47}$`), else host-derived from the tool call id (`a-` + first 12 hex of sha256(toolCallId)), else random. Element/document id = `cmp-<artifactId>-<key>`; `key` per item defaults to its path ordinal (`i1`, `i1-c2`). A second compose with the same `artifactId`: identical compiled content -> `ok` with the stored receipt (idempotent retry); different content -> `ARTIFACT_EXISTS`. | Prior art: never trust model-minted ids, make retries idempotent, never reuse ids after delete. |
| Guide | Full format reference behind a no-argument tool `canvas_compose_guide` (Excalidraw `read_me`, Miro composer skill). The `canvas_compose` description stays under ~700 characters and points at the guide and the error paths. The Kotlin constant is the source of truth; `docs/reference/canvas-compose-v1.md` is a copy a jvmTest keeps equal. | Prior art 2.6. |
| Not in v1 | Update/delete of compose artifacts; edges/connectors; images; raw HTML; font shrink-to-fit (notes grow, they do not shrink); fractional-index z-order (documents have no z-order; TEXT/GROUP elements use `zIndex` LWW as today); per-property LWW inside a document entry. | Create-only v1 as approved. Fractional indexing and per-property document LWW are recorded as follow-ups (C11) and become necessary only with v2 update. |

Open for Emmanuel (section 8) are three product/operational questions, each with a recommendation. Nothing in the bead list blocks on them.

---

## 3. Architecture

### 3.1 Contracts (sharedLogic/commonMain, package `com.letta.mobile.data.canvas.compose`)

- `CanvasComposeContract`: constants `CATALOG = "letta.canvas.compose"`, `VERSION = 1`, caps, per-kind widths; `ComposeRequest`, `ComposeItem` (sealed by `kind`: `Note`, `Checklist`, `Card`, `Text`, `Group`), `ComposeReceipt`, `ComposeRefusal` (`code`, `problems: List<ComposeProblem(path, code, message)>`, `hint`), all `@Serializable` with `SerialName`s matching the wire (snake_case keys as the other canvas DTOs use).
- `CanvasToolContract.compose` and `CanvasToolContract.composeGuide` (`CanvasToolDefinition`s; names via constants `COMPOSE`, `COMPOSE_GUIDE`, following the `canvas_*` naming), both in `CanvasToolContract.all`. The compose input schema is a strict JSON schema (`additionalProperties: false` at every level, `kind` enum, caps as `maxItems`/`maxLength`) so validation errors have paths that match the schema the model saw.
- Scene-model additions (package `com.letta.mobile.data.canvas`): `enum class CanvasGeometryOwner { AUTO, EXPLICIT, USER }`; `CanvasSceneDocument.owner: CanvasGeometryOwner?` and `.compose: CanvasComposeProvenance?` (`artifactId, key, kind, catalog, version`); `CanvasOp.SetDocumentOp.owner` and `.compose` (null keeps existing, as the other optional fields do); `CanvasOpProjector.keptFields` carries both; `_documents` entry keys `"owner"` and `"compose"`. Whole-document LWW is unchanged.

### 3.2 Data model and persistence

- Live model: unchanged op log (`CanvasOp`, `CanvasOpProjector` LWW per element / per document) and the notebook Automerge document (`NotebookBoardStorage`, layout version 2). Compose adds two namespaced keys to a document entry and nothing to the envelope. Field names stay JSON-Canvas-compatible where the concept exists (`frame.{x,y,width,height}`, `color`, `title`), so the export in 4i2z9.20.2 is a rename-free projection.
- Content: markdown on the wire; Cascade v2 JSON in the document (`{"version":2,"blocks":[{"id","type":{"typeId":...,...},"content":{"kind":"text","version":1,"text","spans":[...]},"children":[...]}]}`). Supported block typeIds (from `cascade-editor` 1.9.2): `paragraph`, `heading` (level), `bullet_list`, `numbered_list` (number), `todo` (checked), `quote`, `code`, `divider`; spans `bold`, `italic`, `inline_code`, `link`, `strike_through` (exact span/property key names are taken from the library's `BlockTypeCodec`/`BlockContentCodec` by the round-trip oracle test in C3, not guessed).
- Colours: the request accepts the JSON Canvas preset names `red, orange, yellow, green, cyan, purple` or `#rrggbb`; the compiler maps presets to the workspace's note palette (`CanvasColorPicker` presets) and stores `#rrggbb` in `color`.
- Export/import (JSON Canvas 1.0) is 4i2z9.20.2/.20.3 work and is out of scope here; this plan only guarantees the mapping exists: NOTE/CARD -> `text` node, CHECKLIST -> `text` node with `- [ ]` lines, TEXT -> `text` node, GROUP -> `group` node with `label`; on import, imported geometry gets `owner: EXPLICIT` (never AUTO).

### 3.3 Compile pipeline (sharedLogic, pure, host-independent)

```
request JSON
  -> decode + schema validation (paths)            VALIDATION_FAILED
  -> ids: artifactId, per-item keys (unique)       VALIDATION_FAILED (/items/i/key)
  -> idempotency: existing artifact on the scene?  ok (same digest) | ARTIFACT_EXISTS
  -> content: markdown subset -> Cascade blocks    VALIDATION_FAILED (/items/i/markdown, offset in message)
  -> placement: slots (x, y, width, reserved h)    (never fails; caps already enforced)
  -> ops: set_document x N, add_element (TEXT, GROUP rect + label)
  -> CanvasBatchValidator.check(sceneJson, ops)    BOARD_REFUSED (its violations as problems)
  -> publish via the host's existing path          (host: HostCanvasBackend.publish; app: CanvasSession)
  -> receipt
```

All-or-nothing: any problem anywhere refuses the whole request and publishes nothing. `dry_run: true` runs everything but publish and returns the receipt with `status: "dry_run"` plus any problems (same shape as the published receipt, so the model can preview bounds).

Markdown subset (v1): CommonMark paragraphs, ATX headings 1-3, `-`/`*` bullet lists, `1.` numbered lists, GFM task items `- [ ]`/`- [x]` (inside NOTE they become `todo` blocks too), `>` quotes, fenced code, `---` dividers, inline `**bold**`, `*italic*`, `` `code` ``, `[text](url)`, `~~strike~~`. One nesting level for lists (Cascade `children`). Rejected with a path and a character offset: raw HTML, images, tables, deeper nesting, reference links, footnotes. Text content is escaped as-is; nothing is interpreted beyond the subset.

### 3.4 Placement and auto-fit

Placement (`CanvasComposePlacement`, sharedLogic):

- Inputs: the compiled items, and the current scene's content bounds (`elements` bounds via the same geometry `CanvasViewportFit.contentBounds` uses, plus framed documents). No viewport (the host has none).
- Artifact origin: an empty board starts at (80, 80). Otherwise the artifact is placed to the RIGHT of the content bounds with a 48 gap, top-aligned, unless the content is wider than 2 400 world units, in which case BELOW with a 48 gap, left-aligned. Deterministic: same scene + same request = same frames.
- Flow inside an artifact: row-major grid, columns = 1 for 1 item, 2 for 2-4, 3 for 5-9, 4 for 10+; column width = max kind width in the artifact (NOTE/CHECKLIST/CARD 320, TEXT 480 heading / 320 body); gap 24; row height = max reserved height in the row. GROUP: a RECTANGLE Shape (zIndex 0, light fill, cornerRadius 16) enclosing its children's grid with padding 24 and a 32-high label row (a Text element, fontSize 18) at the top-left; children flow with the same rule; the group's slot in the outer grid is its enclosing rect.
- Reserved height estimator (pure, conservative): per block, `lines = ceil(chars / charsPerLine)` with `charsPerLine = floor((width - 2*padding) / (0.6 * fontSize))`, `height += lines * 1.5 * fontSize + 8`; fontSize 22 for headings, 16 for body/todo/list (todo and list items lose 3 chars for the marker); code 14 mono at 0.65; plus 32 handle bar and 24 padding; times 1.15; clamped to [120, 1 200]. TEXT uses DrawBox's `wrapWidth` with the same line estimate. The estimator is tuned by a desktop test that measures real rendered heights for a fixture set and asserts `estimate >= measured` for every fixture on JVM (and in the Android Robolectric render test).

Units (letta-mobile-bglj6.17, Emmanuel's decision: "everything should use canvas units so it proportional"): everything ON the board is sized in world units, one px before the board's zoom. Note cards are composed under `CanvasWorldDensity` (density 1, font scale 1), so a 16-unit font is 16 world units on a phone at density 2.75 exactly as on a desktop, with no system font-scale dependence; DrawBox lays out text elements, shape text and group labels under the same `WorldDensity`. The estimator books world units and the renderer draws world units, so they agree on every display (a Robolectric xxhdpi gate, `CanvasNoteAutoFitAndroidRenderTest`, holds this on Android). Board chrome (toolbars, menus, selection handles, presence) stays in dp/sp and honours accessibility font scaling. The zoom is a graphics-layer scale read in layout/draw only: a pan or zoom recomposes no card and remeasures no text.

Auto-fit (sharedUI `CanvasNotesLayer.CanvasNoteCard`):

- `owner == AUTO` (or compose provenance present and owner null): measure the rendered body (SubcomposeLayout over `CanvasBlockPreview`, like `VerticallyCentred` does) and set card height = `min(measured + chrome, frame.height)`; width = `frame.width`; if `measured + chrome > frame.height`, the body scrolls inside the card and an overflow indicator shows. No write-back. The local height is remembered per document id so there is no visible pop while measuring (first frame renders at the reserved height, then shrinks once).
- `owner == USER` or `EXPLICIT`: the stored frame verbatim (today's behaviour), scroll inside when too small.
- `frame == null` (legacy frameless): width 320, height measured and capped at 1 200, position from `CanvasComposePlacement.placeFrameless(documents, contentBounds)` (same engine, deterministic over the scene) instead of `defaultNoteFrame(index)`; two frameless notes never overlap.
- A human move/resize commits through `CanvasSession.moveDocument`/`moveDocuments` with `owner = USER`; after that the card never auto-fits again. Resizing below content height is allowed (scroll), as 8tlf9 requires.

### 3.5 Receipt and provenance

- Wire: the compose tool return (section 5.3). Canvas side: each produced document carries `compose: {artifactId, key, kind, catalog, version}`; TEXT/GROUP elements carry the same under an element field `compose` (DrawBox ignores unknown keys only after `stripMetadataForDrawBox`; C2 confirms `_`-prefixed vs plain field handling and uses `_compose` on elements if the decoder is strict).
- Chat side: `CanvasArtifactReceipt` (sharedLogic `data/chat/projection`), produced by `CanvasArtifactReceipts.attach(events)` and placed on `UiMessage.artifacts: List<CanvasArtifactReceipt>` (new field; both `Local` and `Confirmed` projection branches; `ChatRenderModelBuilder` predicates `isTurnLatencyTarget`, `isPlainAssistantTextEchoOf`, `ChatTimelineProjector.toolCardCount` / `isEmptyAfterA2uiExtraction` and `RunBlock` `isPlainAssistantStep` updated). The row is `ArtifactCard` in `sharedUI/.../ui/chat/surface/timeline/rows/ChatRowArtifacts.kt`, rendered in `AssistantMessageColumn` beside `GeneratedUiCard`, with a "Show on canvas" action that calls `ChatActions.openCanvas` and a focus request on the receipt bounds (`CanvasFocusRequest`).
- Truncated returns: if the stored return is truncated or unparsable, the part degrades to `{status: published|failed, title: tool name}` with no bounds, never to nothing.

### 3.6 Agent-facing tool surface

- `canvas_compose {catalog?, version?, canvas_id?, artifact_id?, title?, items[], dry_run?}` -> receipt JSON or structured refusal.
- `canvas_compose_guide {}` -> the markdown guide (kinds, fields, caps, markdown subset, colours, examples, error codes, "what happens on the board").
- `canvas_apply_ops set_document` keeps working and now accepts `owner` (optional) for explicit geometry; its description gains one sentence pointing agents that want notes/checklists at `canvas_compose`.
- Registration: `HostCanvasTools.all` (Iroh host, `AppServerServeIrohCommand.startCanvasRelay`) and `CanvasExternalTools.all` (desktop direct App Server, `DesktopAppServerChatGatewayBuilder`). Android registers no canvas tools today (`AppModule.kt:124` registers only `DeviceActionExternalTool`); on Iroh the host answers. `ExternalToolCaller` gains `toolCallId: String? = null`, passed by `ExternalToolDispatcher`, so the host can derive `artifactId`.

### 3.7 Versioning and errors

- Request `version` defaults to 1; any other value -> `UNSUPPORTED_VERSION` naming the supported versions. Unknown `kind` -> `VALIDATION_FAILED` at `/items/i/kind` with the kind list (future: a `fallback` kind as Adaptive Cards does).
- Error codes: `VALIDATION_FAILED`, `UNSUPPORTED_VERSION`, `ARTIFACT_EXISTS`, `BOARD_REFUSED`, `UNAUTHORIZED`, `CANVAS_NOT_FOUND`. Problem codes inside `VALIDATION_FAILED`: `UNKNOWN_KIND`, `UNKNOWN_FIELD`, `MISSING_FIELD`, `TOO_MANY_ITEMS`, `TOO_LONG`, `DUPLICATE_KEY`, `BAD_KEY`, `BAD_COLOR`, `UNSUPPORTED_MARKDOWN`, `NESTING_TOO_DEEP`.
- Scene-model change (`owner`, `compose` on `_documents` entries) needs the Iroh host wrapper rebuilt, exactly as `letta-mobile-dpen4` needed for `title`; an old host drops the fields when it rewrites a note. Record in the wiring bead's acceptance.

---

## 4. Risks

1. Cascade JSON drift: the codec hand-builds what `cascade-editor` 1.9.2 decodes. Mitigation: the oracle test in C3 round-trips through the library (`loadFromJson`/`toJson`, available in `desktop` tests) and fails on any property-name mismatch; the library version is pinned.
2. Estimator too small on one platform: text would scroll inside its card. Mitigation: the measured-vs-estimated gate in C6 on JVM and Android; the 1.15 factor and clamps are tunable constants in one place.
3. Tool return truncation hides the receipt: mitigated by the compact receipt (24 items max, no layout detail) and the degrade rule in 3.5; C8 asserts the receipt size stays under the truncation threshold it finds in `TimelineExactCanonicalWriter`.
4. Host redeploy: new fields and tools do nothing until the Iroh wrapper is rebuilt; an old wrapper's `apply_ops` path rewrites notes without `owner`. Flagged in section 8.
5. `_documents` stored whole (section 1 finding): many compose notes plus human moves grow Automerge history faster than PR #1727 intends. Follow-up C10; storage faults stay non-fatal and visible (`CanvasStorageFaultBanner`).
6. App path (desktop direct App Server) never stamped `opId`/`lamport` and skipped `CanvasBatchValidator` on real writes (`CanvasExternalTools.executeApplyOps`). Compose uses one shared `CanvasComposeService` on both paths, so parity is by construction; the `apply_ops` gap is noted on the wiring bead as a one-line fix if cheap, else a follow-up.
7. Two placements racing (two agents composing at once on one board): both read the same content bounds and overlap. Accepted for v1 (one agent per conversation canvas); the receipt bounds make the overlap visible.

---

## 5. Fixtures: the v1 wire contract (serialized examples)

The files under `sharedLogic/src/commonTest/resources/canvas/compose/v1/` are the source of truth once C1 lands; these are their intended contents.

### 5.1 Request (`canvas_compose` input)

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

### 5.2 Compiled op batch (what `CanvasBatchValidator.check` receives; `opId`/`actorId`/`lamport` are stamped by the host after this)

```json
[
  { "type": "add_element", "elementId": "cmp-weekend-plan-heading",
    "elementJson": "{\"type\":\"Text\",\"text\":\"Weekend plan\",\"textTopLeft\":\"80.0,80.0\",\"wrapWidth\":480.0,\"fontSize\":36.0,\"alignment\":\"LEFT\",\"fontFamilyKey\":\"sans\",\"strokeColor\":\"#212121ff\",\"zIndex\":2,\"_compose\":{\"artifactId\":\"weekend-plan\",\"key\":\"heading\",\"kind\":\"TEXT\",\"catalog\":\"letta.canvas.compose\",\"version\":1}}" },
  { "type": "set_document", "documentId": "cmp-weekend-plan-shopping",
    "documentJson": "{\"version\":2,\"blocks\":[{\"id\":\"b1\",\"type\":{\"typeId\":\"todo\",\"checked\":false},\"content\":{\"kind\":\"text\",\"version\":1,\"text\":\"Milk\",\"spans\":[]}},{\"id\":\"b2\",\"type\":{\"typeId\":\"todo\",\"checked\":true},\"content\":{\"kind\":\"text\",\"version\":1,\"text\":\"Eggs\",\"spans\":[]}},{\"id\":\"b3\",\"type\":{\"typeId\":\"todo\",\"checked\":false},\"content\":{\"kind\":\"text\",\"version\":1,\"text\":\"Bread\",\"spans\":[]}}]}",
    "frame": { "x": 80.0, "y": 152.0, "width": 320.0, "height": 236.0 },
    "color": "#fff59d", "title": "Shopping", "owner": "AUTO",
    "compose": { "artifactId": "weekend-plan", "key": "shopping", "kind": "CHECKLIST", "catalog": "letta.canvas.compose", "version": 1 } },
  { "type": "set_document", "documentId": "cmp-weekend-plan-meals",
    "documentJson": "{\"version\":2,\"blocks\":[{\"id\":\"b1\",\"type\":{\"typeId\":\"heading\",\"level\":2},\"content\":{\"kind\":\"text\",\"version\":1,\"text\":\"Saturday\",\"spans\":[]}},{\"id\":\"b2\",\"type\":{\"typeId\":\"bullet_list\"},\"content\":{\"kind\":\"text\",\"version\":1,\"text\":\"Pasta\",\"spans\":[]}},{\"id\":\"b3\",\"type\":{\"typeId\":\"heading\",\"level\":2},\"content\":{\"kind\":\"text\",\"version\":1,\"text\":\"Sunday\",\"spans\":[]}},{\"id\":\"b4\",\"type\":{\"typeId\":\"bullet_list\"},\"content\":{\"kind\":\"text\",\"version\":1,\"text\":\"Roast\",\"spans\":[]}},{\"id\":\"b5\",\"type\":{\"typeId\":\"todo\",\"checked\":false},\"content\":{\"kind\":\"text\",\"version\":1,\"text\":\"Buy a chicken\",\"spans\":[]}}]}",
    "frame": { "x": 424.0, "y": 152.0, "width": 320.0, "height": 318.0 },
    "color": "#c8e6c9", "title": "Meals", "owner": "AUTO",
    "compose": { "artifactId": "weekend-plan", "key": "meals", "kind": "NOTE", "catalog": "letta.canvas.compose", "version": 1 } },
  { "type": "add_element", "elementId": "cmp-weekend-plan-selfcare",
    "elementJson": "{\"type\":\"Shape\",\"shapeType\":\"RECTANGLE\",\"points\":[\"80.0,494.0\",\"792.0,826.0\"],\"strokeColor\":\"#9e9e9eff\",\"strokeWidth\":1.0,\"fillColor\":\"#f5f5f5ff\",\"cornerRadius\":16.0,\"zIndex\":0,\"_compose\":{\"artifactId\":\"weekend-plan\",\"key\":\"selfcare\",\"kind\":\"GROUP\",\"catalog\":\"letta.canvas.compose\",\"version\":1}}" },
  { "type": "add_element", "elementId": "cmp-weekend-plan-selfcare-label",
    "elementJson": "{\"type\":\"Text\",\"text\":\"Self-care\",\"textTopLeft\":\"104.0,510.0\",\"wrapWidth\":664.0,\"fontSize\":18.0,\"alignment\":\"LEFT\",\"fontFamilyKey\":\"sans\",\"strokeColor\":\"#424242ff\",\"zIndex\":1,\"_compose\":{\"artifactId\":\"weekend-plan\",\"key\":\"selfcare\",\"kind\":\"GROUP\",\"catalog\":\"letta.canvas.compose\",\"version\":1}}" },
  { "type": "set_document", "documentId": "cmp-weekend-plan-walk",
    "documentJson": "{\"version\":2,\"blocks\":[{\"id\":\"b1\",\"type\":{\"typeId\":\"heading\",\"level\":2},\"content\":{\"kind\":\"text\",\"version\":1,\"text\":\"Walk\",\"spans\":[]}},{\"id\":\"b2\",\"type\":{\"typeId\":\"paragraph\"},\"content\":{\"kind\":\"text\",\"version\":1,\"text\":\"When: Sat 09:00\",\"spans\":[{\"style\":\"bold\",\"start\":0,\"end\":5}]}},{\"id\":\"b3\",\"type\":{\"typeId\":\"paragraph\"},\"content\":{\"kind\":\"text\",\"version\":1,\"text\":\"Where: Park\",\"spans\":[{\"style\":\"bold\",\"start\":0,\"end\":6}]}}]}",
    "frame": { "x": 104.0, "y": 550.0, "width": 320.0, "height": 236.0 },
    "color": "#ffffff", "title": "Walk", "owner": "AUTO",
    "compose": { "artifactId": "weekend-plan", "key": "walk", "kind": "CARD", "catalog": "letta.canvas.compose", "version": 1 } },
  { "type": "set_document", "documentId": "cmp-weekend-plan-read",
    "documentJson": "{\"version\":2,\"blocks\":[{\"id\":\"b1\",\"type\":{\"typeId\":\"paragraph\"},\"content\":{\"kind\":\"text\",\"version\":1,\"text\":\"Finish Dune before Sunday.\",\"spans\":[{\"style\":\"italic\",\"start\":7,\"end\":11}]}}]}",
    "frame": { "x": 448.0, "y": 550.0, "width": 320.0, "height": 160.0 },
    "color": "#fff59d", "owner": "AUTO",
    "compose": { "artifactId": "weekend-plan", "key": "read", "kind": "NOTE", "catalog": "letta.canvas.compose", "version": 1 } }
]
```

Numbers are illustrative of the rules in 3.4 (origin 80,80; TEXT heading row 72 high; gap 24; group padding 24 and label row 32); the golden fixture written by C4/C5 is authoritative. Span `start`/`end` and the `todo`/`heading` property keys are confirmed by the C3 oracle.

### 5.3 Receipt (the `canvas_compose` return; also the source of the chat part)

```json
{
  "ok": true,
  "catalog": "letta.canvas.compose",
  "version": 1,
  "artifact_id": "weekend-plan",
  "canvas_id": "canvas-conversation-conv-123",
  "revision": 42,
  "status": "published",
  "title": "Weekend plan",
  "bounds": { "x": 80.0, "y": 80.0, "width": 712.0, "height": 746.0 },
  "items": [
    { "key": "heading", "kind": "TEXT" },
    { "key": "shopping", "kind": "CHECKLIST", "count": 3 },
    { "key": "meals", "kind": "NOTE" },
    { "key": "selfcare", "kind": "GROUP", "children": [
      { "key": "walk", "kind": "CARD" },
      { "key": "read", "kind": "NOTE" }
    ] }
  ],
  "warnings": []
}
```

As built (letta-mobile-bglj6.14) items carry no `id`: each piece's board id is `cmp-<artifact_id>-<key>`, derived by the reader; receipts written earlier with `id` still read (section 9).

Projected chat part (`UiMessage.artifacts[0]`, not a wire object):

```json
{ "artifactId": "weekend-plan", "canvasId": "canvas-conversation-conv-123", "revision": 42,
  "status": "published", "title": "Weekend plan", "kinds": ["TEXT","CHECKLIST","NOTE","GROUP","CARD"],
  "itemCount": 6, "bounds": { "x": 80.0, "y": 80.0, "width": 712.0, "height": 746.0 }, "error": null }
```

### 5.4 Error (inside `ExternalToolResult.Error`)

```json
{
  "ok": false,
  "code": "VALIDATION_FAILED",
  "catalog": "letta.canvas.compose",
  "version": 1,
  "problems": [
    { "path": "/items/1/kind", "code": "UNKNOWN_KIND", "message": "'STICKY' is not a kind; use NOTE, CHECKLIST, CARD, TEXT or GROUP" },
    { "path": "/items/2/items", "code": "TOO_MANY_ITEMS", "message": "a CHECKLIST holds at most 40 items (got 52)" },
    { "path": "/items/3/markdown", "code": "UNSUPPORTED_MARKDOWN", "message": "raw HTML at offset 14 is not allowed; see canvas_compose_guide" }
  ],
  "hint": "Nothing was published. Fix every problem and send the whole request again; canvas_compose_guide describes the format."
}
```

A board refusal (`code: "BOARD_REFUSED"`) carries `CanvasBatchValidator`'s violations as problems with `path` = `/items/<i>` of the item whose op was blamed and `message` = the violation text, so the existing invariant vocabulary (`element.exists`, `document.size`, ...) is preserved rather than duplicated.

---

## 6. Resolution recorded on `letta-mobile-4i2z9.20`

Amended table:

| layer | format | commitment |
|---|---|---|
| note / document content, agent-facing | strict markdown subset (section 3.3) | contract (compose v1) |
| note / document content, live and rendered | Cascade v2 block JSON, produced by our codec; a swappable rendering detail | ours |
| note / document content, at rest | `.md` with YAML front matter (4i2z9.20.2) | compatibility, unchanged |
| board structure, live | op log + notebook Automerge maps; JSON-Canvas field names where the concept exists; `owner`, `compose` extensions | ours |
| board structure, import/export | JSON Canvas 1.0 `.canvas` | compatibility (old prototype, vault projection), not the live model |
| ink | our own versioned schema (4i2z9.20.1) | unchanged |

---

## 7. Beads (children of `letta-mobile-bglj6`)

Order and parallel groups. Dependency edges are recorded in beads (`bd dep tree letta-mobile-bglj6.14`); `letta-mobile-8tlf9` now depends on C6.

| Group | Bead | Depends on | Can run in parallel with |
|---|---|---|---|
| G0 | `letta-mobile-bglj6.6` C1 Contract, tool definitions and fixtures | - | - |
| G1 | `letta-mobile-bglj6.7` C2 Scene model: geometry owner + compose provenance | C1 | C3, C4 |
| G1 | `letta-mobile-bglj6.8` C3 Markdown subset -> Cascade blocks codec (+ oracle test) | C1 | C2, C4 |
| G1 | `letta-mobile-bglj6.9` C4 Placement and reserve estimator | C1 | C2, C3 |
| G2 | `letta-mobile-bglj6.10` C5 Compiler and compose service (all-or-nothing, batch validator, receipt) | C2, C3, C4 | C6 |
| G2 | `letta-mobile-bglj6.11` C6 Auto-fit renderer honouring `owner` (absorbs 8tlf9) | C2, C4 | C5 |
| G3 | `letta-mobile-bglj6.12` C7 Tool wiring on both hosts (`canvas_compose`, `canvas_compose_guide`, dry_run, toolCallId); HOST REDEPLOY REQUIRED | C5 | C8 |
| G3 | `letta-mobile-bglj6.13` C8 Receipt projection and chat card | C1 (fixtures); C7 for the E2E test only | C7 |
| G4 | `letta-mobile-bglj6.14` C9 Agent guide, docs, end-to-end gate | C6, C7, C8 | - |
| follow-up | `letta-mobile-bglj6.15` C10 Notebook storage: `_documents` per entry (array vs object), P2 bug | - | any |
| follow-up | `letta-mobile-bglj6.16` C11 Per-property LWW for document geometry vs content (v2 update prerequisite), P3 | C2 | any |

Each bead's scope, files, acceptance criteria and tests are on the bead itself.

---

## 8. For Emmanuel (three items, each with a recommendation)

1. Should composing from a full-screen chat automatically switch the view to the canvas? Recommendation: no; the receipt card's "Show on canvas" does it on tap, and the docked/canvas-first page already shows the board. (Product intent; the beads implement the recommendation unless told otherwise.)
2. PR #1727 (`fix/canvas-automerge-oom`): the per-entry saving does not cover `_documents` because the projector writes an array (section 1). Recommendation: merge #1727 as is and take C10 next; or fold the object-form change into #1727 if you prefer one PR. Your PR, your call.
3. The Iroh host wrapper must be rebuilt for `canvas_compose` and the new document fields to exist on Iroh-served conversations (same as `title` needed in dpen4). Recommendation: C7's PR includes the wrapper and the beads note says "HOST REDEPLOY REQUIRED".

---

## 9. As built (letta-mobile-bglj6.6 - .14, .17)

Status: every bead of section 7 (C1-C9) is implemented on `feat/canvas-compose` with C10 (`letta-mobile-bglj6.15`, notes stored per entry) and the world-units decision (`letta-mobile-bglj6.17`); no PR is open for the branch yet. **HOST REDEPLOY REQUIRED**: the Iroh wrapper decodes typed ops (an old one drops `owner`/`compose` when it rewrites a note) and only a rebuilt one advertises `canvas.compose` and `canvas.compose_guide` (rebuild `:iroh-wrapper-cli:distZip`, install per `docs/architecture/lettashim-retirement-deployment-runbook.md`, restart `meridian-iroh-wrapper` only, check the NodeID is unchanged and `runtime_start` lists `canvas.compose`). Open follow-ups: `letta-mobile-bglj6.16` (per-property LWW, the v2 update prerequisite) and `letta-mobile-bglj6.18` (`apply_ops` atomic on the host, validated and stamped on the app path).

Where the code differs from sections 1-5 (the code wins; this is the record):

| Bead | Plan said | As built | Why |
|---|---|---|---|
| C1 (bglj6.6) | tool names `canvas_compose` / `canvas_compose_guide` | `canvas.compose` / `canvas.compose_guide` (`CanvasToolContract.COMPOSE`, `COMPOSE_GUIDE`); the receipt projection also accepts the underscore spelling | the provider-safe rename (`dg8f8`) is not on this branch; the beads followed the constants |
| C2 (bglj6.7) | `owner: "AUTO"` on the wire | lower case `auto` / `explicit` / `user`; `CanvasComposeProvenance` lives in `data.canvas` with `kind` as a string; unknown stored owner/compose values are kept as stored | forward compatibility with a later catalog |
| C3 (bglj6.8) | heading block `{"typeId":"heading","level":n}`; nested lists as Cascade `children` | `heading_<n>` type ids; a nested list item is a flat block with `attributes.indentationLevel` (the oracle test against cascade-editor 1.9.2 decides) | what the library writes and reads; a `heading` + `level` block renders as "Unsupported block type" |
| C4 (bglj6.9) | flat 22 heading font, `ceil(chars / charsPerLine)`, one global column width | the editor's real heading sizes (32/28/24 ...), greedy word wrap with per-character-class advances, column width per grid, label row grows when a label wraps; the estimator reads `indentationLevel` (bglj6.17) | conservative for capitals, CJK and URLs; matches the renderer |
| C5 (bglj6.10) | default keys `i1`, `i1-c2`; retries by a digest; presets onto `CanvasColorPicker` | 0-based keys (`i0`, `i3-c0`) that match the JSON-pointer indices; a retry is detected by comparing content signatures with geometry ignored (no digest stored), so a retry after a person moved a note is still a retry; presets map onto the sticky-note tints (red -> pink `#fbcfe8`, orange `#fed7aa`, yellow `#fde68a`, green `#bbf7d0`, cyan -> blue `#bfdbfe`, purple -> violet `#ddd6fe`), a NOTE/CHECKLIST without a colour takes the workspace default (null), a CARD is white | keys a model can map to its own request; no new provenance field; the note palette has no red or cyan |
| C6 (bglj6.11) | AUTO content longer than the booking scrolls with an indicator; no font shrink in v1 | type steps down (1.0, 0.9, floor 0.8), then the card grows past the booking (visual only, never written back); never clipped. EXPLICIT/USER scroll | the dispatching instruction for C6; a clipped or scrolled agent note was the 8tlf9 complaint |
| bglj6.17 | world unit = 1 dp on Android | everything on the board is in world units: cards compose under `CanvasWorldDensity` (density 1, font scale 1), DrawBox text too; board chrome stays in dp/sp | Emmanuel: "everything should use canvas units so it proportional"; the estimator and the renderer agree on every display |
| C7 (bglj6.12) | publish through the host's existing per-op path | one stamped `CanvasOp.BatchOp` per artifact on both hosts (`CanvasStampedBatch`): one relay message, one log append, one ack; inner ops keep their own id and lamport; a lost ack heals on retry (`ALREADY_PUBLISHED`). `ExternalToolCaller.toolCallId` reaches the host through the dispatcher; a stringified JSON tool return without text is kept whole | an artifact is all or nothing on the board too, not only in the validator |
| C8 (bglj6.13) | receipt item `{key, kind, id, count?, children?}` | as planned; the largest receipt was found to be about 4.6 KB | - |
| C9 (bglj6.14) | the receipt as in 5.3 | items no longer carry `id`: it is derived as `cmp-<artifact_id>-<key>` (`ComposeReceiptItem.boardId`, `CanvasArtifactReceipt.pieceIds`); the largest receipt at every cap went from 4 626 to 2 394 bytes, under the 4 096 above which `message.list` hydrate ships a tool return as a 2 KiB preview without bounds (`CanvasComposeContract.MAX_RECEIPT_BYTES`, `CanvasComposeReceiptSizeTest`). Receipts already written with ids still read. The guide is generated from the contract constants and test-locked (`CanvasComposeGuideTest`, `docs/reference/canvas-compose-v1.md`); it is about 7 700 characters (cap `CanvasComposeGuide.MAX_CHARS` = 8 000), over the 6 000 the bead suggested, because every error and problem code carries an example that a test sends | a near-max compose lost its bounds on reload, so "Show on canvas" could not frame it |

The multi-card gate (the 8tlf9 gate of the bglj6 review) is `CanvasComposeMultiCardEndToEndTest` (sharedLogic: App Server request -> ExternalToolDispatcher -> host -> one BatchOp -> notebook -> restart -> timeline -> one receipt; a second compose beside the first; an idempotent retry) and `CanvasComposeEndToEndRenderTest` (sharedUI, Skiko: every composed card rendered inside its reservation at full type size, no overlaps, inside the receipt bounds, Show on canvas framing them on the real workspace; snapshots in `sharedUI/build/canvas-compose-e2e/`). Both run `request-multi-card.json`.

Manual device check (after the host redeploy), on the Pixel 9 Pro and on the Cintiq desktop:

1. Ask the agent for a plan with a checklist, a long note with headings and nested lists, and a group of cards. The chat shows one card on the narrating message: title, kinds and count, published.
2. Tap "Show on canvas": the canvas opens framed on the artifact at no more than 100%; nothing is clipped and nothing overlaps the existing drawing or notes.
3. Move one composed note and resize another: they stay where put (owner USER); ask the agent to send the same compose again: nothing changes on the board and the chat still shows one card.
4. Reload the conversation (and restart the app): the card is still there with Show on canvas working, and the board is unchanged.
