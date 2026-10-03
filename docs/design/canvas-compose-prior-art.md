# canvas.compose: prior-art research

Date: 2026-10-01. Status: research input for design, not a decision record.

Method: web search and page fetch. Every claim carries a URL. Where a fetch returned only a summary, or a claim came from a search snippet or from memory, it is marked **(unverified)** or **(snippet only)**. Page-summarising fetches were lossy, so nothing here should be quoted as a spec without re-reading the source.

Scope reminder (from the brief): `canvas.compose` is a small, closed, versioned, agent-facing vocabulary (v1 kinds NOTE, CHECKLIST, CARD, TEXT, GROUP; create-only; markdown note content; compose layer owns placement; AUTO/EXPLICIT/USER ownership marker; compiled into the existing op log; typed artifact refs on the same assistant message; open question: markdown plus JSON Canvas 1.0 persistence).

---

## 1. Executive summary: top 10 takeaways

| # | Takeaway | Verdict | One-line recommendation |
|---|---|---|---|
| 1 | The plan's shape (small closed catalog, versioned, host-validated, agent never sees layout) is exactly what A2UI does: `catalogId`, a `version` on every message, flat id-referenced components, and a structured `VALIDATION_FAILED` error carrying a JSON-pointer `path` so the model can self-correct. | Confirm | Name the vocabulary with a catalog id plus version (for example `letta.canvas.compose/1`), stamp it on every call, and return A2UI-shaped validation errors. |
| 2 | LLMs are poor at 2D coordinates. tldraw says agents are "really, really bad at working in 2D space"; Miro's MCP exposes a sticky grid tool and a skill that assembles layouts by gap math; community Excalidraw skills push grid or layout-engine delegation. | Confirm | Keep the model out of x/y entirely. Accept only intent (kind, content, optional relation or anchor hint) and let compose place. |
| 3 | The best-regarded programmatic creation API for a canvas is a "skeleton" that the host expands: Excalidraw's `convertToExcalidrawElements` fills ids, text-container sizes and bindings from a minimal input. | Adopt pattern | Define `compose` input as a skeleton and compile it to full op-log elements, exactly like Excalidraw's skeleton to element step. |
| 4 | Auto-fit needs a stored mode, not just a measured size, and the user-resize flip is common: tldraw text `autoSize` becomes fixed-width when a side handle is dragged; Excalidraw text has `autoResize`; OOXML and Google Slides store autofit as an enum. No product found stores a three-value owner, but the AUTO/EXPLICIT/USER marker is a superset of these. | Confirm, with a caveat | Keep AUTO/EXPLICIT/USER, but persist the intent (mode), never a measured font scale. See takeaway 5. |
| 5 | The renderer, not the generator, computes fit. PowerPoint stores `normAutofit fontScale` computed by PowerPoint; python-pptx cannot do it without font files. Therefore a generator that lacks font metrics cannot be exact. | Confirm design, tighten | Fix width and a font-size floor per kind; the renderer shrinks font to the floor, then grows height. Placement must reserve space with a conservative height bound, because the true height is renderer-derived. |
| 6 | No standard solves "single receipt". The nearest patterns attach a typed part to the same message and reconcile by id: Vercel AI SDK data parts (rewrite a part with the same id and the client reconciles), A2UI DataParts inside an A2A message, MCP tool results with `resource_link`. | Confirm, fill the gap | Define one `canvas_artifact` part on the narrating assistant message with a stable id, so republishing the same id updates in place. |
| 7 | JSON Canvas 1.0 is a good interchange format and a poor live model. It is MIT, has text/file/link/group nodes, markdown in text nodes, integer coordinates, array order as z-order, and no stated rule for unknown properties. It has no sizing mode, no owner, no per-node version. | Change | Persist the live model as Automerge maps with JSON-Canvas-compatible field names plus namespaced extensions. Treat `.canvas` as import/export, not storage. |
| 8 | Per-property writes beat whole-object rewrites everywhere. Figma models a doc as `Map<ObjectID, Map<Property, Value>>`; Automerge docs say concurrent edits to different map keys do not conflict while same-key edits lose one value. | Confirm | Compile each kind into per-field map writes; never replace an element object. Keep the AUTO/EXPLICIT/USER flag as its own key so a move does not rewrite content. |
| 9 | Z-order and order-within-parent are the CRDT trap. Figma and Excalidraw both use fractional indexing strings; JSON Canvas uses array order, which does not merge well. | Change | Store an `index` fractional-order string per element; derive JSON Canvas array order on export. |
| 10 | Markdown as the note contract is the lowest common denominator and matches JSON Canvas text nodes, but other products diverge: Miro stickies take a tiny HTML subset, tldraw stores TipTap JSON, Notion stores `rich_text` arrays and a structured `to_do`. | Confirm with a constraint | Pin a strict subset (CommonMark plus GFM task lists, no raw HTML, no images in v1) and make CHECKLIST a structured item list (id, text, checked) that renders to and exports as `- [ ]` markdown. |

Further cross-cutting findings: the biggest competitive trend is toward richer composer tools, not fewer (Miro replaced diagram/layout/doc tools with SVG-based canvas read/create/update tools; section 2.1). Create-only v1 is defensible, but stable ids must be assigned now to make v2 update possible.

---

## 2. Findings by area

### 2.1 Agent to canvas / whiteboard integrations

#### tldraw

