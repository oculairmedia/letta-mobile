# Module :sharedLogic

Shared domain, transport and state-management code for Letta clients and controller hosts.
Start with repository/session contracts for resources, App Server clients and runtime packages
for turns, and timeline packages for durable history and live projections. UI-facing chat
ports and render models are portable contracts; composable painting belongs to `:sharedUI`.

The vendored `src/commonMain/resources/beads/openapi.v0-bd-1.3.2.yaml` is
`internal/httpapi/spec/openapi.v0.yaml` from steveyegge/beads tag `v1.3.2`,
commit `9b65b9e198acbcc98e7fd3a87a5d55baa065a572` (matching the installed
`bd version 1.3.2 (9b65b9e19)`). Its SHA-256 is
`9a33a349e5266bdba916246594a4fc457c63e39d7affb5acec9bd5b75acde4dd`.
The spec's `info.version` is its document revision, `0.1.0`, not the bd release.
Update this pin only for a deliberate bd contract upgrade.

Read each declaration's platform/source-set tags. `commonMain` holds shared contracts,
`jvmAndAndroid` adds JVM/Android integrations, and `jvmMain` provides JVM process/filesystem
facilities. Android, host-native and wasmJs actuals implement their declared seams; a package
appearing here does not mean its native runtime is available on every target. These package
overviews describe responsibilities, not deployment health or remote-server capabilities.

# Package com.letta.mobile.appserver

Host App Server command specifications and argument validation shared by the command-line entry points. Keep process launching in host bindings; construct validated arguments with `buildAppServerServeCommand` and configure them through [AppServerServeSpec](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.appserver/-app-server-serve-spec/index.html). Smoke-run scenarios live in the separate `:appserver-testkit` module.

# Package com.letta.mobile.data.chat.layout

Stable view identities for the shared chat content lane. The shell layout owns their order and protects the transcript and composer; chat presentation owns responsive docking, overlays and gestures. No transient run state is persisted in the layout.

# Package com.letta.mobile.data.a2ui

Agent-generated UI protocol models, data bindings, action dispatch and bounded surface state. Renderers consume these surfaces while approval submission and link policy connect actions to their owning session. Keep composable painting outside protocol state; integrate navigation and permission decisions through [A2uiLinkPolicy](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.a2ui/-a2ui-link-policy/index.html) and [ToolApprovalAdmission](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.a2ui/-tool-approval-admission/index.html).

# Package com.letta.mobile.data.agenteditor

Agent-editor state, validation and backend operations. Cached agents supply immediate display; a bounded detail load supplies the save baseline, preserving content and showing a retry on failure. The controller coordinates edits and exposes sections to the UI; hosts supply repositories and platform creation/presentation bindings. Keep platform navigation and widget state outside edit orchestration; supply resource operations through [AgentEditorBackend](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.agenteditor/-agent-editor-backend/index.html) and creation through [AgentCreator](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.agenteditor/-agent-creator/index.html).

# Package com.letta.mobile.data.agents

Agent grouping and recently used agent projections for navigation. Display-name groups and recency are derived independently of the platform rail or picker. Keep rail rendering and backend mutations outside these projections; feed grouping through [deriveAgentSpaces](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.agents/derive-agent-spaces.html) and recency through [RecentAgentsPolicy](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.agents/-recent-agents-policy/index.html).

# Package com.letta.mobile.data.api

Portable API failures and project request payloads retained by repository contracts. Their presence does not establish that a particular session has a remote HTTP route. Keep transport selection outside request payloads; repository adapters translate these request types and [ApiException](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.api/-api-exception/index.html) at their backend boundary.

# Package com.letta.mobile.data.attachment

Shared image-ingress policy and attachment budgets. Platform decoders supply image data; these policies constrain turn attachment counts, dimensions and payload size. Keep decoding behind platform adapters and attachment rendering in `sharedUI`; apply [ImageIngressPolicy](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.attachment/-image-ingress-policy/index.html) and [AttachmentLimits](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.attachment/-attachment-limits/index.html) before admitting image results.

# Package com.letta.mobile.data.beads

Portable bd serve HTTP client, capability handshake, event stream and typed failures. Supply a Ktor engine and credentials at connection time; the client has no wrapper route or host integration.

Issue projections and related mutation requests share wire models. Their operation-specific fields remain optional, while dependency entries retain JSON because detail and edge responses use different shapes. For nullable patch members, a Kotlin null omits the member and `JsonNull` explicitly clears it.

# Package com.letta.mobile.data.beads.wire.core

Serializable wire models for the pinned bd serve contract, including issue and journal records.

# Package com.letta.mobile.data.beads.wire.payloads

Serializable wire models for pinned bd serve operations and their shared payloads.

# Package com.letta.mobile.data.beads.wire.mutations

Serializable wire models for pinned bd serve mutation operations and their request and response payloads.

# Package com.letta.mobile.data.backend

`ConversationListingFactory` binds session conversation read paths to the shared listing; the cached repository consumes `ConversationListing` without depending on its implementation package. Hosts supply the factory at composition time.

Session capabilities and explicit route availability. Bound sources and absent routes let repositories report unsupported operations without silently choosing another backend. Keep unsupported routes explicit rather than silently falling back; session factories describe available operations through [SessionCapabilities](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.backend/-session-capabilities/index.html) and [SessionRouteMatrix](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.backend/-session-route-matrix/index.html).

# Package com.letta.mobile.data.canvas

Canvas scene/operation models, validation, projection, undo/history, synchronization and host tool integration. Notebook, filesystem and relay storage bindings are platform-scoped implementations alongside the portable contracts. Keep platform drawing and filesystem access behind adapters; implement [CanvasDocumentStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.canvas/-canvas-document-store/index.html), [CanvasSyncTransport](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.canvas/-canvas-sync-transport/index.html) and [CanvasPreviewRenderer](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.canvas/-canvas-preview-renderer/index.html) for their respective effects.

`CanvasStorageHealth.awaitDurableSave` is the completion barrier for asynchronous document stores. Sessions await durable persistence before settling journal ops or acknowledging drawing handoff, including recovery that generates no new drawing operations. Other mutations retain the existing ability to edit in memory after a reported storage failure. `saveFailureGeneration` counts diagnostics, including older background saves, and does not decide a drawing's durability. The completion barrier reports the current write's outcome, including swallowed failures in synchronous key-value backings. Retained `SAVE_FAILED` diagnostics preserve their first timestamp after recovery and do not block retries. `READ_ONLY` continues to refuse writes. Notebook saves await incremental chunk completion through the repository's observed storage within a bounded deadline (a stuck write is reported and stays retryable), retaining history without relying on background save completion.

# Package com.letta.mobile.data.canvas.authority

Host-owned notebook grant transactions and reference policy. Windows hosts refuse grant administration before locking or changing files because directory durability cannot be confirmed; reads continue to deny absent grants. The file-backed store serializes reads, protected actions, provisioning and revocation across processes sharing the same protected directory. A grant is keyed by authenticated principal and exact document identity; storage membership is not authority. The host adapter requires read and reference for previews and read and edit for content-bearing editor operations; it checks the current grant through each save and flush. Hosts must bind authenticated identities and protect the grant directory from untrusted writes. Grant-bound operations permanently isolate the store from raw repository exposure. Activation after repository exposure is rejected because retained native links cannot be authorized. The wrapper rejects mixed grant/sync startup and offers explicit RPC-only grant configuration; existing sync-only deployments retain their legacy trust boundary. The pinned native announce hook neither authenticates a principal nor enforces incoming mutation rights. Multi-peer grant-aware sync remains unavailable.

# Package com.letta.mobile.data.canvas.bubble

Persisted layout preferences for canvas-attached chat bubbles. These describe user layout choices rather than owning chat or canvas session state. Keep conversation lifecycle and transcript state outside layout persistence; bind saved bubble geometry through [CanvasBubbleLayoutPrefs](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.canvas.bubble/-canvas-bubble-layout-prefs/index.html) and [CanvasBubbleLayoutCodec](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.canvas.bubble/-canvas-bubble-layout-codec/index.html).

# Package com.letta.mobile.data.canvas.compose

The versioned semantic canvas composer: validation, Markdown conversion, geometry reservation, placement, compilation and receipts. It converts content-oriented requests into canvas operations; renderers draw and fit the resulting content. Keep pixel rendering outside semantic composition; extend accepted requests through [CanvasComposeContract](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.canvas.compose/-canvas-compose-contract/index.html) and compile them with [CanvasComposeCompiler](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.canvas.compose/-canvas-compose-compiler/index.html).

# Package com.letta.mobile.data.canvas.library

Canvas library state and operations shared by hosts. `CanvasLibraryController` retains loaded boards when storage fails, reports a retryable error and serializes library operations; `CanvasLibraryState` is its observable content and loading state. Hosts supply storage, archive and synchronization bindings and present the error. Keep windowing and platform storage construction outside this package; retries reload state rather than repeating mutations.

`CanvasBoardOwner` coordinates one window's table and registry using the same config identity as shell layouts. Initial configuration loading adopts the startup owner; identity changes retire pages before publishing replacement services. `CanvasPageHold` retains one destination's session in an injected scope, reports recoverable acquisition failures, and retries only within its original table. Retirement is terminal: old canvas/conversation identifiers are never re-acquired in a replacement backend. Hosts of these controllers bind retention and render `CanvasPageState`; they do not implement this policy.

# Package com.letta.mobile.data.canvas.mount

Host-owned persistence for semantic canvas mounts. `AuthorizedCanvasMounts` is the only entry point from outside this package: every call carries a `CanvasMountRequest` (a `CanvasMountPrincipal` built only from verified host connection identity, plus the `CanvasMountScope` it addresses; agent and conversation ids are targets, never the actor) and is checked by a host-supplied `CanvasMountAuthority` in a fixed order: input bounds (non-blank, at most 512 UTF-8 bytes per id), then scope before storage is touched, then the proposed canvas before any mutation, then the canvas read from storage before it is returned. Abstaining, denying or throwing all deny. `CanvasMountResult.Denied` carries nothing, so a denied caller cannot tell an existing record from an absent one or learn its winner; storage failures become `CanvasMountResult.StorageFailure` with a fixed `CanvasMountFailure` reason and never a path, message or id, while cancellation propagates. When no record exists, a bind stores the caller's own authorized proposal even if the authority then refuses to reveal it; when one exists, a bind writes nothing. A mutation that failed before its commit point (the atomic record replacement) is a `StorageFailure` and changed nothing; one that failed or was cancelled at or after it is `CanvasMountResult.OutcomeUnknown`, which carries nothing and tells the caller to read back. Cancellation before the commit point propagates; after it the store still completes and returns the outcome.

The raw store below is internal: it trusts every value it is given and authorizes nothing. `FileCanvasMountStore` owns a `canvas-mounts/` directory beneath the existing protected backend product-state directory (beside `host-canvases.json` / `model-exposure.json`). Each agent default and each agent/conversation binding has a distinct SHA-256-named file; raw ids never form paths. Every record carries version, host and its exact key, so a foreign or misplaced file fails closed. An operation reads and validates only its own record, without enumerating, rewriting or imposing a byte limit on the population. An 8 KiB safety limit rejects an oversized record before reading or decoding its contents. The unreleased monolithic format has no production writer and is not migrated.

The first byte of the key hash selects one of 256 stripe lock paths in `canvas-mounts/locks/`, bounding both lock files and process-wide mutex owners per backend independently of the key population. Canonical stripe ownership plus the shared cancellable OS lock spans read/check/write across instances and processes. Keys in one stripe serialize; keys in different stripes operate independently. Distinct keys replace distinct files, so neither case can erase another key's update. Reads of absent roots or records return null without creating a directory, lock file or runtime owner. A read of an absent key creates nothing. A read of an existing record puts back a missing lock directory or stripe lock file before locking (a writer created them; they can be lost with the record kept, see Windows below); a lock path that is not a directory fails visibly. Mutations force temporary contents, atomically replace that key and sync its directory where supported. Where the host can confirm directory entries, mutations also sync the product-state parent, the mount directory and its locks directory, confirming newly created directory entries and stripe lock files, including retries after ambiguous creation barriers. Windows deliberately proceeds without ANY of those directory confirmations (record, mount directory and lock entries alike): after a power cut the newest binding may be missing and is set again, and a surviving record whose lock entries were lost is still read, the next read or write recreating them. An existing bind winner is reread and its directory entry resynced under the same stripe lock, without rewriting or replacing its already-forced contents. A failed barrier never acknowledges success. Corrupt/unsupported/duplicate/wrong-host or wrong-key state fails visibly for that key without replacement; unrelated keys remain independent. Semantic ids and document provenance are unchanged.

The admin RPC routes over this facade live in `com.letta.mobile.data.controller.node.iroh.mounts`; their wire names live in `com.letta.mobile.data.canvas.mount.wire`. No client adapter, preferences, UI or deployment is added here. A shell canvas slot's explicit object reference remains separate from a conversation default; S3 must decide their presentation precedence without rewriting shell layout or document provenance.

# Package com.letta.mobile.data.canvas.mount.lookup

`HostCanvasMountLookup` is the host canvas tools' read-only view of the canvas mounted for the agent and conversation the runtime stamped on a call, and `HostCanvasMount` its three answers (none, mounted, unavailable). The host canvas backend derives its grant on mount from this answer at every check. Ids are plain strings so the package imports nothing and the canvas package can depend on it without joining an import cycle; the mount routes implement it over the mount store.

# Package com.letta.mobile.data.canvas.mount.wire

`CanvasMountWire` names the canvas mount admin RPC methods, capability, params, statuses, reasons and refusal texts of `docs/reference/contracts/canvas-mount-v1.md`, shared by the host routes and a later client. Constants only: no behaviour, and the package imports nothing, so the iroh capability table can name the methods without joining an import cycle.

# Package com.letta.mobile.data.canvas.mount.client

