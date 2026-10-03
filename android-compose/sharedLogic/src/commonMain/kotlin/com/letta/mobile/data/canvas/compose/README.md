# canvas_compose

This package is the compose layer of the canvas: an agent says WHAT goes on the board (notes,
checklists, cards, text, labelled groups) and never WHERE, and compose places it, sizes it and
compiles it into the one canvas op log. Read this before changing the wire contract, the markdown
subset, placement, the receipt, or before adding a kind.

The agent-facing reference is the guide `canvas_compose_guide` answers (`CanvasComposeGuide.kt`,
copied in `docs/reference/canvas-compose-v1.md`). The plan, its decisions and what changed while
building it are in `docs/design/canvas-compose-plan.md` ("As built"); the prior art it draws on is
`docs/design/canvas-compose-prior-art.md`.

Authoritative implementation files (sharedLogic `commonMain`, package
`com.letta.mobile.data.canvas.compose` unless named otherwise):

- Wire contract, caps, DTOs, codec: `CanvasComposeContract.kt`
- Strict input schema (the one the model is given) and its path-carrying checker: `CanvasComposeSchema.kt`
- Markdown subset reader: `CanvasComposeMarkdown.kt`; Cascade v2 block writer: `CanvasCascadeBlocks.kt`
- Reserved-height estimator: `CanvasComposeReserve.kt`; placement engine: `CanvasComposePlacement.kt`
- Compiler (request + scene -> ops + receipt, or refusal): `CanvasComposeCompiler.kt`
- The one entry point both hosts call: `CanvasComposeService.kt`
- Ids (`cmp-<artifactId>-<key>`, derived artifact ids, SHA-256): `CanvasComposeIds.kt`; colours: `CanvasComposeColors.kt`
- The guide: `CanvasComposeGuide.kt`
- Host wiring: `../CanvasComposeHosting.kt` (shared answer, refusal, telemetry), `../HostCanvasTools.kt`
  (Iroh host), `../CanvasExternalTools.kt` (desktop's direct App Server), `../CanvasStampedBatch.kt`
  (the one atomic `BatchOp`), `../CanvasToolContract.kt` (`compose`, `composeGuide`)
- Geometry owner and provenance on documents: `../CanvasModels.kt` (`CanvasGeometryOwner`,
  `CanvasComposeProvenance`), `../CanvasOps.kt` (`SetDocumentOp.owner`, `.compose`)
- Chat receipt: `../../chat/projection/CanvasArtifactReceipts.kt`; card:
  `sharedUI/.../ui/chat/surface/timeline/rows/ChatRowArtifacts.kt`; "Show on canvas":
  `sharedUI/.../ui/chat/surface/ChatCanvasActions.kt` and `sharedUI/.../ui/canvas/CanvasCameraRequest.kt`
- Auto-fit renderer: `sharedUI/.../ui/canvas/CanvasNoteAutoFit.kt` and `CanvasNotesLayer.kt`; world units:
  `sharedUI/.../ui/canvas/CanvasWorldUnits.kt`

## Architecture

Compose is not a second canvas. It is a compiler in front of the existing one:

```
agent tool call canvas_compose {items, artifact_id?, title?, canvas_id?, dry_run?}
  |  ExternalToolDispatcher (App Server) -> HostCanvasTools (Iroh host) | CanvasExternalTools (desktop)
  v
CanvasComposeHosting.answer  ->  CanvasComposeService.compose(input, canvasId, sceneJson, revision, toolCallId, check, publish)
  |                                   |
  |                                   +-- CanvasComposeCompiler.compile -> ops + receipt | refusal
  |                                   +-- check: CanvasBatchValidator (the only validator; CanvasSceneValidator per op)
  |                                   +-- publish: the host's own write path, as ONE stamped BatchOp
  v
receipt JSON (tool return)  ->  persisted on the TOOL_CALL timeline event  ->  CanvasArtifactReceipts.attach
                                                                               -> one card on the narrating message
```

- One op log, one validator, one document store: compose emits ordinary `set_document` (notes,
  checklists, cards) and `add_element` (TEXT, group frames and labels) ops.
- Both hosts call `CanvasComposeService.compose` and nothing else, so they cannot disagree. The
  Iroh host checks against its own scene and publishes through `HostCanvasBackend.publish`; the
  desktop app path goes through the live `CanvasSession.applyAgentBatch`, or the store at the read
  revision when no board is open.
- The batch is atomic on both: `CanvasStampedBatch.of` wraps the checked ops in one `BatchOp`
  (one relay message, one log append, one ack); inner ops keep their own id and lamport.
- Pure and host-independent: nothing here touches Compose, fonts or the network, so the Iroh host
  (sharedLogic without Compose) runs exactly what the apps run.

## Pipeline

`CanvasComposeCompiler.compile`, in order; any problem anywhere refuses the whole request:

1. Decode (`CanvasComposeContract.decode`): byte cap, catalog/version (`UNSUPPORTED_VERSION`), the
   strict schema (`CanvasComposeSchema.check`, JSON-pointer paths, `additionalProperties: false`
   everywhere), then the rules a schema cannot say: the item total across groups and unique keys.
2. Ids: `artifact_id` or the host's fallback (`CanvasComposeIds.derived(toolCallId)`, `a-` + 12 hex
   of SHA-256, so a runtime retry of one call is one artifact); keys default to the 0-based path
   (`i0`, `i3-c0`); every board id `cmp-<artifactId>-<key>` must be unique, group labels included.