- **Agent starter kit.** Actions are Zod schemas with a `_type` field; each action has an `AgentActionUtil` with `applyAction()`. Default actions: create/update/delete shapes, freehand pen strokes, multi-shape operations (rotate, resize, align, distribute, stack, reorder), messaging and thinking, todo-list management, viewport movement, shape counting, scheduling follow-up work, external API calls. Source: https://tldraw.dev/starter-kits/agent and https://github.com/tldraw/agent-template.
- **Context to the model uses three shape formats:** BlurryShape (viewport shapes: bounds, id, type, text), FocusedShape (most properties), PeripheralShapeCluster (off-screen groups: bounds plus count). A screenshot of the agent's view accompanies it. Source: https://tldraw.dev/starter-kits/agent.
- **Streaming and sanitization.** Actions stream; `applyAction` waits for `action.complete` before executing. `sanitizeAction()` repairs hallucinated or duplicate ids (`ensureShapeIdExists`, `ensureShapeIdIsUnique`), and coordinates are offset to and from a working-area frame and numbers rounded before sending to the model then restored. tldraw says explicitly that mistakes come from hallucination and from the canvas changing under the agent. Source: https://tldraw.dev/starter-kits/agent.
- **Spatial harness lessons.** Discrete `createShape`/`updateShape` actions "proved limiting" in the Fairies work, and tldraw Offline moved to code generation against the live `Editor`; viewport-bounded context rather than the whole canvas; coding agents are "really, really bad at working in 2D space". Source: https://tldraw.dev/blog/harnessing-the-agents (fetch summary; one detail, the exact failure modes, was not itemised there). A third-party write-up reports that dropping camera-move and review actions roughly halved model calls per capture: https://techstackups.com/articles/tldraw-agent-draw/.
- **Shape schema.** Shapes are JSON records with base props plus a `props` object; a free `meta` field is stored and synced but unused by tldraw; schema evolves via per-shape migrations. Source: https://tldraw.dev/docs/shapes. The note shape has `color, labelColor, size, font, fontSizeAdjustment, align, verticalAlign, growY, url, richText, scale, textLastEditedBy` and thirteen numbered schema versions (including `AddRichText`, `AddFirstEditedBy`). Source: https://raw.githubusercontent.com/tldraw/tldraw/main/packages/tlschema/src/shapes/TLNoteShape.ts. Text shape props include `w, richText, scale, autoSize`: https://tldraw.dev/reference/tlschema/TLTextShape.
- **Sizing behaviour.** Notes have a fixed base width of 200 px and grow vertically (`growY`); when text is too wide the note shrinks the font down to a minimum of 14 px before wrapping; notes are not user-resizable unless `resizeMode: 'scale'`; clone handles and keyboard shortcuts create adjacent notes, and notes snap into adjacent slots within 10 screen px. Source: https://tldraw.dev/sdk-features/note-shape. Text shapes default to `autoSize: true` (no wrap, grows horizontally); dragging a side handle converts to fixed-width. Source: https://tldraw.dev/sdk-features/text-shape.
- **Layout helpers.** `Editor` exposes `stackShapes`, `packShapes`, `alignShapes`, `distributeShapes`, `getShapePageBounds`, `getShapesAtPoint`: https://tldraw.dev/reference/editor/Editor (fetch summary; `packShapes` algorithm is not described there, **unverified** detail).
- **Make Real / canvas-as-output.** tldraw's AI page frames three patterns: canvas as output surface, visual workflows with AI nodes, and the agent kit; for LLM context it exports a screenshot plus structured shape text via `ShapeUtil.getText()`: https://tldraw.dev/docs/ai.
- **Licence.** tldraw SDK licence permits commercial use if the "Made with tldraw" watermark is kept; removal requires a business licence: https://github.com/tldraw/agent-template (fetch summary). Do not copy code; patterns only.
- **MCP servers (third-party).** `create_shape, update_shape, delete_shapes, connect_shapes, create_frame, create_flowchart (auto-layout), get_snapshot, zoom_to_fit, clear_canvas`: https://glama.ai/mcp/servers/@dpunj/tldraw-mcp (search snippet). A file-based one exposes `tldraw_read/write/create/list/search/get_shapes/add_shape/update_shape/delete_shape` over `.tldr` files with path-traversal and format validation: https://github.com/talhaorak/tldraw-mcp.

#### Excalidraw

- **Scene schema.** Top level `type: "excalidraw"`, `version`, `source`, `elements`, `appState`, optional `files` keyed by `fileId`. Common element fields include `id, type, x, y, width, height, angle`, style fields, reconciliation fields `seed, version, versionNonce, index, updated`, `isDeleted, locked`, `groupIds, frameId`, `boundElements, link, customData`. Labels are separate text elements bound with `boundElements` / `containerId`. Source: https://plus.excalidraw.com/docs/api/scene-content-schema.
- **Source of truth for types.** `version` increments per change; `versionNonce` is regenerated per change for deterministic tie-breaks; `index` is a fractional-index string; text has `autoResize` (default true), `containerId`, `originalText`, `lineHeight`. Source: https://raw.githubusercontent.com/excalidraw/excalidraw/master/packages/element/src/types.ts.
- **Reconciliation.** Remote loses when the local element is being edited/resized/created, or has a higher `version`; ties go to the lowest `versionNonce`. Source: https://github.com/excalidraw/excalidraw/blob/master/packages/excalidraw/data/reconcile.ts (fetch summary). Note this is whole-element LWW, which is coarser than our per-property LWW.
- **Skeleton API.** `ExcalidrawElementSkeleton` is "the simplified version... with the minimum possible attributes"; `convertToExcalidrawElements` regenerates ids by default (`regenerateIds: false` keeps them), computes container size from label dimensions, and computes arrow bindings. Its sticky-note case shrinks font from a ceiling to a minimum and only then grows height. Source: https://docs.excalidraw.com/docs/@excalidraw/excalidraw/api/excalidraw-element-skeleton.
- **mermaid-to-excalidraw.** `parseMermaidToExcalidraw(def, config)` returns `{elements, files}`; MIT; caps of 500 edges and 50,000 characters by default; the layout comes from Mermaid itself. Source: https://github.com/excalidraw/mermaid-to-excalidraw. Excalidraw's AI text-to-diagram generates Mermaid and then converts (the model never emits coordinates): https://smcleod.net/2024/10/generating-diagrams-with-with-ai-/-llms/ (secondary source).
- **Excalidraw MCP App (official).** Tools `read_me` (element format reference, "call this before create_view") and `create_view` (a JSON string of Excalidraw elements, up to 5 MB, streamed with partial-JSON parsing and draw-on animation); `save_checkpoint`/`read_checkpoint`; pseudo-elements `cameraUpdate`, `restoreCheckpoint` and `delete`; the cheat sheet demands 4:3 camera sizes, min font 16, 20-30 px gaps, never reuse deleted ids, emit shapes then labels then arrows progressively. MIT. Sources: https://mcp.excalidraw.com/manifest.json, https://github.com/excalidraw/excalidraw-mcp, https://raw.githubusercontent.com/excalidraw/excalidraw-mcp/main/src/server.ts, https://glama.ai/mcp/servers/@functionstackx/excalidraw-mcp/blob/fed409dd6c47ad16bffc844931b58a1b4760bc77/CLAUDE.md.
- **Files.** Binary assets live in a separate `files` map keyed by `fileId`, not in `elements`: https://plus.excalidraw.com/docs/api/scene-content-schema.
- **`restore()` migration helper** exists in the public API: referenced as a "Restore Utilities" section at https://docs.excalidraw.com/docs/@excalidraw/excalidraw/api/utils/export, behaviour **unverified**.

#### Miro