The app's side of host-owned canvas mounts: which canvas a conversation shows, and the person's switch to another, for that conversation only. `CanvasMountClient` is the port to the host (typed `CanvasMountLookup` / `CanvasMountChange` answers covering found, none, denied, invalid input, unavailable and outcome unknown); `CanvasMountClient.Unsupported` is a host without mounts, where nothing is ever asked or changed. The admin RPC implementation is not here yet; the port's KDoc lists what it must map. `ConversationCanvasResolver` is the one answer for the board on screen and the agent's canvas tools alike: the mount when the host supports one, else the conversation's own board, never blocking a conversation's opening, resolving again on observation, after its own writes and after any `outcome_unknown`, and serializing calls so a stale answer never overwrites a write. `ConversationBoardSwitch` and `CanvasBoardSwitchGate` hold the switch rule (the board on screen is saved before another replaces it; a failed save keeps it). `DeviceCanvasMountClient` holds mounts on this device only, in memory for the app session, for a host without mounts; `DeviceFallbackCanvasMountClient` is the one place that picks it or the host's client by the host's `supported` flow, and its `CanvasMountReach` tells the screen which one answers. A mount that reaches this device only never reaches the agent's canvas tools (`ConversationCanvasResolver.mountedCanvas` answers null). `CanvasModeFocus` is the window's record of the conversation on screen and whether it is in canvas mode, and `ConversationCanvasPicker` turns a side-menu pick into a mount on that conversation, or into the host's ordinary open when none is on screen. A conversation's document and its `conversationId` are never rewritten.

# Package com.letta.mobile.data.hoststate

Host-owned sidecar transaction primitives shared by notebook grants and mount storage: one canonical-path coroutine owner, cancellable interprocess locking and forced atomic file replacement. `HostDirectoryDurability` centralizes directory-channel support and synchronization. On Windows, mounts proceed with forced atomic contents but unconfirmed directory entries (a lost newest binding is set again); notebook provisioning and revocation refuse before locking or touching files, while reads remain available. The wrapper refuses notebook sharing at startup on unsupported hosts. Other hosts retain directory synchronization. Trusted administrators must protect the parent directory, which must already exist.

# Package com.letta.mobile.data.canvas.mutations

Host-side notebook mutation callback lifecycle. The internal fence serializes native callback execution with cancellation or timeout withdrawal, so a queued mutation cannot outlive the authority transaction that accepted it. Grant decisions and persistence outcomes remain with their existing owners.

# Package com.letta.mobile.data.canvas.names