3. Content: NOTE and CARD markdown through `CanvasComposeMarkdown.parse` (refusals carry the
   character offset), CHECKLIST entries to `todo` blocks, CARD title/fields to a `heading_2` and
   bold `Label: value` paragraphs; written by `CanvasCascadeBlocks` as Cascade v2 JSON.
4. Placement: `CanvasComposeReserve` books a conservative height per document (world units, same
   numbers the renderer draws in), `CanvasComposePlacement.place` lays the artifact out right of
   (or below, past 2 400 wide) the board's occupied bounds, as a row-major grid.
5. Idempotency: if the board already holds pieces of this artifact, their content signatures
   (geometry ignored) are compared: equal is a retry (no ops, `ALREADY_PUBLISHED_WARNING`),
   different is `ARTIFACT_EXISTS`.
6. Ops: group frames, then documents (`owner: AUTO`, colour, title, `compose` provenance), then
   texts and group labels (`_compose` on the element).

`CanvasComposeService` then runs the batch validator (`BOARD_REFUSED` with the board's own rule
names at the item's path), stops for `dry_run`, and publishes; a publisher that throws
`ComposePublishException` answers in contract terms (`UNAUTHORIZED`, `CANVAS_NOT_FOUND`), any other
failure is `BOARD_REFUSED` / `publish.failed`.

## Receipt

The tool's return IS the receipt (`ComposeReceipt`): artifact and canvas ids, revision, status,
title, bounds and the items by key and kind. It is persisted as the tool return of the TOOL_CALL
event, so nothing new is stored in the timeline; `CanvasArtifactReceipts.attach` derives one
`CanvasArtifactReceipt` per artifact and puts it on the message that narrates the call
(`UiMessage.artifacts`), stable across replay and hydration.

Items carry no board id: it is `cmp-<artifact_id>-<key>` (`ComposeReceiptItem.boardId`). That keeps
the largest receipt the caps allow at about 3.1 KB, under the 4 096 bytes above which
`message.list` ships a tool return as a 2 KiB preview (`CanvasComposeContract.MAX_RECEIPT_BYTES`,
`CanvasComposeReceiptSizeTest`). Receipts written before that still carry `id` and still read.

## Geometry and rendering

Every produced document has a frame and `owner: AUTO`. The renderer (sharedUI, C6) measures the
content and fits the card inside the reserved height (shrinking type a step, then growing, never
clipping) and never writes geometry back. A person's move or resize sets `owner: USER`, after which
the card is drawn at its stored frame; `apply_ops set_document` with a frame is `EXPLICIT`.
Everything on the board is in world units (density 1, no font scale), so the estimator and the
renderer agree on every display (`CanvasNoteWorldUnitsUiTest`, the Android Robolectric gate
`CanvasNoteAutoFitAndroidRenderTest`).

## Adding a kind (v2)

A kind is a contract change: bump the catalog version, keep v1 accepted.

1. Contract: add the kind to `ComposeKind` and a `ComposeItem` subclass with its fields; add caps as
   constants; add `VERSION = 2` to `SUPPORTED_VERSIONS` (keep 1) and say in the `VERSION` notes
   what v2 adds.
2. Schema: add its branch to `CanvasComposeSchema` (strict, every cap as `maxItems` / `maxLength`),
   and decide whether a GROUP may hold it. `CanvasComposeSchemaTest` holds the schema and the DTOs
   together.
3. Compiler: build its content (`Built`), size it (`CanvasComposeContract.width`,
   `CanvasComposeReserve`), emit its ops, and include it in the content signature used for retries.
   Anything drawn must pass `CanvasBatchValidator`; no new validator.
4. Rendering: if it is a document, the auto-fit renderer handles it; extend the reserve fixtures
   (`CanvasComposeFixtures`, its copies in sharedUI `CanvasNoteAutoFitFixtures` and the app's
   `CanvasNoteAutoFitAndroidRenderTest`) so the estimator gate covers it on JVM and Android.
5. Guide: add it to `CanvasComposeGuide` (the `when` over the enums will not compile without it),
   regenerate `docs/reference/canvas-compose-v1.md` from `build/canvas-compose-guide.md`, and add a
   problem example if it brings a new refusal.
6. Fixtures: a request fixture under `commonTest/resources/canvas/compose/v<n>/`, the receipt shape,
   the compiled golden; run the multi-card end-to-end gate (sharedLogic
   `CanvasComposeMultiCardEndToEndTest`, sharedUI `CanvasComposeEndToEndRenderTest`) with it in.
7. Host redeploy: the Iroh wrapper decodes typed ops and advertises the tools, so a new kind (or any
   new field on documents) does nothing on Iroh-served conversations until the wrapper is rebuilt.

Updating or deleting an artifact (v2 "update") needs per-property LWW for a document's geometry vs
content first (`letta-mobile-bglj6.16`), so a person's move and an agent's edit do not overwrite
each other.

## Tests And Review

- Contract and schema: `CanvasComposeContractTest`, `CanvasComposeSchemaTest`, `CanvasComposeFixturesTest` (fixtures round-trip)
- Markdown and blocks: `CanvasComposeMarkdownTest`, `CanvasCascadeBlocksTest`, desktop `CanvasCascadeBlocksOracleTest` (the library itself)
- Placement and reserve: `CanvasComposePlacementTest`, `CanvasComposeReserveTest`, `CanvasComposePlacementGoldenTest`
- Compiler and service: `CanvasComposeCompilerTest`, `CanvasComposeServiceTest`, `CanvasComposeCompiledGoldenTest`
- Hosts: `HostCanvasComposeToolsTest`, `HostCanvasComposeAtomicityTest`, `CanvasComposeToolTest`, `CanvasComposeHostParityTest`
- Guide: `CanvasComposeGuideTest` (every cap, code, colour and refusal example), `CanvasComposeGuideDocTest`
- Receipt: `CanvasArtifactReceiptsTest`, `CanvasComposeReceiptSizeTest`, `CanvasComposeReceiptEndToEndTest`
- End to end: `CanvasComposeMultiCardEndToEndTest` (dispatcher, host, notebook, timeline), sharedUI
  `CanvasComposeEndToEndRenderTest` (rendered board, Show on canvas; snapshots in `sharedUI/build/canvas-compose-e2e/`)