- **REST items API.** Sticky create is `POST /v2/boards/:board_id/sticky_notes` with `data.content`, `data.shape` (`square` default or `rectangle`), `style.fillColor` (named palette, default `light_yellow`), `position`, `geometry` width/height, and `parent` (frame id). Source: https://developers.miro.com/docs/working-with-sticky-notes-and-tags-with-the-rest-api and https://engineering.miro.com/docs/api/miro/create-sticky-note-item (snippets only).
- **Bulk create.** Up to 20 items of mixed types per create call and it is transactional (a failure fails all remaining): https://community.miro.com/developer-platform-and-apis-57/miro-rest-api-introduces-create-items-in-bulk-13778 and https://developers.miro.com/changelog/2023-06-12 (snippet only).
- **Rich text in stickies.** Only `<strong>`/`<em>`-style inline HTML is rendered; other tags are escaped as literal text: https://community.miro.com/developer-platform-and-apis-57/sticky-notes-do-not-seem-to-support-html-based-rich-text-styling-14140 (snippet only).
- **Sticky auto-sizing.** Text auto-shrinks to fit the sticky; staff say overriding auto-size "is not possible at present" and suggest grouping a text box with a sticky: https://community.miro.com/ask-the-community-45/can-you-override-auto-sizing-on-sticky-notes-8857. Users complain that the shrink can reach tiny sizes and waste border space on the same page. Lesson: auto-fit with no opt-out frustrates users.
- **Official MCP server.** Current tools include `canvas_get_canvas_composer_skill` (call once per session before creating), `canvas_read_as_svg` (items with stable `data-miro-id`), `canvas_create_from_svg`, `canvas_update_from_svg` (applies SVG changes as diffs), plus `table_*` (deprecating), `image_*`, `comment_*`, `prototype_*`, `board_*`. The page says the canvas tools replace legacy diagram, layout and document tools, supporting Mermaid, documents, tables with grid/timeline/kanban views, workshop widgets (polls, dot voting, planning poker...), shapes and connectors. Source: https://developers.miro.com/docs/miro-mcp-tools. Interpretation: Miro moved from many bespoke tools to one richer, documented composer vocabulary plus an on-demand "skill" doc. This both supports a small closed vocabulary and shows pressure to grow it.
- **Third-party Miro MCP.** `miro_create_sticky_grid` (max 50, params `columns` default 3, `spacing` default 220, `start_x/start_y`, optional `parent_id`) and a `miro-workflow` skill describing frame sizes, gap math and colour conventions for sprint, retro, brainstorm, story-map and kanban layouts: https://glama.ai/mcp/servers/olgasafonova/miro-mcp-server/tools/miro_create_sticky_grid and https://glama.ai/mcp/servers/olgasafonova/miro-mcp-server. This is the same idea as our layout-owned-by-compose: the layout math is documented once and the LLM only supplies content.
- **Miro AI/Sidekicks.** Describing a board produces "frames, stickies, shapes, tables, kanbans, and interactive widgets"; AI Cluster groups selected stickies by keyword or sentiment. Source: https://help.miro.com/hc/en-us/articles/36650745968914-Miro-AI-with-Board-Layout-BETA. The internal placement algorithm is not documented in anything found (**unverified**). A Miro community thread notes the grid-mode auto-arrange in frames is a one-shot action and not a live layout, with the old grid mode being deprecated: https://community.miro.com/ideas/auto-arrange-stickies-in-frames-14963 (snippet only). Lesson: one-shot arrange, not continuous re-layout.

#### FigJam / Figma

- FigJam AI generates boards, diagrams, flowcharts, timelines; sorts and summarises stickies: https://help.figma.com/hc/en-us/articles/16822138920343. FigJam diagramming inside Claude exists via MCP but the details are undocumented in the sources found: https://www.createwith.com/tool/figma/updates/figma-brings-figjam-diagramming-directly-into-claude. A Figma forum thread says the FigJam canvas is inaccessible to AI agents and assistive technology (snippet only): https://forum.figma.com/suggest-a-feature-11/figjam-canvas-inaccessible-to-ai-agents-and-assistive-technology-50966. FigJam internals: **unverified**.

#### Microsoft Whiteboard / Loop

- Copilot in Whiteboard: Suggest inserts sticky notes, Categorize clusters existing stickies into colour-coded groups with headings, Summarize outputs a Loop component; all operate on sticky notes only, not text boxes or ink. Source: https://support.microsoft.com/en-US/whiteboard/welcome-to-copilot-in-whiteboard. Takeaway: even Microsoft restricts the AI vocabulary to the sticky-note primitive. Developer API and Loop/Fluid internals: **unverified**, nothing found.

#### Obsidian Canvas and JSON Canvas

- **Spec 1.0** (released 2024-03-11): top-level optional `nodes` and `edges`; nodes ordered by z-index (first is bottom); node types `text` (plain text with Markdown syntax), `file` (+ optional `subpath` heading/block), `link` (`url`), `group` (`label`, `background`, `backgroundStyle` cover/ratio/repeat); all nodes have `id, type, x, y, width, height` (integers) and optional `color`; edges have `fromNode, toNode`, optional `fromSide/toSide`, `fromEnd/toEnd` (none/arrow), `color`, `label`; colour is hex or preset 1-6 (red, orange, yellow, green, cyan, purple), with preset values intentionally undefined. Sources: https://jsoncanvas.org/spec/1.0/ and https://raw.githubusercontent.com/obsidianmd/jsoncanvas/main/spec/1.0.md.
- **What the spec does not say.** No rule for unknown or custom properties and no extension mechanism (the fetched summary of the raw spec says it is silent). Repo licence is MIT. Source: https://github.com/obsidianmd/jsoncanvas. Supported apps listed: Obsidian, Kinopio, Flowchart Fun, hi-canvas, OrgPad, Charkoal, Ideaflip; libraries in Dart, Go, Python, React, Ruby, Rust, TypeScript, Vue; converters from Heptabase export and to Mermaid: https://github.com/obsidianmd/jsoncanvas/blob/main/docs/apps.md.
- **Auto-size in Obsidian.** Core Canvas does not auto-fit; community plugins add it. One plugin measures an off-screen Markdown clone at final node width: https://www.obsidianstats.com/plugins/canvas-current-node-auto-size. A search snippet claims that manually dragging the bottom edge disables that plugin's auto-resize, but the plugin page did not confirm it (**unverified**).
- **Advanced Canvas** plugin extends JSON Canvas ("Advanced JSON Canvas") with frontmatter-like custom properties: https://community.obsidian.md/plugins/advanced-canvas (snippet only). Whether other tools preserve those fields is **unverified**.

#### Apple Freeform, Canva, Affine, Heptabase

- **Apple Freeform:** no public scripting or content API found; Shortcuts/AppleScript results did not mention Freeform. Treat as having no documented agent integration (**unverified**).
- **Canva:** not researched (no source found worth citing); **unverified**.
- **AFFiNE / BlockSuite:** edgeless editor on Yjs with a block tree; a surface block holds canvas elements and note blocks embed rich text inside the canvas; MPL-2.0. Source: https://github.com/toeverything/blocksuite. Relevant as a "notes are full documents placed on a canvas" model, heavier than ours.
- **Heptabase:** whiteboards of cards; MCP found is a third-party backup reader with export to Markdown/JSON/Mermaid (now archived): https://github.com/LarryStanley/heptabase-mcp. A converter from Heptabase export to JSON Canvas exists (apps.md above).

### 2.2 Declarative agent-generated UI and artifact protocols

#### Google A2UI