The `canvas_*` tool names on their own, for code that only names the tools (a tool step's presenter) without depending on the canvas model. Keep tool definitions and schemas in the canvas contract; read the names through [CanvasToolNames](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.canvas.names/-canvas-tool-names/index.html).

# Package com.letta.mobile.data.canvas.notes

Independent notebook Markdown editing through [NotebookNoteSession](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.canvas.notes/-notebook-note-session/index.html) and the suspend [NotebookNoteBackend](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.canvas.notes/-notebook-note-backend/index.html) boundary. A session is permanently keyed by the existing notebook document ID; hosts create separate sessions when switching documents and await its prepare-close result before replacing or removing the mounted document session. Explicit save/refresh retain drafts across conflicts, failures and suspended saves. Host access denial remains distinct from missing or unavailable storage, including when authorization changes during the initial workspace load. Baseline, applied mutation and durable acknowledgement remain distinct; persistence retry flushes accepted history without reapplying Markdown. A newer source is retained alongside the draft and requires explicit discard/reload before replacement.

The JVM/Android [LocalNotebookNoteBackend](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.canvas.notes/-local-notebook-note-backend/index.html) binds the existing local-owned NotebookLocalStore, validates its actual storage-generated hex identity and awaits persistCanvas. It provides no remote grant authority, polling, schema, alias or synchronization engine. CAS serializes with incoming updates within that repository, not across independent repositories or remote peers; later Automerge merges become visible on explicit refresh/save/flush. A successful barrier persists local history, not a distributed lock. Cancellation propagates and may leave an already applied mutation. A submitted replacement retains an indeterminate-write marker, so undoing to the old baseline and refreshing cannot acknowledge a clean close. Before another replacement, discard or close, the session makes one cancellable read and flush within a five-second reconciliation deadline; timeout, failed persistence or a post-flush conflict retains the marker and draft for an explicit retry. A durable snapshot in that conflict acknowledges older history, not the newer current state. Only a successful reconciliation flush clears the marker; it adopts only the submitted text or old baseline automatically, while unrelated peer content remains a conflict. There is no retry loop or non-cancellable I/O. Trusted host activation remains a separate integration.

# Package com.letta.mobile.data.canvas.references

Bounded read-only notebook reference previews over the existing Automerge document identities and item map. A mandatory trusted-host authority callback runs before each storage lookup; denial and unavailable authority never read storage. Index membership, UI state and document payloads establish no rights. No default policy or persisted grant model is supplied. Hosts must separately reauthorize an open-target request before mounting an editor.

One shared session explicitly refreshes a sequential immutable tree, with independent ancestor paths for sibling branches, explicit cycle/depth/size placeholders and hard depth/node ceilings. Every placeholder counts toward the total budget, and traversal stops inspecting source items at the cap. Unknown kinds and versions retain their opaque item data without resolving targets or executing plugins. Refresh withdraws old content while reauthorizing; supersession and cancellation cannot publish a stale tree. No polling or per-node jobs are created. This component remains dormant until trusted host authority and navigation are wired; it adds no transport or storage schema. Unlinking deletes only the keyed source item through the existing serialized store transaction; callers retain the existing explicit durability barrier.

# Package com.letta.mobile.data.canvas.plugin

Plugin element schemas, kind catalogs and property checks within canvas scenes. Clients can preserve, edit and show fallback elements without loading plugin implementation code. Keep plugin execution outside element interpretation; provide installed kind metadata through [PluginKindCatalog](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.canvas.plugin/-plugin-kind-catalog/index.html) while retaining fallback representations for unknown kinds.

# Package com.letta.mobile.data.channel

Channel display projections and page-controller state, plus notification delivery contracts. Injected sources supply live data; connection and recovery states remain distinct from channel content. Keep connection establishment outside library presentation; supply reads through [ChannelLibrarySource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.channel/-channel-library-source/index.html) and mutations through [ChannelsPageActions](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.channel/-channels-page-actions/index.html).

# Package com.letta.mobile.data.chat.approval

Approval-answer coordination, submission tracking and an outbox for answers awaiting delivery. Request identity is maintained independently of the screen displaying the approval. Keep permission decisions explicit and independent of screen lifetime; implement [ApprovalOutboxStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.approval/-approval-outbox-store/index.html) for persistence and [ChatApprovalAnswerHost](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.approval/-chat-approval-answer-host/index.html) for submission.

# Package com.letta.mobile.data.chat.branch

Conversation fork, edit-and-resend and rerun orchestration. The branch controller plans operations, records lineage and passes the resulting conversation and optional draft to its host opener. Keep platform navigation and wire encoding outside branch orchestration; connect creation through [ConversationForkGateway](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.branch/-conversation-fork-gateway/index.html) and opening through [BranchOpener](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.branch/-branch-opener/index.html).

# Package com.letta.mobile.data.chat.conversations

Conversation row actions every host runs on its backend: [ConversationManagement](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.conversations/-conversation-management/index.html) renames, pins, archives, deletes, recompiles and (through [ConversationBranches](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.conversations/-conversation-branches/index.html)) forks, edits and reruns over the host's chat gateway; the host keeps the list through [ConversationListHost](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.conversations/-conversation-list-host/index.html) (desktop's chat controller, the web's TimelineConversations owner). [AgentConversationFleet](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.util/-agent-conversation-fleet/index.html) retains per-agent paging and legacy read-through behavior; visible recents are a separate host opt-in, not an unfiltered raw window that worker transcripts can crowd out. The composer's `/` palette is built here too. Push invalidation is shared: `observeConversationInvalidations` retains typed dirty identities and coalesces queued bursts; `AgentConversationFleet.refreshForInvalidations` reads each owning agent once per batch and preserves other agents and loaded pages. Legacy fleets reconcile the roster; the bounded timeline owner reconciles only recents, pins and cached agents. Gateways expose existing channel conversation pushes to `TimelineConversations`, including the browser's channel bridge. Keep navigation, storage and the transport choice with the host.

The timeline owner bootstraps one visible recents page (30 rows) plus pinned agents' first pages, then reads agent pages on explicit focus or search demand. Search reads unvisited agents rather than narrowing title matches to recents. Pushes for unloaded agents retrieve only the changed row; loaded owners refresh individually. Reconnect refreshes recents, pins and cached agents only. A partial list is loaded but not globally authoritative, so missing recents never prune tabs or remembered selections. Completed agent page sequences carry an agent-scoped authority set; shell reconciliation prunes only known rows and last-active memories for those owners. A refreshed full first page is not proof of completeness, even when older pages remain cached. Remembered ids for incomplete owners still resolve through the chat port's targeted lookup instead of silently falling back to a newer row. Legacy hosts (unsupported capability or agent-free raw rows proving the flag was ignored) retain eager fleet reads with one fallback telemetry event per owner. Bootstrap telemetry reports start, first published rows, and completion using monotonic elapsed milliseconds. Desktop and Android retain their existing eager fleets until their separate migration.

# Package com.letta.mobile.data.chat.conversations.listing

Session-local bounded conversation listing policy used by the timeline owner: visible recents, pinned first pages, focused-agent cursors and explicit search demand. Gateway adapters own wire shapes and raw legacy detection; this package owns bounded refresh and merge behavior. No platform or UI dependencies.

# Package com.letta.mobile.data.chat.display

Client-side chat display preferences, font-scale policy, Markdown transcript export, and how a background task notification is classified and its command report split into sections. Presentation changes do not rewrite the server conversation. Keep server transcript mutation outside display preferences and exports; bind font persistence through [ChatFontScaleStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.display/-chat-font-scale-store/index.html) and supply transcript inputs through [TranscriptInput](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.display/-transcript-input/index.html).

# Package com.letta.mobile.data.chat.projection

Timeline-to-message/run/tool projections, notification presentation and canvas receipts. Grouping, action policy and row identity are shared so hosts do not reconstruct chat semantics independently. Keep durable timeline writes and composable painting outside projection; derive presentation with [buildChatRenderModel](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.projection/build-chat-render-model.html) and action policy with [messageActionAvailability](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.projection/message-action-availability.html).

# Package com.letta.mobile.data.chat.projection.meridian

Recognition of Meridian command invocations in tool arguments and output. Supported shell calls project back to their semantic tool identity for timeline presentation and receipts. Keep shell execution outside command recognition; represent recognized invocations with `MeridianInvocation` and command metadata with `MeridianCommandSpec`.

# Package com.letta.mobile.data.chat.recompile

Conversation system-prompt recompile requests and their Iroh RPC adapter. Recompilation is scoped to agent/conversation identity and is distinct from a normal chat send. Keep ordinary message sending outside recompile state; connect backend execution through [ConversationRecompileGateway](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.recompile/-conversation-recompile-gateway/index.html) or the [IrohAdminRpcLink](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.recompile/-iroh-admin-rpc-link/index.html) adapter seam.

# Package com.letta.mobile.data.chat.routing

Target-conversation selection when following an inter-agent message. Sender and recipient have separate conversation spaces, so navigation cannot assume one shared conversation identifier. Keep navigation and conversation creation outside recipient selection; callers supply conversation candidates to [pickOtherAgentConversation](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.routing/pick-other-agent-conversation.html).

# Package com.letta.mobile.data.chat.runtime

Chat-session state, reducers and host-facing runtime contracts. Backend selection, conversation loading, composer gates, interruption and viewport-follow policy live outside platform screen implementations. Keep platform ViewModels and composable rendering outside session state; provide backend effects through [ChatGateway](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.runtime/-chat-gateway/index.html), [BackendConfigStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.runtime/-backend-config-store/index.html) and [ChatRunInterruptHost](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.runtime/-chat-run-interrupt-host/index.html).

# Package com.letta.mobile.data.chat.send

Portable chat send orchestration shared by Android and Desktop. `ChatSendCoordinator` owns lifecycle serialization, agent/generation gates and ordered routing. `ConversationTurnRegistry` is the sole conversation/alias index; `ConversationTurnState` retains each conversation's identity, replay fences and delivery evidence. Incoming ownership and delivery policies operate on those existing objects, and `IncomingTurnProjection` writes transport content and retains the existing pre-conversation delta buffer. `ConversationChatUi` applies the single dynamic foreground rule at each effect, after suspending writes; no active selection remains permissive, while global disconnect callbacks retain their global semantics.

`QueuedSendDriver` owns scheduling through the existing [ChatSendQueue](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.send/-chat-send-queue/index.html); `CoordinatorQueueTurns` adapts transport readiness, dispatch and abort under the coordinator's lifecycle boundary. `TurnTerminalPolicy` evaluates completion/failure evidence, `TurnSettlement` applies ordered timeline/UI effects, and `TurnSettlementOwnership` retires identity and pending send ownership. Mandatory local retirement precedes queue release; captured cancellation and caller/scope liveness gate the drain, while ordinary write failures remain retryable. Runtime audit batching uses the injected sink and existing caller scope.

Lock order is coordinator lifecycle mutex, registry index lock, then per-turn identity lock. Registry/index and identity critical sections do not suspend, acquire the lifecycle mutex, or call presentation callbacks. No collaborator creates an independent lifecycle scope or a second ownership index. Extend transport events through the existing subscription/generation gates, resolve the conversation before projection, preserve replay fences, and route visible effects through [ChatSendUiSink](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.send/-chat-send-ui-sink/index.html) via the same foreground rule. Timeline, transport, runtime recording and presentation remain injected capability seams; these internal policy helpers introduce no plugin runtime or mandatory Canvas dependency.

Retention remains unchanged: registry aliases are capped at 64, settled OTIDs at 16, settled-run and assistant-run evidence sets at 8, deduplication at 512 and tracked conversation states have a soft cap 32. The existing pre-conversation delta deque, runtime audit channel, client send queue and turn-fence history retain their existing unbounded policies; this extraction does not add a new retention policy.


# Package com.letta.mobile.data.chat.sentruns

Which run each message this client sent started, by identity only. The send pipeline's turn state publishes an entry when the send's echo pairs its otid with a run, adds every later id of the same turn (a durable id promoting a provisional one), and ends the entry when the turn completes, fails, is stopped or is replaced. An echo that arrives only after its turn settled is still paired, as an ended run, when that run is among the recently settled ones. Entries are bounded; one whose run is in flight is never dropped. [IdentifiedChatSend](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.sentruns/-identified-chat-send/index.html) is what a chat owner offers when it can name the message a send enqueued; the shared send coordinator offers one as `identifiedSend`, returning the otid read under the same lock hold that enqueued the message.
# Package com.letta.mobile.data.chat.tools

The tool presenter plugin contract: how a tool call reads as a timeline step on every host. A presenter claims calls by tool name (exact or globbed) and argument shape, and gives a cheap step line (verb, target, preview targets, failure) and an inset built only when the step opens. Shell tools hand each command they run to a second-level command presenter registry keyed on the program and its subcommands. Bundled presenters and the ones a plugin's manifest contributes register through the same calls; keep painting in `sharedUI`; register through [ToolPresenterRegistry](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.tools/-tool-presenter-registry/index.html) and implement [ToolPresenter](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.tools/-tool-presenter/index.html) or [CommandPresenter](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.tools/-command-presenter/index.html).

# Package com.letta.mobile.data.chat.tools.bundled

The presenters every host ships, one file per tool or CLI, each listed in its area's registration file (shell, files, tasks and agents, memory, canvas CLI) that [BundledToolPresenters](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.tools.bundled/-bundled-tool-presenters/index.html) combines through the same registry calls a plugin's contributions use, so adding a presenter never edits a list another area owns. Keep each presenter to its own tool and leave rendering to `sharedUI`; start a new one from [BashToolPresenter](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.tools.bundled/-bash-tool-presenter/index.html).

# Package com.letta.mobile.data.chat.tools.links

The URLs and file paths in a tool's output, found so a step's inset can make them links: a URL to open in the browser, a path (and the line it names) to open in the file preview. Plain words, numbers and bare file names never read as links; find them with [OutputLinks](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.tools.links/-output-links/index.html).

# Package com.letta.mobile.data.chat.tools.shell

A lenient reader of the shell scripts agents pass to their shell tools: quotes, separators, heredocs and `bash -lc` wrappers, never expanding or throwing. Tool presenters and the meridian recogniser read shell calls through it so they agree on what ran; read a call's script with [ShellTools](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.tools.shell/-shell-tools/index.html) and its commands as [ShellCommandLine](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.tools.shell/-shell-command-line/index.html).

# Package com.letta.mobile.data.chat.tools.steps

What the timeline does with steps once presenters have drawn them: which kind of work a run of steps adds up to ("Explored files, ran commands"), and which steps the person opened or closed against their default, remembered across reloads in a bounded set. Keep per-tool reading in the presenters; group steps with [ToolActivity](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.tools.steps/-tool-activity/index.html) and keep disclosure state in [ToolStepExpansions](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.tools.steps/-tool-step-expansions/index.html).

# Package com.letta.mobile.data.commands

Slash-command contracts and commands answered by the client itself. Navigation/local actions remain distinct from commands requiring active backend support. Keep arbitrary shell execution outside slash-command discovery; provide backend command capabilities through [SlashCommandsApi](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.commands/-slash-commands-api/index.html) and client commands through [ClientSlashCommands](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.commands/-client-slash-commands/index.html).

# Package com.letta.mobile.data.compaction

Compaction state and backend ports with App Server and admin-RPC adapters. Controllers expose progress and unsupported-host outcomes without requiring UI code to construct wire commands. Keep protocol construction outside compaction presentation; provide backend operations through [CompactionRepository](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.compaction/-compaction-repository/index.html) to [CompactionController](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.compaction/-compaction-controller/index.html).

# Package com.letta.mobile.data.composer

Composer autocomplete, mention spans and reasoning-effort choices. Reference identity and text ranges are independent of the field or suggestion popup rendering them. Keep text-field rendering and popup placement outside token rules; use [ComposerAutocomplete](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.composer/-composer-autocomplete/index.html) for edits and [MentionCatalog](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.composer/-mention-catalog/index.html) for available mentions.

# Package com.letta.mobile.data.context

Context-window readings, usage snapshots and breakdown loading for the selected conversation. Presentation models carry model/window information and distinguish supported usage readings from estimates. Keep provider billing and platform rendering outside context readings; supply measurements through [ContextBreakdownLoader](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.context/-context-breakdown-loader/index.html) and identify them with [ContextReadingKey](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.context/-context-reading-key/index.html).

# Package com.letta.mobile.data.context.estimate

Local transcript token and context-section estimation. These calculations operate over supplied content and are not authoritative provider billing or server measurements. Keep authoritative provider accounting outside local estimates; substitute estimation policy through [TokenEstimator](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.context.estimate/-token-estimator/index.html) and supply transcript data through [LocalTranscriptContext](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.context.estimate/-local-transcript-context/index.html).

# Package com.letta.mobile.data.context.limit

Context-limit choices, controller state and backend operations. App Server/Iroh adapters feed one workflow for supported limits and unsupported routes. Keep per-host copies of limit policy outside the controller; adapt backend effects through [ContextLimitRepository](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.context.limit/-context-limit-repository/index.html).

# Package com.letta.mobile.data.controller

App Server controller coordination, device status, approval decisions and synchronization budgets. Runtime registration and routed events cross this shared boundary before reaching individual client consumers. Keep process launching and platform UI outside controller policy; bind runtime control through [AppServerController](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller/-app-server-controller/index.html) and status publication through [DeviceStatusRepository](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller/-device-status-repository/index.html).

# Package com.letta.mobile.data.controller.capability

Capability advertisement and negotiation contracts with conservative defaults. Capability models describe supported operations; they do not prove what an arbitrary remote deployment implements. Keep authorization and deployment-health claims outside capability advertisement; provide offers through [CapabilityAdvertiser](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.capability/-capability-advertiser/index.html) and reconcile them with [CapabilityNegotiator](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.capability/-capability-negotiator/index.html).

# Package com.letta.mobile.data.controller.channels

Enabled-channel account caching and restoration across App Server connection generations. Restoration binds ingress to the current connection while preserving information needed for recovery attempts. Keep a second independent channel host outside restore coordination; reconnect existing account state through [ChannelAccountCache](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.channels/-channel-account-cache/index.html) and [ChannelRestoreCoordinator](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.channels/-channel-restore-coordinator/index.html).

# Package com.letta.mobile.data.controller.extras

External-tool registries, offers and dynamic tool-source contracts. Platform CLI runners bind execution while the shared registry describes which tools can be offered to a runtime. Keep platform execution behind host adapters rather than discovery metadata; contribute tools through [ToolSource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.extras/-tool-source/index.html) and execution through [HostExternalTool](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.extras/-host-external-tool/index.html) or [IrohCliRunner](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.extras/-iroh-cli-runner/index.html).

# Package com.letta.mobile.data.controller.extras.identity

Provenance of a tool caller's agent id. [CallerIdentity](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.extras.identity/-caller-identity/index.html) says whether a tool caller's agent id was vouched for. Anyone can build a claimed caller (`claimedToolCaller`); a verified one is made only inside sharedLogic, by the App Server dispatcher when its client owns the App Server connection (`AppServerClient.vouchesForRuntimeScope`) and by the Meridian live-call binder, and a guard test pins those two. Changing a verified caller's agent or conversation makes it claimed. Security-sensitive tools serve only verified callers.

# Package com.letta.mobile.data.controller.extras.relay

The app-tool relay: an Iroh host offers agents the tools that act on the app on screen under the app tools' own names, and forwards each call to the app viewing the caller's conversation over that app's authenticated control stream. Both peers negotiate it in the auth exchange, each side relays only its own allow-list of tools, and the caller is the scope the host's App Server stamped, never model input. This package knows frames and routing only, never a particular tool, so the Iroh host and transport can depend on it; tools plug in from their own packages. Route host calls through [AppToolRelayHost](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.extras.relay/-app-tool-relay-host/index.html) and answer them in the app by installing an [AppToolRelayAnswerer](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.extras.relay/-app-tool-relay-answerer/index.html) on [AppToolRelayResponder](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.extras.relay/-app-tool-relay-responder/index.html).

# Package com.letta.mobile.data.controller.fanout

Runtime-scoped event fanout, inbound control-request tracking and stream-integrity monitoring. Generation changes, sequence gaps and replayed approval decisions are handled before delivery to consumers. Keep transcript interpretation and durable storage outside frame fanout; attach runtime connections through [RuntimeSessionLink](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.fanout/-runtime-session-link/index.html) and route events through [AppServerRuntimeEventRouter](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.fanout/-app-server-runtime-event-router/index.html).

# Package com.letta.mobile.data.controller.node

Node identity, client/server contracts and relay configuration. These abstractions describe controller nodes without requiring every shared target to instantiate a native Iroh endpoint. Keep platform endpoint construction outside shared node contracts; supply implementations of [NodeClient](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.node/-node-client/index.html), [NodeServer](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.node/-node-server/index.html) and [NodeIdentity](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.node/-node-identity/index.html).

# Package com.letta.mobile.data.controller.node.agentfiles

A bounded, change-aware view of the local backend's agent records on disk. Admin reads such as the agent count, the hidden-agent set and the block index use it so they stat each record and parse only the ones that changed, instead of re-reading the whole store on every request. Keep record parsing for a served page in the readers; this package holds only the few fields kept per file and never covers more than its entry cap.

# Package com.letta.mobile.data.controller.node.listcache

Full App Server lists the host holds and reuses while the files they are built from are unchanged, so a client paging a list or reading it agent by agent costs one App Server fetch per change rather than one per request. A held list is dropped by a host write, by a change in its files' signature or after a short age bound; a list too large to hold falls back to the request's own fetch. Keep the per-request slicing, which must match the App Server's own paging, with the admin handlers that serve it.

# Package com.letta.mobile.data.controller.node.iroh

JVM/Android Iroh controller hosting: authentication, pairing, viewer fanout and explicit admin-RPC handlers. Native App Server commands and read-only local storage are separate ownership tiers; sensitive workspace/plugin operations use dedicated handlers. Keep product UI and direct backend-file mutation outside protocol handling; add routes through [AdminRpcRoutes](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.node.iroh/-admin-rpc-routes/index.html) and inject trust/storage policy through [IrohAuthPolicy](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.node.iroh.auth/-iroh-auth-policy/index.html) and [PairedPeerStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.node.iroh/-paired-peer-store/index.html).

Host construction separates endpoint network/identity binding (`IrohEndpointBinding`) from per-peer authentication (`IrohConnectionAuthentication`). Admin routes receive native sources, change notifications and pairing dependencies via `AdminRpcNativeSources`, `AdminRpcNotifications` and `AdminRpcPairing`, alongside host bindings in `AdminRpcHostServices`; omitted optional sources continue to fail closed.

`conversation.list` supports opt-in `visible_only: true` for unfiltered requests without `summary_search`. It shares the held full list with per-agent pages, removes agent-free, hidden-agent, hidden-conversation, archived and unauthorized rows before pagination, and returns newest-first recents (default 30). `after` names a visible conversation; unknown cursors restart from the top. Limits retain App Server integer/negative-prefix semantics; absent or invalid limits default to 30 in this mode. Legacy unfiltered, per-agent and search requests are unchanged. Visible mode requires the host's local agent visibility index; without it the opt-in request fails with `capability_unavailable` rather than return potentially hidden-agent rows. Above the held-list cap or without a usable signature, recents fetch the full list without retaining it rather than filter a truncated raw page.

# Package com.letta.mobile.data.controller.node.iroh.secrets

The secret request admin RPC routes and the agent's `secret_request` tool on the JVM host. [SecretRequestFeature](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.node.iroh.secrets/-secret-request-feature/index.html) builds both only for a paired-peers-only auth policy (as the caller passes it) with a keystore provider and an explicit item scope, and nothing in production calls it yet; the human principal comes from the serving connection (a paired device holding `admin.full`), the agent principal only from the runtime-stamped tool call, and no route returns or logs a value.

# Package com.letta.mobile.data.controller.node.iroh.discovery

JVM/Android host discovery primes externally created local conversations before wrapper list requests. It bounds and throttles retrievals, invalidates held lists after successful priming, and leaves authorization and output filtering to the owning admin handler.

# Package com.letta.mobile.data.controller.node.iroh.timing

Per-call phase timing for the JVM/Android host's admin RPC router. A handler marks the time it spends waiting on the App Server, discovering conversations or reading the local backend, and a slow call's `handler.slow` event reports those phases and the remaining wrapper work separately. Timing never changes a response.

# Package com.letta.mobile.data.controller.node.iroh.auth

JVM/Android authentication policy values and operator-configuration resolution for Iroh hosts. The sealed `IrohAuthPolicy` contract distinguishes bearer credentials, a vetted peer allowlist, pairing-only access and explicit anonymous test mode; `IrohAuthPolicy.resolve(...)` returns a secure policy, a warned test-only selection or a refusal. This leaf package owns configuration validation and pre-authentication decisions; it must not depend on endpoint lifecycle, verifier state, pairing persistence or RPC handlers. Hosts provide the policy to their connection/authentication boundaries, which own verification, rate limits and revocation.

# Package com.letta.mobile.data.controller.reconnect

Reconnect supervision and forwarding App Server clients. Backoff, synchronization and external-tool registration follow replacement connections while callers retain a stable client boundary. Keep platform suspend detection behind [SystemSuspendSignal](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.reconnect/-system-suspend-signal/index.html); attach session behavior through [ReconnectingClientListener](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.reconnect/-reconnecting-client-listener/index.html) and tool restoration through [ExternalToolRegistrar](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.reconnect/-external-tool-registrar/index.html).

# Package com.letta.mobile.data.controller.registry

Runtime records and registry coordination. The in-memory implementation supports ephemeral state and tests; persistence must be supplied rather than inferred from the interface. Keep durable persistence assumptions outside the in-memory registry; consumers depend on [RuntimeRegistry](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.registry/-runtime-registry/index.html) and coordinate mutations through [RuntimeRegistryCoordinator](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.controller.registry/-runtime-registry-coordinator/index.html).

# Package com.letta.mobile.data.desktopshell

Portable shell layout, conversation-tab ordering, persistence contracts and tab search. Desktop/browser shells reuse this state without depending on a windowing toolkit. Keep AWT, browser widgets and platform navigation outside layout policy; persist through [ShellLayoutStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.desktopshell/-shell-layout-store/index.html) and derive changes through [ShellLayoutReducer](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.desktopshell/-shell-layout-reducer/index.html).

# Package com.letta.mobile.data.diff

Unified-diff parsing and presentation models. Consumers classify additions, removals and context without interpreting raw diff text inside UI components. Keep filesystem mutation and patch application outside parsing; consumers interpret [UnifiedDiff](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.diff/-unified-diff/index.html) and `DiffLine` values in their own viewer or editing workflow.

# Package com.letta.mobile.data.document

Shared document operations, access control, presence, storage ports and bounded history. Canvas and other document kinds build on these mutation/history primitives. Keep platform painting and storage mechanisms outside document semantics; add document behavior through [DocumentReducer](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.document/-document-reducer/index.html) and effects through [DocumentStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.document/-document-store/index.html), [OpLog](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.document/-op-log/index.html) and [PresenceTransport](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.document/-presence-transport/index.html).

# Package com.letta.mobile.data.document.markdown

A Markdown text/title document kind over the shared document primitives. Persistent panels can use it without adopting the canvas scene model. Keep canvas-specific elements and text widgets outside the Markdown document kind; integrate shared backing through [openMarkdownDocument](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.document.markdown/open-markdown-document.html) and apply operations through [MarkdownReducer](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.document.markdown/-markdown-reducer/index.html).

# Package com.letta.mobile.data.dreaming

Dreaming settings, history, notifications and controller state. App Server and admin-RPC adapters expose available reflection operations through one backend contract. Keep runtime ownership and platform presentation outside dreaming controls; supply operations through [DreamingRepository](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.dreaming/-dreaming-repository/index.html) and history through [DreamHistorySource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.dreaming/-dream-history-source/index.html).

# Package com.letta.mobile.data.health

Server health probing, state and repository contracts. Service availability is reported separately from whether a conversation currently has an active turn. Keep active-turn state distinct from reachability; provide probes through [AppServerReachability](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.health/-app-server-reachability/index.html) and consume updates through [ServerHealthListener](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.health/-server-health-listener/index.html).

# Package com.letta.mobile.data.home

Renderer-independent Home/fleet projections and page controller. Injected sources, pins, recent navigation and agent activity combine into state for the shared Home page. Keep host widgets and duplicate fleet calculations outside home state; bind resources through [HomePageSource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.home/-home-page-source/index.html), pins through [HomePinStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.home/-home-pin-store/index.html) and actions through [HomePageActions](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.home/-home-page-actions/index.html).

The session source keeps catalogs live while Home is hidden. Page entry reuses completed aggregates for 60 seconds; explicit refresh forces one reload. Unsupported figures are remembered until the host, connection generation or advertised capabilities change.

The session source reloads backend figures on graph changes and transport reconnection. Page-entry and error Retry requests share an in-flight load; hosts must not add their own refresh timers or reconnect handlers for these figures.

# Package com.letta.mobile.data.lanes

`ConversationLane` derives exact home/inbox/work purposes and bead/run references from existing conversation tags; references grant no read or send rights, work conversations are viewable but never message targets, and automatic selection anchors on home and never falls back to inbox or work. `ConversationLaneProjection` is session-graph-owned unread/activity state that shell rows render and the shared transcript acknowledges when it displays an arrival; opening, swapping or closing a view never changes it. `LaneActivitySource` is the one input: committed inbox arrivals from the capability-gated `conversation_activity` producer (mr49w), deduplicated by agent/conversation/source identity. Without the host capability unread is unknown, never zero, and metadata `conversation_updated` invalidations never count as arrivals. No history fetch or per-agent subscription is issued. Verified forwarding provenance (os2pc) is not derived here.

# Package com.letta.mobile.data.lens

The Work/Play presentation lens over common underlying data. It changes vocabulary and framing rather than creating separate agents, conversations or memory. Keep changes to underlying agents or memory outside lens selection; hosts map [LensDestination](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.lens/-lens-destination/index.html) and [WorkPlayMode](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.lens/-work-play-mode/index.html) to their navigation surfaces.

# Package com.letta.mobile.data.mapper

JVM/Android message-to-domain mapping and generated-UI extraction helpers. This compatibility facade is platform-scoped; portable chat/timeline projections live in their respective packages. Keep JVM compatibility facades out of portable dependencies; conversion callers supply [MessageMappingState](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.mapper/-message-mapping-state/index.html) to [toAppMessage](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.mapper/to-app-message.html) rather than introducing platform policy into shared models.

# Package com.letta.mobile.data.memory

Memory categorization and parity-page models/controllers. Categories carry semantic UI roles while access to block content stays behind repository/content ports. Keep graph I/O and fabricated availability counts outside parity projection; provide data through [MemoryParitySource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.memory/-memory-parity-source/index.html) and section reads through [MemoryParitySectionReader](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.memory/-memory-parity-section-reader/index.html).

# Package com.letta.mobile.data.memory.graph

Memory graph models, layout/force calculations and page/detail state. Loading full block content and committing edits are port operations rather than graph-renderer responsibilities. Keep drawing and unguarded persistence outside graph state; supply block content through [MemoryBlockContentPort](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.memory.graph/-memory-block-content-port/index.html) and mutations through [MemoryPageActions](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.memory.graph/-memory-page-actions/index.html) and [MemoryBlockLifecycleActions](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.memory.graph/-memory-block-lifecycle-actions/index.html).

# Package com.letta.mobile.data.memory.memfs

MemFS file/history/diff sources and browser/editor state, including an App Server source. Shared reducers/controllers handle loading, saving and conflict-aware editor transitions. Keep direct filesystem access and editor widgets outside the reducer; connect reads through [MemfsSource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.memory.memfs/-memfs-source/index.html) and changes through [MemfsPageActions](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.memory.memfs/-memfs-page-actions/index.html).

# Package com.letta.mobile.data.meridian

Meridian command catalog, parser/router and tool-offer modes. Requests are mapped to host tools and validated before endpoint or shell adapters execute them. Keep UI rendering and tool-policy bypasses outside command dispatch; route accepted calls through [MeridianCommandRouter](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.meridian/-meridian-command-router/index.html) and constrain them with [MeridianRateLimiter](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.meridian/-meridian-rate-limiter/index.html).

# Package com.letta.mobile.data.meridian.endpoint

Caller-bound Meridian tool service and request/response wire handling. Live-call tracking and token comparison support the local command endpoint; a display label does not authenticate a caller. Keep caller identity independent of display labels; bind authenticated callers through [MeridianCallerBinder](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.meridian.endpoint/-meridian-caller-binder/index.html) and expose tool execution through [MeridianToolsService](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.meridian.endpoint/-meridian-tools-service/index.html).

# Package com.letta.mobile.data.messaging

Inter-agent delivery routing and structured provenance. Stable sender/recipient relationships and delivery state remain separate from display-only labels used by presentation. Keep display labels out of routing trust decisions; carry identity and provenance through [AgentMessageClientId](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.messaging/-agent-message-client-id/index.html) and [AgentMessageProvenanceProjection](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.messaging/-agent-message-provenance-projection/index.html) when integrating [IrohAgentMessageRouter](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.messaging/-iroh-agent-message-router/index.html).

`IrohAgentMessageRouter` reserves identities before delivery and exposes bounded snapshots for the
JVM receiver's atomic receipt journal. Only definite application refusal releases an identity;
accepted and ambiguous attempts remain suppressive across restart. Inbox activity uses the separate
`conversation_activity_v1` capability and committed source/client identities; it is neither a
conversation-list invalidation nor a timeline message. See the conversation lanes contract for
capacity, observation-window and reconnect limits.

# Package com.letta.mobile.data.model

Shared dispatch and task-notification wire parsers live here so controller ingestion and chat projection consume the same interpretation without depending on each other.

`parseAgentTodos` reads authored TodoWrite and update_plan replacement plans for foreground and child agents; `todoProgress` counts those items without inferring progress from activity or worker lifecycle.

Shared models for agents, conversations, messages, models, tools and related resources, with normalization/compatibility helpers. A model's existence does not imply that every backend serves that resource. Keep network access and storage effects outside resource values; repository adapters map backend data to models such as [LettaMessage](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.model/-letta-message/index.html) and bind recency persistence through [RecentModelsPersistence](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.model/-recent-models-persistence/index.html).

# Package com.letta.mobile.data.model.provider

Provider-management domain models and model-route availability. Canonical route identity is shared by catalog composition, selection and management workflows. Keep credential access and network execution outside provider descriptions; adapters construct [ProviderDefinition](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.model.provider/-provider-definition/index.html) and [CanonicalModelRoute](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.model.provider/-canonical-model-route/index.html) values under [VisibilityPolicy](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.model.provider/-visibility-policy/index.html).

# Package com.letta.mobile.data.model.reflection

Reflection-settings-v2 values shared by dreaming repositories and UI. Model choice preserves the wire distinction between an omitted legacy field, inherited agent model and explicit catalog handle; last outcomes retain skipped and failed runs that do not appear in memory history.

# Package com.letta.mobile.data.model.provider.composer

Pure projection of provider/catalog inputs into a canonical catalog. Cache and selection state enter explicitly rather than hiding inside platform picker code. Keep fetching and hidden caching outside catalog composition; supply [CatalogScope](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.model.provider.composer/-catalog-scope/index.html) inputs to [CanonicalCatalogComposer](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.model.provider.composer/-canonical-catalog-composer/index.html) and consume [SelectionResolution](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.model.provider.composer/-selection-resolution/index.html) results.

# Package com.letta.mobile.data.model.provider.rpc

Provider-management RPC contracts, granular capabilities and client gating. Callers can distinguish supported management operations from host limitations before mutation. Keep permission bypasses and host UI outside provider RPC; supply requests through [ProviderRpcTransport](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.model.provider.rpc/-provider-rpc-transport/index.html) with explicit [ProviderRpcAuthContext](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.model.provider.rpc/-provider-rpc-auth-context/index.html) and availability.

# Package com.letta.mobile.data.model.provider.wire

Provider-management JSON models, codec, errors and domain mappers. Wire normalization and mutation serialization are centralized rather than repeated in screens. Keep RPC execution and secret presentation outside wire encoding; translate mutation payloads through [ProviderManagementWireCodec](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.model.provider.wire/-provider-management-wire-codec/index.html) and represent secret writes with [SecretWriteValue](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.model.provider.wire/-secret-write-value/index.html).

# Package com.letta.mobile.data.modelvalidation

Validation of model handles for configuration/runtime flows. Syntactic validity is distinct from a model actually being installed or reachable. Keep reachability and installation claims outside syntactic validation; callers apply [ModelHandleValidator](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.modelvalidation/-model-handle-validator/index.html) before their own backend capability checks.

# Package com.letta.mobile.data.notify

Attention preferences, event gating and notification copy. Shared policy identifies events warranting attention; hosts supply operating-system notification presentation. Keep OS notification APIs outside attention policy; bind preferences through [AttentionPrefsRepository](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.notify/-attention-prefs-repository/index.html) and translate [AttentionDecision](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.notify/-attention-decision/index.html) into host effects.

# Package com.letta.mobile.data.observability

JVM-side Kotzilla monitoring bootstrap via reflective generated initialization. Desktop integration stays out of portable source sets and does not require common code to import JVM SDK classes. Keep JVM monitoring SDK dependencies outside common code; the desktop host starts the JVM-only reflective integration through [startKotzillaMonitoring](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.observability/start-kotzilla-monitoring.html).

# Package com.letta.mobile.data.onboarding

First-run agent setup tasks and onboarding models. These describe work offered to an agent, not a separate backend or agent identity. Keep agent creation and task execution outside onboarding suggestions; hosts interpret [OnboardingTask](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.onboarding/-onboarding-task/index.html) values produced by [AgentOnboarding](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.onboarding/-agent-onboarding/index.html).

# Package com.letta.mobile.data.objectverbs

Accepted object-verbs v1 values for canonical references, descriptors, read-only facets,
contribution metadata and typed invocation outcomes. The compatibility projection hides
unsupported kinds, versions and verbs. The core provider and session binding live in child packages; trusted hosts retain source resolution and permission checks.
The review contract is `docs/reference/contracts/object-verbs-v1.md` (letta-mobile-i5jdm.9).

# Package com.letta.mobile.data.pairing

Pair-invite parsing, flow state, device access and paired-host storage ports. Scanned data moves through explicit invite/session states before becoming a stored connection. Keep scanners, QR drawing and platform storage outside pairing state; provide effects through [PairingRedeemer](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.pairing/-pairing-redeemer/index.html), [PairedHostStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.pairing/-paired-host-store/index.html), [QrEncoder](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.pairing/-qr-encoder/index.html) and [BackendConfigSink](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.pairing/-backend-config-sink/index.html).

# Package com.letta.mobile.data.pairing.composition

The one pairing composition every client shares: the join flow, the invite session and this device's NodeId, built from the services a platform owns. Platforms implement [PairingPorts](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.pairing.composition/-pairing-ports/index.html) and never construct pairing objects themselves; transport choice stays in shared code through [PairingNetwork](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.pairing.composition/-pairing-network/index.html), with [IrohPairingNetwork](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.pairing.composition/-iroh-pairing-network/index.html) on JVM and Android and [RelayPairingNetwork](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.pairing.composition/-relay-pairing-network/index.html) in the browser. Build everything through [PairingComposition](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.pairing.composition/-pairing-composition/index.html).

# Package com.letta.mobile.data.platform

Heap and memory-pressure signals for shared consumers. Platform observers produce the signals so cache-release policy does not import operating-system monitoring APIs. Keep OS metric collection outside common policy; hosts publish pressure through [MemoryPressureHub](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.platform/-memory-pressure-hub/index.html) and consumers apply [HeapPressureGate](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.platform/-heap-pressure-gate/index.html).

# Package com.letta.mobile.data.plugin

Plugin manifests, capabilities, packages, registry transitions and settings/secrets templates. These describe installation and consent; platform package readers and host drivers supply execution where available. Keep native process execution and product widgets outside portable plugin state; provide packages through [PluginPackageReader](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.plugin/-plugin-package-reader/index.html) and action effects through [PluginActionInvoker](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.plugin/-plugin-action-invoker/index.html).

Runtime view bindings live on `InstalledPlugin` and change through `PluginRegistryReducer.configureView` / `unbindView`. Configuration selects trusted code only; it cannot consent, enable, install, or retarget an occupied mount. Configuration and unbinding honor the package-update guard. Canonical object content stays with its source.

# Package com.letta.mobile.data.plugin.catalog

Plugin catalog ports and management-page state. Controllers expose inspection and consent workflows while the connected host remains authoritative for installed state. Keep locally inferred installation authority outside catalog state; supply host-backed inventory through [HostPluginCatalog](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.plugin.catalog/-host-plugin-catalog/index.html) and mutations through [PluginsPageActions](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.plugin.catalog/-plugins-page-actions/index.html).

# Package com.letta.mobile.data.plugin.host

JVM host-side plugin lifecycle, process drivers, placement, quotas and telemetry. Launch/environment policy and capability checks apply at the host boundary rather than executing plugins inside product UI. Keep product rendering outside JVM plugin lifecycle management; bind execution through [PluginDriver](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.plugin.host/-plugin-driver/index.html), confinement through [ProcessConfinementSource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.plugin.host/-process-confinement-source/index.html) and canvas access through [PluginCanvasAccess](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.plugin.host/-plugin-canvas-access/index.html).

Runtime-provider metadata validation, immutable capture and runtime/contract selection live in commonMain, exercised by commonTest without importing an executor. The JVM `PluginDriverProviders` adapter supplies an explicit inventory to this shared selector; missing or ambiguous matches fault the plugin before construction. `PluginLaunchSpec` carries JVM package/settings/consent inputs without choosing an executor. Distribution assembly supplies providers; the host has no default executor.

# Package com.letta.mobile.data.plugin.host.process

JVM subprocess execution of LCP plugins, selected explicitly through `ProcessPluginDriverProvider`. `ProcessDriverOptions` owns launch confinement, caps, deadlines, health checks and restart policy; `ProcessPluginDriver` preserves guarded host calls and secret-scrubbed output. Keep executor selection outside this package and product features outside process lifecycle. This binding does not provide browser, service or JAR execution.

# Package com.letta.mobile.data.plugin.remote

JVM/Android admin-RPC adapters for plugin catalogs and live views. Operations follow advertised host support and report unavailability instead of opening an alternate connection silently. Keep independent fallback connections outside remote adapters; bind the existing admin connection through [PluginAdminLink](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.plugin.remote/-plugin-admin-link/index.html) and uploads through [PluginPackageUploader](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.plugin.remote/-plugin-package-uploader/index.html).

# Package com.letta.mobile.data.plugin.view

The shared lcp-view/1 bridge: handshake, validated messages, lifecycle, rate limits, CSP and host ports. Platform page containers bind rendering while the bridge defines supported method/permission boundaries. Keep platform WebView construction outside bridge policy; connect messaging through [PostMessagePort](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.plugin.view/-post-message-port/index.html), actions through [PluginViewTransport](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.plugin.view/-plugin-view-transport/index.html) and access decisions through [PluginViewConsent](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.plugin.view/-plugin-view-consent/index.html).

# Package com.letta.mobile.data.plugin.runtimeview

`RuntimeViewId` and `RuntimeViewMountId` come from `core:ids` and keep selection separate from mount ownership. `RuntimeObjectReference`, `RuntimeViewObject`, `RuntimeViewDescriptor` and `RuntimeViewBinding` provide a neutral native-view contract. Object schema ID/version, view-contract version and mount identity are independent. Full JSON content (including unknown fields) is read-only; source authority is an opaque part of the reference. Schema incompatibility, absent owners/objects/implementations and reported renderer failure use explicit fallback. Native implementations are supplied by trusted host code, never fetched from configuration.

# Package com.letta.mobile.data.plugin.wire

JVM LCP JSON-RPC codecs, framing, typed calls and sessions. NDJSON/WebSocket transports share protocol envelopes, deadlines, cancellation and capability/secret guards. Keep plugin lifecycle and permission expansion outside framing; supply bytes through [LcpByteSource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.plugin.wire/-lcp-byte-source/index.html) and [LcpByteSink](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.plugin.wire/-lcp-byte-sink/index.html) and dispatch allowed calls through [LcpHostHandlers](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.plugin.wire/-lcp-host-handlers/index.html).

# Package com.letta.mobile.data.presence

Agent/conversation run-presence state and reduction. Live status and avatar behavior consume these projections instead of inferring work state from message text. Keep text-derived guesses outside run identity and phase state; publish authoritative transitions through [ConversationRunPhasePublisher](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.presence/-conversation-run-phase-publisher/index.html) and retain them in [ConversationRunRegistry](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.presence/-conversation-run-registry/index.html).

ActiveTurnCoordinator owns one trusted conversation lifecycle using active-turn-v1 arbitration. ActiveTurnIdentity carries captured run/turn correlation; ActiveTurnApproval binds canonical request/tool IDs to transport validation. It serializes exact-turn progress, ordered open tools, approval reservations and stop gestures; retains retired identities and deletion tombstones; and publishes accepted approvals only after transport validation. Bind request callbacks to the captured session and turn, and return true only for transport acceptance. Dispose its coroutine scope on session teardown. A new lifecycle after deletion requires a new owner. Consumer migration from the existing publisher and observed-turn tracker remains in the platform slices; creating an owner alone does not rewire those consumers.

# Package com.letta.mobile.data.projects

Project page state, backend ports and controller operations. Host project summaries/identifiers are separate from issue-work repository capabilities. Keep issue-work capabilities distinct from project catalog access; provide backend operations through [ProjectsBackend](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.projects/-projects-backend/index.html) and pin persistence through [ProjectPinStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.projects/-project-pin-store/index.html).

# Package com.letta.mobile.data.repository

Cached repositories, change observers and channel/admin-RPC source adapters. Refresh, cursor and cache behavior uses injected persistence/remote boundaries; source tags identify remaining platform-scoped implementations. Keep platform storage engines and UI state outside repository policy; construct shared cached repositories with the source/cache contracts from `data.repository.api`.

# Package com.letta.mobile.data.repository.api

Repository interfaces and local/remote source ports for features and session graphs. `IAgentRepository.getFreshAgent` returns one detail result or failure independently of session observation flows; `force` bypasses the detail TTL, and callers own cancellation and deadlines. List rows never satisfy this contract. Paging/older-message contracts expose bounded access independently of a platform cache implementation. Keep transport construction and process-wide singleton policy outside resource contracts; implement ports such as [IAgentRepository](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.repository.api/-i-agent-repository/index.html), [AgentRemoteSource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.repository.api/-agent-remote-source/index.html) and [AgentLocalCache](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.repository.api/-agent-local-cache/index.html) in adapters.

# Package com.letta.mobile.data.repository.appserver

App Server repository adapters and local admin gateway. They translate shared repository/client contracts into commands on an existing session. Keep connection ownership and host UI outside repository adapters; bind an existing session through [AppServerLocalRepositoryTransport](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.repository.appserver/-app-server-local-repository-transport/index.html) and admin operations through [AppServerLocalAdminGateway](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.repository.appserver/-app-server-local-admin-gateway/index.html).

# Package com.letta.mobile.data.repository.iroh

Admin-RPC repository and chat-gateway adapters for Iroh sessions. Resource operations use the connected channel rather than creating a separate REST backend. Keep silent REST fallback and a second session lifecycle outside these adapters; bind RPC through the injected [IChannelTransport](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.transport.api/-i-channel-transport/index.html) and supply agent directory access through [IrohAgentRepository](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.repository.iroh/-iroh-agent-repository/index.html)'s `directoryProvider`.

# Package com.letta.mobile.data.repository.modelcontrol

Model/provider management state, selection, connection forms and OAuth flows. Shared controllers own workflows while platform HTTP actuals and injected RPC invokers supply effects. Keep platform picker rendering and duplicate host policy outside model control; provide catalog access through [ModelPickerSource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.repository.modelcontrol/-model-picker-source/index.html), admin calls through [AdminRpcInvoker](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.repository.modelcontrol/-admin-rpc-invoker/index.html) and OAuth HTTP through [ProviderOAuthHttp](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.repository.modelcontrol/-provider-o-auth-http/index.html).

# Package com.letta.mobile.data.repository.subagent

Parent Agent-tool dispatch/return correlation keyed by tool-call identity. The reducer backfills known context without inventing child identity, and a dispatch return does not declare the spawned child's lifecycle complete. Keep tool-return completion distinct from child-run completion; callers supply [ParentContext](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.repository.subagent/-parent-context/index.html) to [SubagentCorrelator](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.repository.subagent/-subagent-correlator/index.html) for identity correlation.

# Package com.letta.mobile.data.runs

Run-monitor controller state and run/step formatting. Sources provide execution records; inspection does not transfer ownership of the execution engine. Keep platform detail widgets outside run monitoring; hosts drive [RunMonitorController](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.runs/-run-monitor-controller/index.html) and consume [RunSummary](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.runs/-run-summary/index.html), [RunDetail](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.runs/-run-detail/index.html) and [RunFilter](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.runs/-run-filter/index.html) values.

# Package com.letta.mobile.data.runtime

App Server turn execution, scoped leases, approval dispatch, queued input and terminal settlement. State machines preserve turn identity/delivery ownership across streaming, cancellation and connection loss; platform helpers validate local-backend paths where needed. Keep platform process creation outside turn lifecycle policy; provide defaults through [AgentDefaultsRepository](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.runtime/-agent-defaults-repository/index.html), identities through [TurnIdentityStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.runtime/-turn-identity-store/index.html) and conversation reads through [ConversationRowsSource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.runtime/-conversation-rows-source/index.html).

# Package com.letta.mobile.data.runtime.host

Local App Server launch contracts and lease-based supervision. Platform launchers start processes; shared host state tracks readiness/lifecycle, including embedded runtimes that permit only one launch per process. Keep platform launch mechanics behind [LocalAppServerLauncher](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.runtime.host/-local-app-server-launcher/index.html) and [LocalAppServerProcess](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.runtime.host/-local-app-server-process/index.html); use [LocalAppServerHost](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.runtime.host/-local-app-server-host/index.html) for shared lease and lifecycle coordination.

# Package com.letta.mobile.data.runtime.observer

Passive turn identity tracking and bounded completion history. The internal `ObservedTurnTracker` preserves newer activity when duplicate terminals arrive; transport adapters supply observed identities and round-continuation evidence. This package must not publish frames, own engine turns or perform network operations.

# Package com.letta.mobile.data.runtime.supervisor

Pure crash/backoff policy and runtime health models. Exit decisions describe ignore/retry/intervention outcomes; process creation and termination remain host effects. Keep process creation and host UI outside crash decisions; feed [RuntimeExit](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.runtime.supervisor/-runtime-exit/index.html) and [RuntimeHealth](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.runtime.supervisor/-runtime-health/index.html) into [RuntimeCrashPolicy](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.runtime.supervisor/-runtime-crash-policy/index.html) under [RuntimeCrashPolicyConfig](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.runtime.supervisor/-runtime-crash-policy-config/index.html).

# Package com.letta.mobile.data.schedules

Cron construction, next-run projection, formatting and schedule-library state. Backend ports keep editing policy independent of the renderer and transport serving cron commands. Keep schedule execution in the backend rather than client timers; provide schedule reads through [CronScheduleSource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.schedules/-cron-schedule-source/index.html) and control through [CronApi](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.schedules/-cron-api/index.html).

# Package com.letta.mobile.data.schema

Checks and example generation for supported JSON-schema features. Structured problems identify invalid input paths before callers dispatch malformed payloads. Keep backend mutation and unsupported full-schema assumptions outside schema helpers; supply optional integrations through [JsonSchemaHooks](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.schema/-json-schema-hooks/index.html) and consume [JsonSchemaCheck](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.schema/-json-schema-check/index.html) results.

# Package com.letta.mobile.data.search

Message-search query/match models and text helpers. Search mode and source availability remain explicit rather than being inferred from the visible page. Keep invented routes and platform search widgets outside indexing; provide message access through [MessageSearchSource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.search/-message-search-source/index.html) or [ConversationMessageReader](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.search/-conversation-message-reader/index.html).

# Package com.letta.mobile.data.secrets

Agent secret sources and vault state/controller logic. Secret values have redacted representations; explicit source operations access plaintext rather than exposing it through normal state formatting. Keep secret values out of ordinary display formatting and logs; provide guarded reads through [AgentSecretsSource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.secrets/-agent-secrets-source/index.html) and mutations through [AgentVaultActions](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.secrets/-agent-vault-actions/index.html).

# Package com.letta.mobile.data.secrets.client

Client side of the secure secret-input flow. `SecretRequestClient` is the port the form uses to list pending requests, submit a value and decline; [AdminRpcSecretRequestClient](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.secrets.client/-admin-rpc-secret-request-client/index.html) implements it over the host's admin RPC routes, inert when the host does not advertise them. `SecretBuffer` is the one place a typed value lives and `SecretInput` carries it to the port once: both redact themselves, are never saved or logged, and are zeroed on every exit path. `SecretFormMachine` is the pure state machine and `SecretFormController` applies it. Nothing in this package registers a route or a tool.

# Package com.letta.mobile.data.secrets.host

Host side of the secure secret-input flow: agents create requests, the human answers them from a dedicated surface, and the host writes the value to a keystore provider and returns only a receipt. The value travels only inside the keystore secret type and is wiped on every path; single use, expiry and the human-only principal are enforced by [SecretRequestService](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.secrets.host/-secret-request-service/index.html), which is host-only (JVM) because the keystore SPI is.

# Package com.letta.mobile.data.secrets.journal

Durable storage of secret requests so single use and expiry survive a host restart. A [SecretRequestJournal](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.secrets.journal/-secret-request-journal/index.html) keeps ids, targets, the agent's purpose, states and timestamps, never a secret value; the file implementation replaces atomically, fails closed on corruption and is never reset to empty.

# Package com.letta.mobile.data.secrets.request

Shared models of the secret request contract: typed ids and names with the keystore grammar, the agent request and receipt, the request record clients see, validation of agent-authored purposes, and the lifecycle as pure functions in [SecretRequestLifecycle](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.secrets.request/-secret-request-lifecycle/index.html). Nothing here carries a secret value; clients and the host share these types.

# Package com.letta.mobile.data.secrets.wire

The admin RPC wire of the secret request routes, shared by host and client: method sets, capabilities and params fields in [SecretRequestRouteWire](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.secrets.wire/-secret-request-route-wire/index.html), and the submitted value's base64 codec in [SecretValueBase64](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.secrets.wire/-secret-value-base64/index.html), which encodes into and decodes from arrays its caller zeroes and never makes a string of a value.

# Package com.letta.mobile.data.serialization

Named production JSON configurations for wire and persistence layers. Central codec policy makes additive-field handling and deterministic serialization choices visible to callers. Keep independent ad hoc serializer policy outside adapters; choose the appropriate shared [LettaJson](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.serialization/-letta-json/index.html) wire, admin RPC or persisted configuration at each boundary.

# Package com.letta.mobile.data.session

Backend connection keys, session graph contracts and backend-scoped cache coordination. Session changes define repository/cache lifetime boundaries instead of leaving global state attached to obsolete backends. Keep stale process-global backend state outside resource graphs; construct graphs through [SessionRepositoryGraphFactory](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.session/-session-repository-graph-factory/index.html) and scope caches with [BackendConnectionKey](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.session/-backend-connection-key/index.html).

Home's aggregate epoch follows the same session graph boundary. It recognizes transport generations and capability changes so a newly connected host can retry previously unsupported statistics.

The internal session-readiness projection lets page data sources follow connection changes through the session boundary without importing transport state types or adding their own platform observers.

# Package com.letta.mobile.data.session.graph

Shared host repository graph construction and session-client adapters. Explicit unserved repositories preserve unsupported-route behavior when a host cannot provide a backend capability. Keep unsupported sources explicit instead of fabricating host capabilities; bind clients through [HostAppServerClients](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.session.graph/-host-app-server-clients/index.html) and repository implementations through [HostRepositoryAdapters](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.session.graph/-host-repository-adapters/index.html).

# Package com.letta.mobile.data.settings

Settings page models and effect/storage ports. Section organization and host contributions are defined separately from UI layout and platform preference stores. Keep platform preference backing and widgets outside shared settings models; supply connection validation through [BackendConnectionTester](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.settings/-backend-connection-tester/index.html) and render [SettingsRow](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.settings/-settings-row/index.html) values in the host.

# Package com.letta.mobile.data.shell

Current-focus v1 values and pure startup precedence. A saved workspace envelope keeps the main focus, tab order, editor target and rail expansion together under a trusted source namespace. New external entries precede new explicit routes, same-source restoration and verified settings seeds.

`ShellWorkspaceState` owns one immutable focus/envelope and publishes validated transitions before chat effects. Capture `requestToken()` before asynchronous work and return it to `completeFocus` or `reportAvailability`; source generation, selection revision, requested agent, mode and exact ID fence stale results. Exact completions contribute human resume history only when a matching `ShellConversationRef` establishes an eligible lane; unclassified exact targets and inbox/work records cannot become resume fallbacks. `bindSource` separates a durable trusted namespace from live connection generation; a namespace change opens Home and clears source state, while reconnect retains navigation.

`snapshot`/`snapshotJson` and `restore`/`restoreJson` preserve the versioned navigation envelope, including unresolved exact owners, ordered tabs, editor target and rail. `initialize` validates startup candidates once; unknown-source legacy settings remain outside the owner pending source verification. Runtime tokens, jobs, drafts and per-agent history are never restored.

A saver recreating the owner before its trusted source is available calls `holdPending(snapshot)`. It validates and detaches the whole envelope without publishing its identity. `snapshotOrPending()` returns the current bound snapshot, a detached pending envelope while unbound, or null, so another recreation during startup can save safely. The first matching `bindSource` restores once with one revision increment and fresh runtime evidence; a different source discards it and opens Home. Valid holds can replace one another only before binding or navigation commits. Navigation, successful `initialize` and every `restore` attempt discard pending state. Submit new deep links or explicit routes through `initialize` before `bindSource`; matching pending restoration closes startup, while new entries must win arbitration. For ordinary pending recreation, bind directly: even a settings-only `initialize` starts a separate arbitration and supersedes the held envelope. Once a source is bound, use `restore` rather than `holdPending`.

Existing workspace/navigator names remain dispatch/read adapters for later consumer slices; migrated consumers read `currentFocus` and observe `settledSnapshot` for source-scoped next-start seeds, without a feedback sync loop.

Portable shell destinations, routes, workspace navigation and page errors. Entry points translate external navigation into these destinations instead of duplicating route policy. Keep platform navigation controllers outside portable routes; hosts map [ShellRoute](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.shell/-shell-route/index.html) and [ShellDestination](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.shell/-shell-destination/index.html) and bind chat behavior through [ShellChatPort](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.shell/-shell-chat-port/index.html).

# Package com.letta.mobile.data.shell.layout

What the shell's lanes hold, as data: a slot names a view and the object it shows, and a layout config lists the slots in each lane. The default config is the page with the host's side pane after it, the page is always recoverable, and a saved config gains new default modules once. Keep rendering and window sizes outside the config; it only says what is placed where. `ShellLayoutConfigStore` keeps one config per backend config in the shared settings port, reads it once so the first frame draws it, and falls back to the default when nothing readable is stored. Beside it the store keeps a bounded history of the layouts its commits replaced, so undo and reset survive a restart. `ShellViews.NOTE` is an opt-in notebook note module using the same canvas object reference as `ShellViews.CANVAS`; neither is in the defaults. Layout validation is syntactic, never a grant or existence lookup: unknown or denied references remain stored and the mounted view supplies its authorized fallback.

# Package com.letta.mobile.data.shell.layout.edit

How an agent changes the shell layout, as data. `ShellLayoutOp`s (set a lane, add, remove, move, reset) are checked by `ShellLayoutEdits` against `ShellLayoutCatalog`, which lists each lane, the views it may hold with a line on each, and its cap: the page never leaves the main lane, Settings never leaves the side menu, Home never leaves the agent rail, a slot appears once, and an edit with any invalid op applies nothing and reports every problem. `ShellLayoutTool` is the `shell_layout` host tool (get, apply with an optional dry run, undo, reset) with strict JSON input and JSON receipts. It reaches the shell on screen through `ShellLayoutAgentPort`, which a shell attaches its backend's layout store to while it is shown; every change goes through the store's bounded history, so the person can undo it, and is published so the shell can say who made it.

# Package com.letta.mobile.data.shell.sidemenu

The side menu's parts as view ids: the agent rail's (Home, the agents, New) and the agent panel's (the header, the section rows, New chat, the conversations-and-canvases library, Settings), each with its default order. The shell layout's rail and panel lanes list these ids; the rail and panel draw the parts they know in the lane's order. Kept apart from the layout config so the rail and panel can name their parts without depending on the config itself.

# Package com.letta.mobile.data.skills

Skill catalog API, categorization and controller state. Available/installed entries and controllability come from active backend contracts, including explicit read-only/unavailable outcomes. Keep direct filesystem deletion outside catalog actions; provide supported backend operations through [SkillsApi](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.skills/-skills-api/index.html) to [SkillsCatalogController](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.skills/-skills-catalog-controller/index.html).

# Package com.letta.mobile.data.storage

Key/value, secure-settings and content-addressed asset ports. Large payloads can live outside document histories; file-backed implementations are platform bindings rather than universal storage. Keep filesystem and secure-store mechanisms behind effect contracts; implement [KeyValueStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.storage/-key-value-store/index.html), [AssetStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.storage/-asset-store/index.html), [ImageBlobStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.storage/-image-blob-store/index.html) and [SecureSettingsStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.storage/-secure-settings-store/index.html) for each host.

# Package com.letta.mobile.data.stream

SSE framing and incremental UTF-8 line parsing. These low-level utilities support transports using SSE; they do not define current Iroh timeline ownership. Keep reconnect policy and conversation state outside stream framing; feed bytes through `Utf8LineReader` and interpret [SseFrame](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.stream/-sse-frame/index.html) results from [SseParser](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.stream/-sse-parser/index.html).

# Package com.letta.mobile.data.subagents

Durable keyed subagent registry, roster projections and stale-state reconciliation. Parent/child lifecycle records remain independent of display chips; supported hosts supply file-backed registry storage. Keep text-inferred relationships and platform UI outside durable child identity; provide persistence through [SubagentRegistryStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.subagents/-subagent-registry-store/index.html) to [DurableSubagentRegistry](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.subagents/-durable-subagent-registry/index.html).

The durable dispatch record also owns its latest authored todo snapshot. `withNativeTodos` reads the child's cumulative native tool-call history, using the plan call's position to reject stale replay. The checklist and streamed entry counts derive from that same snapshot; absent or empty plans leave progress unknown, regardless of worker lifecycle.

# Package com.letta.mobile.data.swarm

Swarm sessions, directory/history access and queued-delivery tracking. Sender labels are display metadata; device profiles, stable IDs and send attempts carry messaging relationships. Keep display labels distinct from peer identity and durable send provenance; bind network effects through [SwarmLink](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.swarm/-swarm-link/index.html) and pending delivery through [SwarmOutbox](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.swarm/-swarm-outbox/index.html).

# Package com.letta.mobile.data.system1

Optional System 1 decision/reflex contracts and turn interception. Auxiliary decisions degrade when unavailable rather than becoming a mandatory chat dependency. Keep the optional interceptor from becoming a requirement for ordinary chat; bind decisions through [System1DecisionEngine](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.system1/-system1-decision-engine/index.html) and backend calls through [System1Client](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.system1/-system1-client/index.html).

# Package com.letta.mobile.data.templates

Starter-agent template models and controller operations. Templates describe initialization while agent creation remains an injected backend operation. Keep separate backend ownership and platform picker widgets outside templates; hosts drive [AgentTemplatesController](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.templates/-agent-templates-controller/index.html) with [StarterAgentTemplate](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.templates/-starter-agent-template/index.html) values and their existing creation bindings.

# Package com.letta.mobile.data.timeline

Timeline reducers, synchronization and bounded canonical storage/presentation. Gateway loops and the paged canonical ledger are distinct APIs; live overlays, pending locals, durable rows and generation fences retain their respective ownership. Keep painting and direct host mutations outside timeline authority; supply persistence through [TimelineBoundedStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.timeline/-timeline-bounded-store/index.html), ingress through [TimelineTransport](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.timeline/-timeline-transport/index.html) and canonical commits through [TimelineCanonicalWriter](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.timeline/-timeline-canonical-writer/index.html).

Canonical pending prompts retain their send-time position in paged history. Reconnect and history recovery preserve unconfirmed prompts as Not sent; only a persisted user echo with the same OTID confirms one. `CanonicalTimelinePresentation.retry(MessageId)` routes a failed prompt through its agent's captured send coordinator, preserving its conversation, OTID, text and attachments. `CanonicalTimelinePresentation.dismiss(MessageId)` explicitly removes only that conversation's failed prompt through `CanonicalPendingLocalStore.discardFailed`. Retry and Dismiss are available on both live and paged failed rows. Active streaming rows retain their established order while pending records are positioned by send time. A fresh send never implicitly discards an earlier failure.

# Package com.letta.mobile.data.timeline.api

The narrow external-transport timeline writer contract and internal outbound stream lifecycle/scoped-persistence dependency contracts. Send/transport code performs optimistic append, delivery changes and ingest through this boundary rather than directly mutating presentation rows. `TimelineExternalTransportWriter` inherits `TimelinePendingRetryTarget.queuedRetries`, the outbox's queued retries that the agent's send coordinator subscribes to and dispatches, so a retry queued before any sender subscribes still goes out; `TimelinePendingRetryBinding` merges the queued retries of every conversation writer a captured writer resolves, including ones resolved after subscription. Keep UI code from bypassing canonical commit policy; external transports submit through [TimelineExternalTransportWriter](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.timeline.api/-timeline-external-transport-writer/index.html).

The per-conversation outbox lives here too. `OutboxEntry` records one outbound prompt's state (Queued, Dispatching, Accepted, Unknown, Failed, Confirmed, Dismissed) and `OutboxTransitions.next` is the only function that moves it. Events about one attempt are fenced, so a callback from an older attempt cannot overwrite a newer one. The App Server does not deduplicate `client_message_id`, so a prompt that may have reached the host is resolved by reading history rather than by sending it again, unless the user explicitly resends. Admission against the per-conversation budget happens before any transport call, and timers read the time an entry entered its state. `OutboxPersistence` stores the entries as one `outbox/v2` evidence value per timeline scope, removes settled entries, and converts an older `pending/local/v1` value inside its first write; `confirmOutboxEcho` runs inside the transaction that writes the echo. `CanonicalPendingLocalStore` presents these entries as the Sending/Sent/Failed records the timeline renders, applying each outcome as an outbox transition.

Pending send attempts carry typed agent, conversation and message identities plus a retry generation. Canonical `acceptPendingSend` reserves the pending budget in a storage transaction before invoking its synchronous transport acceptance callback; a refused send preserves existing records. Wrappers must forward this admission along with ownership and attempt-aware outcomes. Transient disconnects allow 30 seconds for live progress or authoritative recovery; failed or timed-out recovery releases pending ownership so an unconfirmed prompt becomes Not sent.

# Package com.letta.mobile.data.timeline.headless

Headless timeline storage, replay and streaming-reveal state. Semantic behavior can be exercised without a Compose viewport or platform application shell. Keep replay diagnostics from becoming a second product timeline writer; supply replay inputs and backing through [HeadlessTimelineReplaySession](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.timeline.headless/-headless-timeline-replay-session/index.html) and [HeadlessTimelineStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.timeline.headless/-headless-timeline-store/index.html) to [HeadlessTimelineReplayer](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.timeline.headless/-headless-timeline-replayer/index.html).

# Package com.letta.mobile.data.timeline.snapshot

Stored envelopes, scope keys, snapshot codecs and incremental persistence planning. Persistence formats stay separate from live rendering so storage preserves scoped identities and replayable rows. Keep rendering and connection policy outside storage encoding; implement [ConfirmedTimelineStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.timeline.snapshot/-confirmed-timeline-store/index.html) and image-body effects through [TimelineImageBodyReader](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.timeline.snapshot/-timeline-image-body-reader/index.html) and [TimelineImageBodyWriter](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.timeline.snapshot/-timeline-image-body-writer/index.html).

# Package com.letta.mobile.data.tooloutput

Structured tool-output parsing for presentation. Classified content replaces repeated ad hoc interpretation inside individual tool cards. Keep tool execution and file writes outside output interpretation; viewers consume [ToolOutputDocument](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.tooloutput/-tool-output-document/index.html) and [ToolOutputBlock](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.tooloutput/-tool-output-block/index.html) produced by [ToolOutputParser](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.tooloutput/-tool-output-parser/index.html).

# Package com.letta.mobile.data.tools

Tool-library controller state and operations. Source loading and selection are coordinated outside platform-specific screens. Keep platform tool-list widgets outside library state; hosts drive [ToolLibraryController](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.tools/-tool-library-controller/index.html) and present its [ToolLibraryState](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.tools/-tool-library-state/index.html) under [ToolLibraryFilter](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.tools/-tool-library-filter/index.html).

# Package com.letta.mobile.data.transport

Channel events, server frames and chat bridge mappings. Scope routing and ingest deduplication are transport boundaries; these types do not themselves own a durable timeline. Keep durable timeline storage and product rendering outside frame routing; supply the channel through [IChannelTransport](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.transport.api/-i-channel-transport/index.html) when integrating [WsChatBridge](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.transport/-ws-chat-bridge/index.html) and `AgentEventRouter`.

# Package com.letta.mobile.data.transport.serialization

Internal deserializers for replay envelopes and unknown transport frames. The public
`ServerFrameSerializer` selects these inbound-only helpers; they preserve raw payloads and reject
accidental encoding. Keep frame contracts in `data.transport` and byte framing in the transport binding.

# Package com.letta.mobile.data.transport.api

Channel/control interfaces consumed by session, chat and repository code. Explicit no-op/unavailable implementations support hosts lacking a particular connection capability. Keep unsupported optional operations explicit rather than simulating them; implement [IChannelTransport](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.transport.api/-i-channel-transport/index.html) and opt into capability interfaces such as [CronControlTransport](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.transport.api/-cron-control-transport/index.html) or [LivenessProbingChannelTransport](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.transport.api/-liveness-probing-channel-transport/index.html) only when supported.

# Package com.letta.mobile.data.transport.appserver

App Server commands/frames, correlated requests, WebSocket transport and client adapters. Generations/retry classes constrain replay; workspace/provider operations share client boundaries with chat. Keep blind retries of non-idempotent operations outside client dispatch; supply a connection through [AppServerTransport](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.transport.appserver/-app-server-transport/index.html) and adapt it with [AppServerTransportAdapter](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.transport.appserver/-app-server-transport-adapter/index.html).

# Package com.letta.mobile.data.transport.iroh

Iroh channel/turn transport, supervision, liveness and observer ingestion, with canvas/inter-agent adapters. Native endpoint and filesystem facilities follow platform tags; common frame contracts do not imply native Iroh availability everywhere. Keep native endpoint availability explicit per target; supply direct dialing through [DirectAppServerDialer](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.transport.iroh/-direct-app-server-dialer/index.html), address persistence through [IrohAgentAddressStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.transport.iroh/-iroh-agent-address-store/index.html) and invitation verification through [InviteAuthenticator](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.transport.iroh/-invite-authenticator/index.html).

# Package com.letta.mobile.data.transport.teardown

Releasing a transport's resources (a QUIC endpoint and its transport, a WebSocket and its HTTP client) in order, once, off the caller's thread: each step is capped and isolated, and a shutdown hook gives running teardowns a bounded wait at exit. Keep connection setup and reconnect policy outside it; hand the ordered release steps to [TransportTeardown](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.transport.teardown/-transport-teardown/index.html) and close it from any thread, a UI thread included.

# Package com.letta.mobile.data.voice

Shared vocabulary of the hands-free voice loop: spoken text that never prints its words, audio levels, the utterance an ear produces (text, an optional immediate reply and an optional structured action decision), tagged errors with a suggested user action, the person's voice settings and the loop's phases and configuration. Destination consent for remote speech endpoints is a separate stored value, [VoiceEndpointGrants](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.consent/-voice-endpoint-grants/index.html), so a settings export never carries it. The session package wires it to the chat page as voice mode.

# Package com.letta.mobile.data.voice.activation

What starts or ends a voice turn: push-to-talk, tap, hotkey and wake word as activation events, and a pure key classifier for Auto, Hold and Toggle modes. The wake word enters through an activation source wrapping the wake-word listener; an unavailable listener degrades to a notice while push-to-talk keeps working.

# Package com.letta.mobile.data.voice.audio

Small platform ports for capturing and playing audio, including fixed-size PCM frame sources with sample positions for wake-word engines, used by engines that handle raw audio themselves, with audio containers whose descriptions report sizes only. Platforms implement [AudioCapture](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.audio/-audio-capture/index.html) and [AudioPlayer](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.audio/-audio-player/index.html); cancelling either must return only after the device is released.

# Package com.letta.mobile.data.voice.audio.android

Android microphone and speaker behind the voice audio ports: [AndroidAudio](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.audio.android/-android-audio/index.html) opens `AudioRecord` frame sources on the voice-recognition path or the communication path with echo cancellation and noise suppression attached when the device has them (reported as [CaptureEffects](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.audio.android/-capture-effects/index.html)), and plays clips on a streaming `AudioTrack` with assistant usage under transient, duck-allowing audio focus. It never requests a permission: capture fails with PERMISSION_REQUIRED until the app holds `RECORD_AUDIO`.

# Package com.letta.mobile.data.voice.audio.desktop

Desktop microphone and speaker on Java Sound behind the voice audio ports: [DesktopAudio](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.audio.desktop/-desktop-audio/index.html) opens `TargetDataLine` frame sources in exactly the engine format (or fails typed), plays WAVE and raw PCM clips on a `SourceDataLine`, resampling when the device refuses the clip's rate, and lists and polls mixers for device selection and change detection.

# Package com.letta.mobile.data.voice.audio.device

Device selection and recovery shared by every platform: typed device identifiers with a system-default choice, a frame source that reports dropped frames, an [AudioCapture](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.audio/-audio-capture/index.html) built on frame sources, and [RecoveringFrameSource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.audio.device/-recovering-frame-source/index.html), which reopens a lost device with bounded exponential backoff, ends the wait early on a debounced device change, keeps sample positions monotonic, and never retries a missing permission or a wrong format.

# Package com.letta.mobile.data.voice.audio.line

The blocking line layer under the Android and desktop audio implementations: minimal microphone and speaker line interfaces that `TargetDataLine`, `AudioRecord`, `SourceDataLine` and `AudioTrack` sit behind, a capture thread that hands fixed-size frames to a bounded drop-oldest buffer and releases the line before a cancelled collection completes, and a player that decodes, resamples, paces and aborts at once on cancellation. Internal; tested with fake lines and no devices.

# Package com.letta.mobile.data.voice.audio.pcm

Pure PCM utilities for voice audio: a robust WAVE reader and writer ([WavCodec](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.audio.pcm/-wav-codec/index.html)) that skips unknown chunks and tolerates streaming placeholder sizes, a clip decoder that judges audio by its bytes and refuses compressed formats instead of playing noise, a chunk-independent windowed-sinc [StreamingResampler](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.audio.pcm/-streaming-resampler/index.html), loudness levels for meters and the avatar, and the typed [VoiceAudioException](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.audio.pcm/-voice-audio-exception/index.html).

# Package com.letta.mobile.data.voice.consent

Per-destination consent for remote speech endpoints: a grant names one exact origin (scheme, host and port) and what may be sent there, audio for transcription or text for synthesis. Grants are a stored value of their own, apart from the voice settings, so an export or backup never carries them; endpoint engines check them before every request.

# Package com.letta.mobile.data.voice.endpoint

An OpenAI-compatible speech client for `POST /v1/audio/transcriptions` (multipart) and `POST /v1/audio/speech` (JSON, streamed audio), built on a caller-supplied Ktor engine with no logging and a redacted bearer key. Failures carry fixed codes (unreachable, unauthorized, rejected, bad response, timed out); diagnostics carry operation, code and status only. The transcriber and the synthesising mouth check a destination grant for the exact endpoint and payload before every request and fail with permission required rather than falling back.

# Package com.letta.mobile.data.voice.engine

The three seams of the voice loop. A [VoiceEar](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.engine/-voice-ear/index.html) owns its audio source and decides end of turn itself, so a realtime audio model can replace a speech recogniser; a [VoiceResponder](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.engine/-voice-responder/index.html) streams reply text and can delegate the rest to arrive later as a late reply; a [VoiceMouth](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.engine/-voice-mouth/index.html) speaks segments in order and stops at once for barge-in. Stops and cancels suspend until the work has actually stopped.

# Package com.letta.mobile.data.voice.loop

The voice loop driver: idle, listening, thinking, speaking and failed, continuous or single shot, with barge-in, a bounded thinking timeout and a late-reply policy. It serialises every input on one coroutine, drops events from stale generations, publishes a phase only after the engines have let go, never opens the microphone over its own speech unless barge-in needs it, and exposes a [VoiceLoopState](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.loop/-voice-loop-state/index.html) rich enough for a page and the avatar.

# Package com.letta.mobile.data.voice.loop.machine

The voice loop's internal transition table: a pure function from model and input to model and effects, split into control, ear, reply and mouth rules. It reads no clock, suspends nowhere and touches no engine, so every row is tested directly.

# Package com.letta.mobile.data.voice.responder

The responder for the conversation's agent, over its own port, [VoiceChatSession](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.responder/-voice-chat-session/index.html): sending returns the message's client id, and the state publishes the run each sent message started. Replies are attributed by that identity only, never by text, position or timing; without it nothing is spoken and the reply ends unattributed. The app's chat session is adapted to this port in `data.voice.responder.chat`.

# Package com.letta.mobile.data.voice.responder.chat

The app's chat session as a [VoiceChatSession](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.responder/-voice-chat-session/index.html). The chat page's rows become voice rows (the person's messages, the agent's plain text, errors; reasoning, tool calls, approvals and everything else are never spoken) under keys that stay put while text streams, while a pending message confirms and while a run id is promoted. Each send's otid and the runs it started come from an [IdentifiedChatSend](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.chat.sentruns/-identified-chat-send/index.html), and stopping is the page's own Stop. A host may add the rows it streams outside the page's list (the desktop's live reply overlay) and the page's send queue is read so a queued follow-up is known. Building the adapter starts nothing; the desktop and Android chat pages build one when voice mode starts.