- **What:** protocol for agent-driven UIs rendered natively; catalogs are client-supplied, so agents can only choose from pre-approved components. Versions: v0.9.1 current, v1.0 release candidate, v0.9, v0.8 legacy. Source: https://a2ui.org/.
- **Messages (v0.9):** `createSurface` (needs `surfaceId`, `catalogId`), `updateComponents` (flat adjacency list of components by id, one has id `root`), `updateDataModel` (JSON Pointer paths), `deleteSurface`. Each message carries `version: "v0.9"`. Source: https://a2ui.org/specification/v0.9-a2ui/.
- **Catalog negotiation:** `catalogId` is an opaque string (URIs by convention); clients advertise `supportedCatalogIds`. Basic catalog: Text, Image, Icon, Video, AudioPlayer, Row, Column, List, Card, Tabs, Divider, Modal, Button, CheckBox, TextField, DateTimeInput, ChoicePicker, Slider; function catalog with validation, formatting, logic. Same source.
- **Validation:** v0.9 is "prompt-first": the schema goes into the prompt rather than strict structured output, and output is validated afterwards; errors return `code: "VALIDATION_FAILED"`, `surfaceId`, JSON-pointer `path`, `message`. Same source. Unknown-component handling is unspecified (buffer or skip).
- **Streaming:** JSONL, one complete JSON object per line, rendered progressively with 16 ms batching and diffing: https://a2ui.org/concepts/data-flow/.
- **Attaching to chat:** inside A2A, messages are DataParts. The v0.9.1 extension spec gives the mime type `application/a2ui+json`, data as an array of messages, each message holds one surface operation, extension activation by header URI `https://a2ui.org/a2a-extension/a2ui/v0.9.1`, client capabilities in message metadata `a2uiClientCapabilities`: https://a2ui.org/specification/v0.9.1-a2ui-extension-specification/. A search snippet shows the mime type differing across versions (`application/json+a2ui` in older docs), another reason to pin the version. How a surface maps to a human-visible chat message is not addressed by the data-flow page. Licence: **unverified**.
- **Our repo already ships an A2UI renderer** (per CLAUDE.md), so catalog-id, version stamping and validation-error vocabulary are reuse, not novelty.

#### MCP Apps and MCP-UI

- A tool declares `_meta.ui.resourceUri` pointing to a `ui://` resource (HTML, mime `text/html;profile=mcp-app`), which the host can preload (enabling streaming tool inputs), renders in a sandboxed iframe, and talks to via JSON-RPC over `postMessage` (`ui/initialize`, `tools/call` etc.). The extension is spec-dated 2026-01-26. Sources: https://modelcontextprotocol.io/extensions/apps/overview and https://mcpui.dev/guide/introduction. Supported hosts listed include Claude, VS Code Copilot, Microsoft 365 Copilot, Goose.
- Open vocabulary: arbitrary HTML. The safety model is sandboxing, not a closed catalog. Not directly usable by a native Compose renderer; relevant only as the ecosystem our tools may be exposed through.
- The tool result itself uses core MCP: `content` blocks (text, image, audio, `resource_link`, embedded `resource`), optional `structuredContent` validated against an optional `outputSchema`, annotations (`audience`, `priority`), `isError`: https://modelcontextprotocol.io/specification/2025-06-18/server/tools. A `resource_link` with annotations `audience: ["user"]` is the closest core-MCP primitive to "a typed reference to an artifact".

#### OpenAI Apps SDK and ChatKit

- Apps SDK now builds on the MCP Apps bridge; tools should work without UI; split data tools from render tools; state categories: authoritative business data, ephemeral UI state (`window.openai.setWidgetState`), cross-session. Source: https://developers.openai.com/apps-sdk/build/chatgpt-ui.
- Visibility split: `structuredContent` and `content` are shown to model and component; `_meta` goes to the component only and is hidden from the model. Source: https://developers.openai.com/apps-sdk/reference.
- ChatKit widgets: a closed catalog with containers (`Card`, `ListView`) and about 15+ components (Box, Row, Col, Text, Markdown, Title, Button, Select, DatePicker, Image, Form...), designed in a visual builder, JSON served from the backend; user actions return as custom action payloads. Sources: https://developers.openai.com/api/docs/guides/chatkit-widgets and https://openai.github.io/chatkit-js/. A ChatKit source-annotations feature exists. How widgets are stored as thread items was not confirmed (**unverified**).

#### Vercel AI SDK

- Tool parts in `message.parts` use typed names `tool-${toolName}` with states `input-available`, `output-available`, `output-error`; the UI maps tool results to components: https://ai-sdk.dev/docs/ai-sdk-ui/generative-user-interfaces.
- **Data parts** (`data-*`) are typed and stored in the message history; "rewriting a data part with an existing ID automatically reconciles on the client side"; transient data parts skip history; sources are a separate part type: https://ai-sdk.dev/docs/ai-sdk-ui/streaming-data. This is the strongest existing precedent for our update-in-place receipt.
- `streamUI`/RSC is experimental with development paused, with flicker on `.done()` and no stream abort; migrate to AI SDK UI: https://ai-sdk.dev/docs/ai-sdk-rsc/migrating-to-ui. Pitfall: do not couple artifact lifecycle to the render transport.

#### Anthropic Artifacts

- Artifacts are versioned within a chat (version dropdown), edits can be targeted updates or full rewrites, and publish state is per version: https://support.anthropic.com/en/articles/9487310-what-are-artifacts-and-how-do-i-use-them (search snippet; the exact tag syntax and the update/rewrite command semantics were not confirmed, **unverified**). Artifacts are an open vocabulary (arbitrary HTML/React/etc.).

#### Microsoft Adaptive Cards

- Cards carry an explicit `version`; features can declare `requires` and `fallback` (drop or substitute element), and `Action.Execute` (since 1.4) supports a `refresh` and returns an updated card in response; old clients reject newer cards. Sources: https://learn.microsoft.com/en-us/adaptive-cards/authoring-cards/universal-action-model (fully read) and the schema explorer https://adaptivecards.microsoft.com/explorer/AdaptiveCard.html (not successfully fetched; `requires`/`fallback` semantics from the first page only).
- Lesson: closed catalog plus version plus per-element `fallback` is the proven way to evolve a vocabulary while old renderers exist.

#### Slack Block Kit

- Messages are arrays of blocks: 50 per message, 100 in modals/home; 21 block types (Header, Section, Markdown, Rich text, Image, Card, Table, Actions, Input, Carousel, Task card, Plan...): https://docs.slack.dev/reference/block-kit/blocks. Fallback text and `chat.update` replace-semantics are well-known but were not confirmed in the fetch (**unverified** here).
- Lesson: a hard per-call item cap is normal.

#### AG-UI (CopilotKit) as the state-sync pattern

- Event categories include lifecycle, text message, tool call, state (snapshot then RFC 6902 JSON Patch deltas), activity, reasoning, subagent, custom; ids (`messageId`, `toolCallId`) group related events: https://docs.ag-ui.com/concepts/events. JSON Patch has array-index fragility (indices shift on remove): https://datatracker.ietf.org/doc/html/rfc6902. Lesson: id-addressed maps are safer than index-addressed arrays for any update language.

#### Cross-cutting comparison of the artifact-protocol questions

- **Closed vs open:** closed - A2UI, ChatKit, Adaptive Cards, Block Kit; open - MCP Apps/MCP-UI (HTML), Anthropic Artifacts, Miro SVG composer. Closed ones carry versions and fallback; open ones rely on sandboxing.
- **Attachment to a chat message:** Slack (blocks are the message), AI SDK (typed parts in `message.parts`), A2UI-in-A2A (DataPart), MCP (tool result content), ChatKit (widget in thread). None specifies a "this message is the receipt for those external objects" invariant; we must define it.
- **Partial/streaming:** A2UI JSONL, Excalidraw MCP partial-JSON, tldraw `complete` flag, AI SDK `input-streaming`/transient parts.
- **Updates to an existing artifact:** A2UI re-sends `updateComponents`/`updateDataModel` by id and path; AI SDK re-sends a data part with the same id; Adaptive Cards returns a replacement card; Excalidraw MCP uses `restoreCheckpoint` plus `delete` pseudo-elements; Miro uses `canvas_update_from_svg` diffs keyed by `data-miro-id`.

### 2.3 Layout and placement of N generated items

- **Where products put layout:** tldraw gives the agent `stack/align/distribute` actions, and the editor has `packShapes`; Miro MCP provides a grid tool with explicit `columns`, `spacing=220` and `start_x/y`, and documents gap-math in a skill (links in 2.1). Neither is a continuous layout; Miro's frame arrangement is also one-shot.
- **Packing:** ELK's `rectpacking` packs unconnected boxes in the given order, with phases (width approximation, row placement, compaction, expansion), default aspect ratio 1.3, padding 15 and spacing 15: https://eclipse.dev/elk/reference/algorithms/org-eclipse-elk-rectpacking.html. A simple binary-tree packer (MIT) with a growing container exists: https://github.com/jakesgordon/bin-packing. Neither is needed for N similar cards; a row/column flow is enough, and order preservation matters (a shopping list is read in order).
- **Structured content:** d3-hierarchy (tree, cluster, partition, pack, treemap; deterministic; ISC): https://d3js.org/d3-hierarchy. dagre (layered digraph layout, MIT, maintained under `@dagrejs/dagre`): https://github.com/dagrejs/dagre. ELK (layered, force, mrtree, rectpacking; Java; elkjs is a GWT-transpiled port): https://github.com/eclipse-elk/elk and https://github.com/kieler/elkjs. ELK's licence is EPL-2.0 per my recollection, not confirmed by the fetch (**unverified**), which matters for shipping it in a KMP binary. v1 has no edges (GROUP only), so none of these are needed yet; GROUP can place children with a row/column/grid flow.
- **Collision avoidance with existing content and viewport:** tldraw's agent solves it by giving the model viewport context and a working-area offset rather than an algorithm (https://tldraw.dev/starter-kits/agent); tldraw note clone handles snap to adjacent slots at 10 screen px (https://tldraw.dev/sdk-features/note-shape). I found no product documentation of a "find free space" algorithm for AI inserts (**unverified**). A generic candidate is: compute bounds of existing elements, try the viewport-adjacent free region first, then right-of or below the current content bounds, with a fixed gap. This is our own design, not prior art.
- **Determinism without host font metrics:** PowerPoint stores the autofit result as `normAutofit fontScale`/`lnSpcReduction` and the rendering app computes it; python-pptx's `fit_text` needs a font file and cannot work without it: https://python-pptx.readthedocs.io/en/latest/dev/analysis/txt-autofit-text.html. Therefore: fix the width per kind (tldraw notes: 200 px), fix a font-size ceiling and floor (tldraw: shrink to min 14 before wrapping; Excalidraw sticky: shrink to a minimum then grow height), and let the renderer derive the rest.
- **Autofit as stored mode:** Google Slides has three modes: do not autofit, shrink text on overflow (placeholder default), resize shape to fit text (text box default): https://support.google.com/docs/answer/10364036. OOXML `spAutoFit` / `normAutofit` / `noAutofit` (same python-pptx page). tldraw `autoSize` boolean, flipped by a side-handle drag (https://tldraw.dev/sdk-features/text-shape); Excalidraw `autoResize` boolean. The Slides page does not state what manual resize does (**unverified**).
- **"User moved it, stop auto-sizing":** the verified instance is tldraw's text shape (resize by edge handle turns `autoSize` off). Miro offers no opt-out (staff statement above). A claimed Obsidian plugin behaviour was not confirmed. No product found uses an explicit AUTO/EXPLICIT/USER owner; ours is a design extension.

### 2.4 Rich content in canvas nodes