# Package com.letta.mobile.data.voice.session

Voice mode for one chat page, assembled from the pieces below: [VoiceModeController](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.session/-voice-mode-controller/index.html) builds and owns one voice loop at a time over a platform's [VoiceAudio](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.session/-voice-audio/index.html), the stored endpoint setup and the chat session a host opens, and publishes a small [VoiceModeState](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.session/-voice-mode-state/index.html) for the chat bar. A press checks the setup, then the microphone permission, then starts; the microphone and speaker are released on stop, on a conversation change and on shutdown. [VoiceSetupStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.session/-voice-setup-store/index.html) keeps the endpoint address and the destination grants as separate values and the API key only in the host's secure store, validates addresses with the endpoint client's own rules and checks reachability without sending audio or text. Nothing here logs audio, transcripts, addresses or keys.

# Package com.letta.mobile.data.voice.session.telemetry

Voice mode's diagnostics, as `VoiceMode` events timed from the session's start: loop states, ear decisions with voiced time and levels, sends and attributions by client message id, speech requests, stops and failures by reason. They carry states, reasons, ids, counts, levels and timings only, never audio, transcript or reply text, an API key or a server address, and go to a [VoiceTelemetrySink](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.session.telemetry/-voice-telemetry-sink/index.html) the chat page hands voice mode (the app's telemetry); with none, nothing is reported.

# Package com.letta.mobile.data.voice.speech

Turns streamed reply text into speakable sentences so speech starts before the reply is complete. Sentence ends respect abbreviations, initials, decimals and list numbers; fenced code is skipped; markdown, links, URLs and HTML are stripped; overlong text is cut at a clause break and the remainder is flushed when the reply completes.

# Package com.letta.mobile.data.voice.vad

Voice-activity detection for the voice ear: the [SpeechDetector](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.vad/-speech-detector/index.html) seam (one hop of PCM in, a speech probability out) so a model-based detector can replace the built-in [EnergySpeechDetector](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.vad/-energy-speech-detector/index.html) (energy and zero-crossing rate over a calibrated, slowly tracked noise floor, with a separate floor for the app's own echo and a low/normal/high sensitivity), and the [EndOfTurnPolicy](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.vad/-end-of-turn-policy/index.html) with hysteresis, a sustained onset, a minimum of voiced audio below which a sound is dropped untranscribed, trailing silence, pre-roll, a hard cap and an echo guard, all measured in audio time from sample positions.

# Package com.letta.mobile.data.voice.vad.ear

[VadVoiceEar](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.vad.ear/-vad-voice-ear/index.html), a hearing engine composed of a frame source, a speech detector, the end-of-turn policy and a [SpeechTranscriber](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.vad.ear/-speech-transcriber/index.html): it decides end of turn itself, transcribes each turn with its pre-roll, drops transcripts that are not words (a [TranscriptPolicy](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.vad.ear/-transcript-policy/index.html) for empty text and stock noise phrases from short quiet turns), reports its turn decisions to an [EarObserver](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.vad.ear/-ear-observer/index.html), offers a failed turn for one retry from memory only, and releases the microphone before a cancelled listen completes.

# Package com.letta.mobile.data.voice.wake

The wake-word stack. Engines implement [WakeWordEngine](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.wake/-wake-word-engine/index.html), pure signal processing over frames that never touches the microphone, files or network; a registry selects the preferred engine and falls back with reasons; [WakeWordListener](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.voice.wake/-wake-word-listener/index.html) owns the frame source, refuses a format mismatch, applies phrase changes live and gates hits by sensitivity, cooldown, suppression while the app speaks and an optional confirmer. Audio is never stored or sent.

# Package com.letta.mobile.data.voice.wake.assets

Provisioning of wake-word engine model files, separate from the engines: assets are never bundled, are downloaded only after the person enables the wake word, are whitelisted, verified against a pinned size and SHA-256 and renamed into place atomically, and reach engines only as verified handles.

# Package com.letta.mobile.data.workspace

Workspace file search, mentions and viewer state over injected file sources. App Server retrieval stays separate from UI navigation and file presentation. Keep local filesystem assumptions and cross-backend path interpretation outside viewers; provide content through [WorkspaceFileSource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.workspace/-workspace-file-source/index.html) and host opening through [WorkspaceFileOpener](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.workspace/-workspace-file-opener/index.html).

# Package com.letta.mobile.data.workspace.directory

Working-directory picker state and path operations for remote POSIX/Windows devices. Paths describe the execution environment, which may differ from the client's operating system. Keep backend device paths distinct from the client's filesystem; provide listing through [WorkspaceDirectorySource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.workspace.directory/-workspace-directory-source/index.html), creation through [FolderCreator](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.workspace.directory/-folder-creator/index.html) and persistence through [DefaultWorkingDirectoryStore](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.workspace.directory/-default-working-directory-store/index.html).

# Package com.letta.mobile.data.workspace.relay

Workspace command routing through a direct client or Iroh host relay. Sources retain one contract while route availability follows the active session. Keep independent session reconnection outside workspace relay calls; bind routing through [WorkspaceClientRoute](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.workspace.relay/-workspace-client-route/index.html) and requests through [WorkspaceRelayCall](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.workspace.relay/-workspace-relay-call/index.html).

# Package com.letta.mobile.qr

QR encoding for pairing codes. The common [QrCodeGenerator](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.qr/-qr-code-generator/index.html) implements ISO/IEC 18004 in pure Kotlin for every target, the browser included, and reports oversized content as a typed result rather than an exception carrying the text. Hosts draw invite codes through the pairing [QrEncoder](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.pairing/-qr-encoder/index.html) contract with [PortableQrEncoder](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.qr/-portable-qr-encoder/index.html). The JVM/Android `QrCode` facade over ZXing remains for the command-line PNG and terminal renderers. Keep pairing trust decisions outside QR generation; these types only produce module grids.

# Package com.letta.mobile.runtime.iroh

Android-only physical initialization for the native Iroh runtime. `IrohAndroidInit.install(context: android.content.Context)` binds the Android context before native transport operations. Pairing and session transport hosts call this hook from Android source sets; common, desktop, browser, and native code have no initialization shim.

# Package com.letta.mobile.ui.ambient

Portable status-to-motion and shader inputs for ambient agent effects. Android/Skia renderers consume shared policy without importing rendering APIs into common logic. Keep renderer objects and OS animation APIs outside portable motion policy; renderers consume [AmbientMotionSpec](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.ambient/-ambient-motion-spec/index.html) and feed activity changes through [AmbientEnvelopeRamp](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.ambient/-ambient-envelope-ramp/index.html).

# Package com.letta.mobile.ui.chat.render

Chat render models, timeline projection/presentation helpers and streaming-text smoothing. These are UI-facing transformations; composable row painting belongs to sharedUI. Keep composable painting and durable timeline writes outside presentation; bind transport inputs through [ChatTransport](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.chat.render/-chat-transport/index.html) and derive state through [ChatTimelinePresenter](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.chat.render/-chat-timeline-presenter/index.html) and [ChatTimelineProjector](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.chat.render/-chat-timeline-projector/index.html).

# Package com.letta.mobile.ui.chat.session

Chat session ports and action contracts consumed by shared surfaces. Hosts bind models, composer state, timelines and effects without importing platform ViewModels into product UI. Keep platform ViewModels and screen lifecycle APIs outside session contracts; provide behavior through [ChatSessionPort](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.chat.session/-chat-session-port/index.html) and optional resources through [ChatModelSource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.chat.session/-chat-model-source/index.html) and [ChatWorkingDirectorySource](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.chat.session/-chat-working-directory-source/index.html).

# Package com.letta.mobile.ui.common

General UI-state containers and timeline grouping helpers. These carry presentation data/policy rather than platform navigation or rendering infrastructure. Keep platform navigation and widget painting outside common presentation values; consumers map [UiState](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.common/-ui-state/index.html) and [GroupPosition](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.common/-group-position/index.html) to their own host surfaces.

# Package com.letta.mobile.ui.haptics

Haptic cue identities, policy and effect contracts. Components request semantic feedback while hosts decide whether hardware feedback is available. Keep OS vibration APIs outside common cue policy; implement [Haptics](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.haptics/-haptics/index.html) in each host or choose [NoHaptics](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.haptics/-no-haptics/index.html) while sharing [HapticPolicy](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.haptics/-haptic-policy/index.html).

# Package com.letta.mobile.ui.state

Refresh policy for retaining previously loaded content during newer requests. Request identities prevent obsolete outcomes from replacing the current screen state. Keep backend fetching outside retained-content transitions; callers supply request identity and results to [RetainedContentRefresh](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.state/-retained-content-refresh/index.html).

# Package com.letta.mobile.ui.theme

Portable palette, spacing, shape, motion and chat tokens. Compose theme construction and semantic-color adaptation live in sharedUI/platform bindings. Keep Compose colors and platform theme objects outside portable tokens; rendering layers translate [LettaThemeTokens](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.ui.theme/-letta-theme-tokens/index.html), including spacing and color tokens, into their toolkit.

# Package com.letta.mobile.util

Formatting, telemetry and coroutine/error helpers. Platform actuals supply logging/time-format details so portable callers need not import operating-system utilities. Keep domain workflows and sensitive payload logging outside general helpers; bind telemetry through [TelemetryDelegate](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.util/-telemetry-delegate/index.html) and platform formatting through the declared expect/actual functions.

# Package com.letta.mobile.util.time

The one relative-age label ("5m", "3h", "2mo") and the one duration label ("340ms", "4.2s", "1m 12s") every surface shows, through [TimeLabels](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.util.time/-time-labels/index.html). The package imports nothing of the app's, so any UI or data package can use it without an import cycle. Signed countdowns (ScheduleFormat.relative) and metric-tile value/unit pairs (FormatHelpers) are separate idioms and stay where they are.

# Package com.letta.mobile.data.canvas.workspace

Shared document composition over the existing NotebookDocumentId, NotebookNoteSession and NotebookReferenceSession. DocumentWorkspaceController serializes navigation, reauthorizes every target through the required trusted opener, validates exact session identity, and awaits prepareClose before replacing or closing an editor. Denial, unavailable capability and failed durability retain the mounted document and draft. A cached target with a retained baseline but a missing/load-failure state is rejected before saving the mounted editor or altering its history/reference work. Superseded save-before-leave operations reconcile any indeterminate write through the shared note session before later close acknowledgement. Presentation switches retain the same session; successful navigation cancels obsolete reference work. No registry, persistence, identity conversion or host authority is supplied. Production activation still requires reviewed host policy and resolution of browser JSON/Automerge and compaction identity blockers.

# Package com.letta.mobile.data.controller.node.iroh.records

Wrapper-owned producer run records and leases, independent of upstream cron storage. `WrapperRecordStore` is the injectable persistence boundary; `FileWrapperRecordStore` serializes append/upsert transactions, enforces clock-based retention and capacity, and quarantines corrupt state. Enum values cross the JSON boundary with an UNKNOWN fallback and persist by name. Record filters and pagination state also live here; authenticated Iroh handlers supply agent scope checks. This package has no dependency on connection lifecycle or product UI.

# Package com.letta.mobile.data.controller.node.iroh.beads

Bounded Beads admin RPC adapter for the wrapper, with upstream credentials held on the host.

# Package com.letta.mobile.data.controller.node.iroh.mounts

Authenticated admin RPC routes over the canvas mount facade (`canvas.mount.resolve`, `canvas.mount.binding.bind`, `canvas.mount.binding.replace`, `canvas.mount.default.set`, `canvas.mount.default.clear`, `canvas.mount.candidates`). `CanvasMountRoutes` is an `AdminRpcRoutes` a host passes as `AdminRpcHostServices.canvasMountRoutes`; it registers all six methods and advertises `canvas.mount.v1` only under a secure `IrohAuthPolicy`, and nothing under the anonymous test policy. Each call takes its principal only from the connection's verified endpoint id, parses params strictly (unknown fields refused), and reaches storage only through `AuthorizedCanvasMounts`, with a per-call authority built from the connection's agent and conversation scopes and granted capabilities, the host's `CanvasMountDirectory` (a binding needs an existing conversation of the agent; changing a default needs `canvas.mount.write`, the agent in scope and an existing agent) and `CanvasMountCatalog`, which decides canvas visibility from the host's own canvas directory and relay bindings: a binding's own conversation canvas is visible while the host's records show that id unused or held through that conversation, any other canvas must exist and be owned by an agent in the caller's agent scope, and a canvas with no known owner is visible only without agent scope; a failed lookup while judging a proposed canvas is the same denial. A conversation shows its binding, else its agent's default, else its own canvas. A deadline bounds every call: before the commit point it answers `unavailable`, after it `outcome_unknown`. `CanvasMountChanges` pushes `canvas_mount_updated` to a conversation's viewers that resolve's own checks admit. `CanvasMountToolLookup` gives the host canvas tools a read-only view of their own conversation's mount as a host-internal principal that can never mutate. Deadlines, the host's conversation records and the one-time paired-device upgrade live in the `deadline`, `directory` and `upgrade` sub-packages. Denials are one fixed refusal text; invalid params a fixed code; telemetry carries fixed event names and enum values only. The package depends on the iroh router package, never the reverse; there is no client code or agent-callable mount path, and the only listing is the bounded, read-only `canvas.mount.candidates`.

# Package com.letta.mobile.data.controller.node.iroh.mounts.deadline

`CanvasMountDeadline` bounds one canvas mount call: the work runs in the host-owned scope, is cancelled past its limit and given a grace period to unwind, and `CanvasMountTimed` tells a settled call from an expired one (and whether it unwound) so the routes can tell "nothing committed" from "may have committed".

# Package com.letta.mobile.data.controller.node.iroh.mounts.directory

`CanvasMountDirectory` is the host's own record of which conversations and agents exist, consulted only by the canvas mount authority: a binding needs an existing conversation of the addressed agent, a default an existing agent. `CanvasMountDirectory.appServer` reads the App Server's `conversation_retrieve` and `agent_retrieve` (an unsuccessful answer counts as absent; a transport failure throws); `MemoisedMountDirectory` keeps each answer for one call so a listing looks each conversation up at most once.

# Package com.letta.mobile.data.controller.node.iroh.mounts.upgrade

`CanvasMountDeviceUpgrade` gives devices paired before canvas mounts the two mount capabilities once, on a host that runs with canvas mounts enabled: only records whose capability set is exactly the chat, desktop or vibesync role as it was before mounts change, in one locked read-modify-write of the paired-device store, and a marker beside that store makes it run once.

# Package com.letta.mobile.data.controller.node.iroh.errors

The one exception type whose message the admin RPC router returns to a remote client. Handlers raise it for deliberate, safe refusals built from the caller's own request; every other exception answers a fixed internal-error text so host paths and state never cross the wire. This leaf package imports nothing from the handlers or the router.

# Package com.letta.mobile.data.schedules.leases

Read-only lease records and their per-day calendar spans. Lease producers and transport remain outside this model.

# Package com.letta.mobile.data.schedules.runs

Maps merged wrapper cron-run and lease pages into shared schedule history records. The mapper tolerates older rows and rejects malformed entries.

# Package com.letta.mobile.data.controller.extras.messaging

Optional recipient addressing, task correlation, and a caller-supplied retry idempotency ID for agent-message CLI invocations.

# Package com.letta.mobile.data.objectverbs.registry

The shared VerbRegistry and trusted core provider implement core.pin once over DevicePinStore. Providers recheck source/object resolution and validate desired boolean state before committing; unavailable providers expose no state or actions.

# Package com.letta.mobile.data.objectverbs.binding

The device settings facade owns one pin journal for its lifecycle, with no process-global owner map. Session bindings project that journal into canonical references, facets and legacy adapter reads. A binding follows the trusted session graph's backend namespace; bindings share a device store and retain separate session scopes.

# Package com.letta.mobile.data.pins

Device-local scoped pin membership/order, explicit-unpin tombstones and recoverable legacy import journal. Snapshot every available backing before union, retain raw/opaque entries and read/decode failures, verify persisted state before per-source checkpoints, and resume idempotently. Unfinished sources are reread with every distinct raw/parse snapshot retained; completed sources are never reread. Initialization failures preserve legacy data and expose unavailable or disabled pin actions while allowing the host to open. Only trusted ownership evidence may resolve pending domain entries; active backend alone is never evidence.

# Package com.letta.mobile.data.objectverbs.refs

Typed ObjectRef factories over the existing agent, conversation, project, canvas and shortcut identities. Import these companion extensions at reference-construction boundaries; descriptor values do not depend on repositories or Home controllers.

# Package com.letta.mobile.data.flow

Dependency-free projections of read-only StateFlow values. DerivedStateFlow observes upstream state without creating a coroutine, cache or independent lifetime.

# Package com.letta.mobile.data.session.pins

Projects the trusted session graph's backend namespace for a pin binding. Session identity comes from the host graph, never the active row or a legacy pin string.

# Package com.letta.mobile.data.home.shortcut

Existing Home shortcut identities, separated from Home controllers and storage. The original HomeShortcut source alias remains available; persistence names are unchanged.