- **Markdown natively:** JSON Canvas text nodes are "plain text with Markdown syntax" (https://jsoncanvas.org/spec/1.0/); Obsidian renders them (and community plugins measure the rendered markdown for sizing).
- **Not markdown:** tldraw stores TipTap/ProseMirror JSON (`richText`) with helpers `toRichText`, `renderPlaintextFromRichText`, `renderHtmlFromRichText`, and it supports bold, italic, code, highlights, lists, links: https://tldraw.dev/sdk-features/rich-text. There is no markdown helper in the page (round-trip is a plugin or custom job, **unverified**). Miro stickies take only a few inline HTML tags (snippet above). Notion stores `rich_text` arrays with annotations (2,000 characters per element, 100 blocks per request) and block types including `to_do { rich_text, checked, color, children }`: https://developers.notion.com/reference/block.
- **Checklists:** GFM task list items (`- [ ]`, `- [x]`) are a CommonMark extension (spec section 5.3; the fetch truncated before the syntax, syntax from my own knowledge): https://github.github.com/gfm/. Obsidian Tasks layers emoji metadata (due, scheduled, recurrence, id, dependencies) on that line syntax and documents that it breaks with non-breaking spaces and Unicode variation selectors: https://publish.obsidian.md/tasks/Reference/Task+Formats/Tasks+Emoji+Format. Pitfall: in-line metadata in markdown is fragile; keep metadata out of the text body.
- **Round-trip:** products that store markdown (Obsidian canvas) are lossless by construction; products with structured rich text (tldraw, Notion) round-trip only through custom converters. AFFiNE's BlockSuite embeds block-tree notes in the canvas (https://github.com/toeverything/blocksuite), heavier than we need.

### 2.5 CRDT modelling for whiteboards

- **tldraw sync:** server-authoritative record store, not a CRDT: git-like push, pull and rebase; storage `InMemorySyncStorage` or `SQLiteSyncStorage`; large binaries use a separate `TLAssetStore`; schema `props`, `meta` and `migrations` let differing client versions collaborate. Sources: https://tldraw.dev/docs/sync and https://tldraw.dev/sdk-features/collaboration. A third-party analysis states LWW at record field level, a `documentClock` incremented per write with `lastChangedClock` per record, tombstones pruned above 5,000 (plus a 1,000-record buffer), and a full resync when a client's clock predates `tombstoneHistoryStartsAtClock`: https://raw.githubusercontent.com/gridaco/nothing/main/docs/wg/research/crdt/tldraw.md. That analysis is internally inconsistent about whether different-property edits merge, and is not from tldraw; treat as **unverified** and re-check source before relying on it.
- **Excalidraw collab:** whole-element reconciliation by `version`, tie-broken by lowest `versionNonce`; fractional `index` ordering; images in a separate `files` map (links above).
- **Figma:** a document is a tree of objects, modelled as `Map<ObjectID, Map<Property, Value>>`; per property last-writer-wins at a central server; parent stored as a property on the child, with the server rejecting cycles; ordering via fractional indexing in base 95 with arbitrary precision: https://www.figma.com/blog/how-figmas-multiplayer-technology-works/ and https://www.figma.com/blog/realtime-editing-of-ordered-sequences/. Known drawbacks: index strings grow, concurrent inserts interleave, identical indices must be fixed by the server.
- **Yjs whiteboards:** guidance is to use a `Y.Map` per shape with nested map values when properties are edited concurrently (replacing a whole record loses a concurrent edit), keep the rendering objects out of the shared doc, and keep viewport/camera outside the shared doc. Source: https://konvajs.org/docs/sandbox/Multiplayer_Whiteboard.html (snippet only); BlockSuite uses Yjs (above).
- **Automerge (ours):**
  - Maps resolve per key (different keys merge, same key keeps one deterministic winner); strings are collaborative text by default in v3, with `ImmutableString` for non-collaborative text; Lists use RGA; Text uses Peritext with marks. Sources: https://automerge.org/docs/reference/documents/ and https://automerge.org/blog/automerge-3/.
  - Full history is retained; Automerge 3.0 cut memory over 10x and loads long histories far faster (one doc went from failing after 17 hours to 9 seconds), same file format as v2: https://automerge.org/blog/automerge-3/.
  - Storage is chunked: snapshots (compacted state keyed by heads) and incremental changes (keyed by change hash); automerge-repo compacts occasionally: https://automerge.org/docs/reference/under-the-hood/storage/.
  - Modelling guidance: a doc suits a small group; hundreds of documents are fine, thousands (PushPin) cost sync overhead; embed a schema version number and apply migrations as hard-coded identical changes; do your own measurements; optimise at the network level instead of truncating history. Source: https://automerge.org/docs/cookbook/modeling-data/.
  - History truncation: reported as not prioritised because Kleppmann's experiment found savings small (search snippet from https://automerge.org/blog/, **snippet only**). The glossary also describes "compaction" as serialising state without history for sensitive-data purges (snippet only; semantics between that and automerge-repo's chunk compaction are **unverified**).
  - Binary assets: no Automerge-specific doc found; tldraw's `TLAssetStore` and Excalidraw `files` are the patterns to copy (Automerge guidance to keep blobs out is the standard practice, **unverified** as a statement from their docs).

### 2.6 Emerging standards for agent canvas tools

- **No cross-vendor standard exists** for a whiteboard MCP tool. What exists are product-specific servers: Excalidraw (official: `read_me` + `create_view`, checkpoints), Miro (official: SVG canvas read/create/update plus a composer "skill"), tldraw (community: shape-level CRUD plus `create_flowchart`), FigJam (via Figma MCP, undocumented), Heptabase (community read-only).
- **Shapes:** three families: (a) low-level per-shape CRUD (tldraw community, Miro REST); (b) a document in the native schema (Excalidraw `create_view` takes a JSON array of elements); (c) a higher-level vocabulary or DSL (`create_flowchart`, Mermaid, Miro SVG composer, sticky grid).
- **What LLMs get wrong / right (evidence is thin and mostly from practitioner write-ups, not benchmarks):** coordinates and spatial balance (tldraw; community skills advise grid cell formulas `x = MARGIN + col*CELL_W` and layout-engine delegation: https://skills.sh/acking-you/myclaude-skills/excalidraw, snippet only); stale ids when the canvas changes (tldraw sanitization); valid JSON with no comments or trailing commas and size limits (Excalidraw MCP); fixed aspect-ratio camera, minimum font sizes and gaps all have to be spelled out in the cheat sheet. LLMs do reliably produce structured text content, labels and relations when layout is taken from them (Mermaid route, Excalidraw TTD).
- **Reference docs on demand:** both Excalidraw (`read_me`) and Miro (`canvas_get_canvas_composer_skill`, once per session) put the format guide behind a tool the model must call first. This is a widely copied convention.

---

## 3. Vocabulary comparison

| System | Kinds / components | Content format | Layout ownership | Versioning | Validation |
|---|---|---|---|---|---|
| **canvas.compose v1 (plan)** | NOTE, CHECKLIST, CARD, TEXT, GROUP | Markdown (note body) | Compose layer (deterministic placement, renderer auto-fit, AUTO/EXPLICIT/USER) | Planned | Before publication, compiled to op log |
| JSON Canvas 1.0 | text, file, link, group (+ edges) | Markdown in text nodes | App-defined; x/y/width/height required integers | Spec 1.0 only, no per-document version field found | Spec only; no unknown-field rule |
| Excalidraw scene | rectangle, ellipse, diamond, text, arrow, line, image, frame, ... (open, grows) | Plain text; labels as bound text elements | Caller supplies x/y, or skeleton API fills size/bindings | Scene `version`; per-element `version` and `versionNonce` | Library `restore()` (**unverified**); MCP: valid JSON, 5 MB cap |
| tldraw | geo, text, note, arrow, draw, frame, embed... (extensible via ShapeUtil) | TipTap JSON `richText` | Caller x/y; helper stack/pack/align/distribute; note `growY` | Per-shape numbered migrations (note: 13 versions) | Props validators in schema; agent `sanitizeAction` |
| Miro items API | sticky, shape, card, text, frame, connector, image, doc, table, widgets | Minimal inline HTML in stickies | Caller x/y; frames as parents; bulk max 20, transactional | REST v2 API | Server-side; SVG composer ids are `data-miro-id` |
| A2UI | Text, Row, Column, List, Card, Tabs, Button, CheckBox, TextField... (catalog-defined) | Typed props; data-bound via JSON Pointer | Client renderer (flex-like containers with `weight`) | `version` on every message; `catalogId` | Post-generation; `VALIDATION_FAILED` + path |
| ChatKit widgets | Card, ListView, Text, Markdown, Title, Button, Image, Form, Row/Col/Box... | Typed props, Markdown component | Client renderer | Not found (**unverified**) | Widget builder + schema (details **unverified**) |
| MCP Apps / MCP-UI | Any HTML app | HTML | The app itself | Extension spec dated 2026-01-26 | Sandbox and CSP, not schema |
| Adaptive Cards | TextBlock, Input.*, Action.*, ActionSet... | Typed props | Host renderer | Card `version`; `requires` + `fallback` | Schema |
| Slack Block Kit | 21 block types | mrkdwn/plain/rich text objects | Slack | Not versioned per message (**unverified**) | Server limits (50 blocks) |
| Vercel AI SDK parts | `text`, `tool-*`, `data-*`, `source-*`... (developer-typed) | Developer-defined | Developer UI | Developer-defined | TS types; stream parse |
| Notion blocks API | 32 block types incl. to_do | `rich_text` arrays | Notion | API version header (**unverified**) | Server; 2,000 chars, 100 blocks per call |

---

## 4. Adopt verbatim vs borrow vs avoid

### Adopt verbatim (standards or de facto standards, drop-in)

1. **GFM task list syntax** (`- [ ]` / `- [x]`) for the markdown rendering and export of CHECKLIST (https://github.github.com/gfm/).
2. **JSON Canvas 1.0 field names and meaning** for import/export (`id, type, x, y, width, height, color, text, label`; 1-6 colour presets; array order = z-order on export) (https://jsoncanvas.org/spec/1.0/). MIT.
3. **A2UI error shape** for validation failures: `{code:"VALIDATION_FAILED", path:<JSON pointer>, message}` plus a `catalogId` and `version` on every call (https://a2ui.org/specification/v0.9-a2ui/).
4. **Fractional-indexing strings** for element order (same approach as Excalidraw `index` via rocicorp's library, and Figma) (https://raw.githubusercontent.com/excalidraw/excalidraw/master/packages/element/src/types.ts, https://www.figma.com/blog/realtime-editing-of-ordered-sequences/).
5. **RFC 6902 semantics** only if we ever need an update language; otherwise use id-addressed property writes, which our op log already is (https://datatracker.ietf.org/doc/html/rfc6902).
6. **MCP tool result conventions** where we expose to external MCP clients: `structuredContent` plus `outputSchema`, `resource_link` with `audience` annotation (https://modelcontextprotocol.io/specification/2025-06-18/server/tools).

### Patterns to borrow

- **Skeleton to full element expansion** (Excalidraw skeleton API).
- **Stable ids supplied or derived by the host** (Excalidraw `regenerateIds`, tldraw `ensureShapeIdIsUnique`, Miro `data-miro-id`); never trust model-minted ids.
- **Data part with id reconciles in place** (AI SDK) for the receipt.
- **Visible-to-model vs renderer-only split** (Apps SDK `structuredContent` vs `_meta`): return compact receipts to the model (ids, counts, bounds) and keep layout detail out of the model context.
- **Format guide via a tool or description** the model reads first (Excalidraw `read_me`, Miro composer skill).
- **Atomic batch with a cap** (Miro bulk: 20 items, transactional; Slack: 50 blocks).
- **Mode enum for sizing** (OOXML, Slides, tldraw `autoSize`, Excalidraw `autoResize`) and shrink-then-grow with a font floor (tldraw note: min 14; Excalidraw sticky).
- **`fallback` and `requires` for vocabulary growth** (Adaptive Cards).
- **Working-area frame plus rounding** for any coordinate the model does emit (tldraw).
- **Per-schema migrations with numbered versions** (tldraw note has 13) and a document-level schema version (Automerge cookbook).
- **Fixed per-kind widths and typographic floors** (tldraw note 200 px; Excalidraw MCP font minimums).

### Pitfalls to avoid (each seen in the sources)

- **Auto-fit with no opt-out** (Miro staff: not possible to override; users complain of tiny text).
- **Persisting a measured font scale** (PowerPoint/python-pptx: only the renderer can compute it).
- **Whole-element LWW for rewrites** (Excalidraw reconcile discards the entire loser; a concurrent move plus edit loses one).
- **Index-addressed update languages** (JSON Patch array indices shift).
- **Array order as z-order in a CRDT** (JSON Canvas; interleaving and fragile merges). Use fractional index.
- **Unbounded fractional index strings** (Figma acknowledges growth) - rebalance occasionally.
- **Interleaving of concurrent inserts** (Figma) - acceptable for notes, but do not rely on insertion order for checklist semantics; carry explicit order keys.
- **Letting tool transport drive artifact lifecycle** (streamUI flicker, quadratic transfer).
- **Pinning to a moving spec** (A2UI mime type differs between v0.8-ish and v0.9.1 docs; adaptive cards reject newer versions on old clients).
- **Letting the model re-use ids after delete** (Excalidraw MCP explicitly warns).
- **Inline metadata inside markdown lines** (Obsidian Tasks emoji format breaks on NBSP and variation selectors).
- **Hidden licence traps:** tldraw SDK needs the watermark or a business licence (do not lift code); ELK is believed EPL-2.0 (**unverified**) and BlockSuite is MPL-2.0 (file-level copyleft); A2UI, MCP Apps and ChatKit licences were not checked (**unverified**).
- **Treating a Miro-style grid as the whole answer:** Miro's arrangement is a one-shot action, and later edits do not reflow (community thread above). Decide explicitly what happens when the user adds to a compose group.

---

## 5. Recommendations against the plan

| Plan point | Verdict | Recommendation and evidence |
|---|---|---|
| **Closed, versioned vocabulary** | Confirm | Matches A2UI (`catalogId`, `version`) and Adaptive Cards (`version`, `requires`, `fallback`). Stamp `catalog`/`version` on every compose call; define behaviour for an unknown kind (reject with `VALIDATION_FAILED` at the kind's JSON pointer in v1; consider `fallback` kind such as `NOTE` for future kinds). Keep a small catalog; Microsoft Copilot restricts itself to stickies, Miro's growing composer is a warning about scope creep. |
| **v1 kinds: NOTE, CHECKLIST, CARD, TEXT, GROUP** | Confirm; define CARD carefully | NOTE/TEXT/GROUP map to JSON Canvas text/text/group. CHECKLIST has no JSON Canvas node (map to a text node with task-list markdown on export). CARD has no standard analogue except A2UI `Card` (container) and Miro `card` (title/description/fields, **unverified fields**); pin down whether CARD is "titled note with fields" or "container"; ambiguity there is the likeliest source of model confusion. Consider whether CHECKLIST is distinct from NOTE only by structure (items with ids), which is justified (section 5, markdown row). |
| **Create-only** | Confirm, with prep for update | Every comparator has an update story (A2UI `updateComponents` by id, AI SDK same-id data part, Miro `canvas_update_from_svg`, Excalidraw MCP checkpoints). Create-only is defensible for v1 if ids are stable and returned. Assign element ids deterministically from (message id, local key) so a later `update` or an idempotent retry cannot duplicate. Idempotency matters because retries are common and Miro bulk create is all-or-nothing. |
| **Markdown as note contract** | Confirm, constrain | JSON Canvas text nodes are markdown; Miro and tldraw are not. Specify a subset: CommonMark + GFM task lists, bold/italic/code/links/lists/headings, **no raw HTML, no images in v1**, length cap (Notion's 2,000 char element cap is a sane reference point). Do not store item metadata in the line text. Store CHECKLIST items as structured `{id, text, checked, order}` and render markdown from them; toggling a box is then one boolean key write instead of a text splice. If the body is human-editable, use Automerge text; if agent-only, a scalar string is acceptable (**design call**, Automerge distinguishes collaborative strings from `ImmutableString`). |
| **Compose layer owns deterministic placement** | Confirm | Strong convergent evidence that models should not choose coordinates (tldraw, Miro MCP grid tools, Excalidraw TTD via Mermaid). Keep determinism: same inputs + same canvas bounds => same placement. Use flow layouts (row-major grid with fixed gap) for N siblings; GROUP lays out its children. Heavy layout engines (ELK, dagre, d3) are unnecessary until edges exist. Document what happens relative to existing content: the only documented prior art is "give the model viewport context"; free-space search is our own design. Placement must be a pure function over the op log state, so peers agree. |
| **Renderer-side auto-fit for frameless content** | Confirm design, change what is persisted | Industry practice is a mode flag with the renderer measuring (OOXML, Slides, tldraw, Excalidraw). Persist width (fixed per kind), font size ceiling/floor and the mode, not a computed height or scale. Because a generator without font metrics cannot be exact (python-pptx), placement should reserve a conservative height bound per kind (clamped lines) so that renderer growth does not create overlap; if the renderer needs more than the reserved space, clamp with scroll or an overflow indicator instead of reflowing neighbours (avoids cascading moves across peers). Platform text-measure differences between Android and Skiko mean two peers may disagree on height; make height a local render concern, not a synced value. |
| **Ownership marker AUTO/EXPLICIT/USER** | Confirm; refine | Verified analogue: tldraw `autoSize` flips to fixed width on a side-handle drag. Ours is richer, which is fine, but specify transitions: any human move or resize sets USER (and disables auto-fit); agent updates never overwrite USER fields (a later `update` kind must respect it); EXPLICIT = agent gave explicit geometry. Store the marker as its own map key so a position write by a human does not rewrite content. Define what resets it (probably nothing automatic; an explicit "re-tidy" command). Consider LWW semantics when an agent and a human write the marker concurrently: human should win (lamport tie-break by actor class). Excalidraw's "do not overwrite the element a local user is actively editing" rule is a precedent. |
| **Compiled into the existing op log, validated before publication** | Confirm | Per-property ops are the right granularity (Figma, Automerge, Yjs guidance). Make compile all-or-nothing: validate the whole compose call, then publish one batch (Miro transactional bulk; tldraw applies on `complete`). Cap items per call (Miro 20, Slack 50 as references; pick our own cap). Return validation errors with JSON-pointer paths for model self-repair. Keep history small: write each element as a fixed set of key writes once at creation, then only deltas; do not re-write the whole element on every auto-fit remeasure (this is the biggest avoidable history growth, because auto-fit would otherwise produce an op per render). Keep large binaries out of the document (tldraw `TLAssetStore`, Excalidraw `files`). |
| **Single receipt: typed artifact refs on the same assistant message** | Confirm; no standard to adopt | Closest precedents: AI SDK `data-*` parts with an id that reconcile on re-send; A2UI DataPart in the A2A message; MCP `resource_link` in the tool result. None states the "chat reply is the receipt" invariant, so define it: one `canvas_artifact` part {artifactId, canvasId, composeVersion, kinds, elementIds, bounds, status} on the narrating assistant message; emit it as persistent (not transient) so it survives replay; re-sending the same `artifactId` updates it in place (this also gives the partial/streaming story: show "pending" then "published" on the same part). Do not carry layout details into the model's context (Apps SDK `_meta` split). Decide up front what happens when the canvas work fails after the narration was streamed: have the part carry `status` and the error. |
| **Open decision: markdown notes + JSON Canvas 1.0 boards, Excalidraw as design reference** | Recommend a split decision | See the note below. |

### Note on the markdown + JSON Canvas decision

Evidence for:

- JSON Canvas is an MIT, actively used interchange format, supported by Obsidian and several other apps, with libraries in eight languages; text nodes are markdown, which matches our note contract; groups exist; the format is tiny (https://jsoncanvas.org/spec/1.0/, https://github.com/obsidianmd/jsoncanvas/blob/main/docs/apps.md).
- Excalidraw as design reference rather than compatibility target is right: its element is a large style-heavy record with whole-element reconciliation (`version`/`versionNonce`), which conflicts with per-property LWW, and its scene is not designed around markdown or checklists.

Evidence against using it as the persisted live model:

- Required integer `x,y,width,height` (no unset width/height for auto-fit nodes), no sizing mode, no owner flag, no per-node version, no fractional order, no stated rule for unknown fields, no checklist/card kind (https://raw.githubusercontent.com/obsidianmd/jsoncanvas/main/spec/1.0.md). Preservation of extension fields by other apps is unverified.
- Array order as z-order does not merge in a CRDT; per-node arrays hide ordering conflicts.

Suggested resolution (design judgment, not prior art):

1. **Live model:** Automerge map of elements keyed by id, JSON-Canvas-compatible field names where they exist, plus our own fields under a clear namespace (`ownership`, `fit`, `index`, `kind`, `compose` provenance with artifact id and message id).
2. **Notes' markdown** stays the content contract, stored as a string field.
3. **JSON Canvas** is an import/export format: on export, order by `index`, round to ints, map CHECKLIST to a text node with task-list markdown, CARD to a text node, and drop or namespace non-spec fields; on import, set ownership USER or EXPLICIT (never AUTO) so imported geometry is not reflowed.
4. Revisit if the JSON Canvas spec gains an extension mechanism; there is no versioning policy documented in the repo (fetch summary) and it has been at 1.0 since 2024-03-11.

---

## 6. Unverified or open items (consolidated)

- Apple Freeform agent/scripting API: none found. Canva: not researched.
- FigJam and Miro AI internal placement algorithms; Microsoft Whiteboard/Loop developer APIs.
- tldraw `packShapes` algorithm; tldraw sync per-property merge claims (third-party doc is self-contradictory).
- Obsidian plugin manual-resize behaviour; whether Advanced Canvas fields survive other apps.
- Excalidraw `restore()` details; Anthropic Artifacts update-command semantics; Slack `chat.update` and fallback text; ChatKit widget storage/versioning.
- Licences: ELK/elkjs (believed EPL-2.0), A2UI, MCP Apps ext-apps, ChatKit not confirmed.
- Automerge history-truncation and compaction semantics beyond the storage doc (snippets only).
- All Miro REST field details beyond what search snippets showed (the canonical reference pages returned 404 at fetch time).
- Quantitative claims on how often LLMs get coordinates wrong: no benchmark found; evidence is practitioner statements only.
