# Module :sharedLogic

Shared domain, transport and state-management code for Letta clients and controller hosts.
Start with repository/session contracts for resources, App Server clients and runtime packages
for turns, and timeline packages for durable history and live projections. UI-facing chat
ports and render models are portable contracts; composable painting belongs to `:sharedUI`.

Read each declaration's platform/source-set tags. `commonMain` holds shared contracts,
`jvmAndAndroid` adds JVM/Android integrations, and `jvmMain` provides JVM process/filesystem
facilities. Android, host-native and wasmJs actuals implement their declared seams; a package
appearing here does not mean its native runtime is available on every target. These package
overviews describe responsibilities, not deployment health or remote-server capabilities.


# Package com.letta.mobile.appserver

Shared smoke-run support for driving real App Server turns, including concurrent conversations and image inputs. Command-line hosts supply a Ktor engine and identity factory; these diagnostic helpers exercise the client stack without providing a platform UI or installing a server. Keep installation and platform UI outside these diagnostics; hosts configure runs through `AppServerSmokeSpec` and `AppServerServeSpec`.

# Package com.letta.mobile.data.a2ui

Agent-generated UI protocol models, data bindings, action dispatch and bounded surface state. Renderers consume these surfaces while approval submission and link policy connect actions to their owning session. Keep composable painting outside protocol state; integrate navigation and permission decisions through `A2uiLinkPolicy` and `ToolApprovalAdmission`.

# Package com.letta.mobile.data.agenteditor

Agent-editor state, validation and backend operations. The controller coordinates edits and exposes sections to the UI; hosts supply repositories and platform creation/presentation bindings. Keep platform navigation and widget state outside edit orchestration; supply resource operations through `AgentEditorBackend` and creation through `AgentCreator`.

# Package com.letta.mobile.data.agents

Agent grouping and recently used agent projections for navigation. Display-name groups and recency are derived independently of the platform rail or picker. Keep rail rendering and backend mutations outside these projections; feed grouping through `deriveAgentSpaces` and recency through `RecentAgentsPolicy`.

# Package com.letta.mobile.data.api

Portable API failures and project request payloads retained by repository contracts. Their presence does not establish that a particular session has a remote HTTP route. Keep transport selection outside request payloads; repository adapters translate these request types and `ApiException` at their backend boundary.

# Package com.letta.mobile.data.attachment

Shared image-ingress policy and attachment budgets. Platform decoders supply image data; these policies constrain turn attachment counts, dimensions and payload size. Keep decoding behind platform adapters and attachment rendering in `sharedUI`; apply `ImageIngressPolicy` and `AttachmentLimits` before admitting image results.

# Package com.letta.mobile.data.backend

Session capabilities and explicit route availability. Bound sources and absent routes let repositories report unsupported operations without silently choosing another backend. Keep unsupported routes explicit rather than silently falling back; session factories describe available operations through `SessionCapabilities` and `SessionRouteMatrix`.

# Package com.letta.mobile.data.canvas

Canvas scene/operation models, validation, projection, undo/history, synchronization and host tool integration. Notebook, filesystem and relay storage bindings are platform-scoped implementations alongside the portable contracts. Keep platform drawing and filesystem access behind adapters; implement `CanvasDocumentStore`, `CanvasSyncTransport` and `CanvasPreviewRenderer` for their respective effects.

# Package com.letta.mobile.data.canvas.bubble

Persisted layout preferences for canvas-attached chat bubbles. These describe user layout choices rather than owning chat or canvas session state. Keep conversation lifecycle and transcript state outside layout persistence; bind saved bubble geometry through `CanvasBubbleLayoutPrefs` and `CanvasBubbleLayoutCodec`.

# Package com.letta.mobile.data.canvas.compose

The versioned semantic canvas composer: validation, Markdown conversion, geometry reservation, placement, compilation and receipts. It converts content-oriented requests into canvas operations; renderers draw and fit the resulting content. Keep pixel rendering outside semantic composition; extend accepted requests through `CanvasComposeContract` and compile them with `CanvasComposeCompiler`.

# Package com.letta.mobile.data.canvas.plugin

Plugin element schemas, kind catalogs and property checks within canvas scenes. Clients can preserve, edit and show fallback elements without loading plugin implementation code. Keep plugin execution outside element interpretation; provide installed kind metadata through `PluginKindCatalog` while retaining fallback representations for unknown kinds.

# Package com.letta.mobile.data.channel

Channel display projections and page-controller state, plus notification delivery contracts. Injected sources supply live data; connection and recovery states remain distinct from channel content. Keep connection establishment outside library presentation; supply reads through `ChannelLibrarySource` and mutations through `ChannelsPageActions`.

# Package com.letta.mobile.data.chat.approval

Approval-answer coordination, submission tracking and an outbox for answers awaiting delivery. Request identity is maintained independently of the screen displaying the approval. Keep permission decisions explicit and independent of screen lifetime; implement `ApprovalOutboxStore` for persistence and `ChatApprovalAnswerHost` for submission.

# Package com.letta.mobile.data.chat.branch

Conversation fork, edit-and-resend and rerun orchestration. The branch controller plans operations, records lineage and passes the resulting conversation and optional draft to its host opener. Keep platform navigation and wire encoding outside branch orchestration; connect creation through `ConversationForkGateway` and opening through `BranchOpener`.

# Package com.letta.mobile.data.chat.display

Client-side chat display preferences, font-scale policy and Markdown transcript export. Presentation changes do not rewrite the server conversation. Keep server transcript mutation outside display preferences and exports; bind font persistence through `ChatFontScaleStore` and supply transcript inputs through `TranscriptInput`.

# Package com.letta.mobile.data.chat.projection

Timeline-to-message/run/tool projections, notification presentation and canvas receipts. Grouping, action policy and row identity are shared so hosts do not reconstruct chat semantics independently. Keep durable timeline writes and composable painting outside projection; derive presentation with `buildChatRenderModel` and action policy with `messageActionAvailability`.

# Package com.letta.mobile.data.chat.projection.meridian

Recognition of Meridian command invocations in tool arguments and output. Supported shell calls project back to their semantic tool identity for timeline presentation and receipts. Keep shell execution outside command recognition; represent recognized invocations with `MeridianInvocation` and command metadata with `MeridianCommandSpec`.

# Package com.letta.mobile.data.chat.recompile

Conversation system-prompt recompile requests and their Iroh RPC adapter. Recompilation is scoped to agent/conversation identity and is distinct from a normal chat send. Keep ordinary message sending outside recompile state; connect backend execution through `ConversationRecompileGateway` or the `IrohAdminRpcLink` adapter seam.

# Package com.letta.mobile.data.chat.routing

Target-conversation selection when following an inter-agent message. Sender and recipient have separate conversation spaces, so navigation cannot assume one shared conversation identifier. Keep navigation and conversation creation outside recipient selection; callers supply conversation candidates to `pickOtherAgentConversation`.

# Package com.letta.mobile.data.chat.runtime

Chat-session state, reducers and host-facing runtime contracts. Backend selection, conversation loading, composer gates, interruption and viewport-follow policy live outside platform screen implementations. Keep platform ViewModels and composable rendering outside session state; provide backend effects through `ChatGateway`, `BackendConfigStore` and `ChatRunInterruptHost`.

# Package com.letta.mobile.data.chat.send

Portable send coordination, queue control and transport-event processing. Optimistic sends, attachment shaping, turn identity and reconciliation operate through injected transport, timeline and UI boundaries. Keep direct screen mutation outside send coordination; connect presentation through `ChatSendUiSink` and serialize work through `ConversationSendQueue`.

# Package com.letta.mobile.data.commands

Slash-command contracts and commands answered by the client itself. Navigation/local actions remain distinct from commands requiring active backend support. Keep arbitrary shell execution outside slash-command discovery; provide backend command capabilities through `SlashCommandsApi` and client commands through `ClientSlashCommands`.

# Package com.letta.mobile.data.compaction

Compaction state and backend ports with App Server and admin-RPC adapters. Controllers expose progress and unsupported-host outcomes without requiring UI code to construct wire commands. Keep protocol construction outside compaction presentation; provide backend operations through `CompactionRepository` to `CompactionController`.

# Package com.letta.mobile.data.composer

Composer autocomplete, mention spans and reasoning-effort choices. Reference identity and text ranges are independent of the field or suggestion popup rendering them. Keep text-field rendering and popup placement outside token rules; use `ComposerAutocomplete` for edits and `MentionCatalog` for available mentions.

# Package com.letta.mobile.data.context

Context-window readings, usage snapshots and breakdown loading for the selected conversation. Presentation models carry model/window information and distinguish supported usage readings from estimates. Keep provider billing and platform rendering outside context readings; supply measurements through `ContextBreakdownLoader` and identify them with `ContextReadingKey`.

# Package com.letta.mobile.data.context.estimate

Local transcript token and context-section estimation. These calculations operate over supplied content and are not authoritative provider billing or server measurements. Keep authoritative provider accounting outside local estimates; substitute estimation policy through `TokenEstimator` and supply transcript data through `LocalTranscriptContext`.

# Package com.letta.mobile.data.context.limit

Context-limit choices, controller state and backend operations. App Server/Iroh adapters feed one workflow for supported limits and unsupported routes. Keep per-host copies of limit policy outside the controller; adapt backend effects through `ContextLimitRepository`.

# Package com.letta.mobile.data.controller

App Server controller coordination, device status, approval decisions and synchronization budgets. Runtime registration and routed events cross this shared boundary before reaching individual client consumers. Keep process launching and platform UI outside controller policy; bind runtime control through `AppServerController` and status publication through `DeviceStatusRepository`.

# Package com.letta.mobile.data.controller.capability

Capability advertisement and negotiation contracts with conservative defaults. Capability models describe supported operations; they do not prove what an arbitrary remote deployment implements. Keep authorization and deployment-health claims outside capability advertisement; provide offers through `CapabilityAdvertiser` and reconcile them with `CapabilityNegotiator`.

# Package com.letta.mobile.data.controller.channels

Enabled-channel account caching and restoration across App Server connection generations. Restoration binds ingress to the current connection while preserving information needed for recovery attempts. Keep a second independent channel host outside restore coordination; reconnect existing account state through `ChannelAccountCache` and `ChannelRestoreCoordinator`.

# Package com.letta.mobile.data.controller.extras

External-tool registries, offers and dynamic tool-source contracts. Platform CLI runners bind execution while the shared registry describes which tools can be offered to a runtime. Keep platform execution behind host adapters rather than discovery metadata; contribute tools through `ToolSource` and execution through `HostExternalTool` or `IrohCliRunner`.

# Package com.letta.mobile.data.controller.fanout

Runtime-scoped event fanout, inbound control-request tracking and stream-integrity monitoring. Generation changes, sequence gaps and replayed approval decisions are handled before delivery to consumers. Keep transcript interpretation and durable storage outside frame fanout; attach runtime connections through `RuntimeSessionLink` and route events through `AppServerRuntimeEventRouter`.

# Package com.letta.mobile.data.controller.node

Node identity, client/server contracts and relay configuration. These abstractions describe controller nodes without requiring every shared target to instantiate a native Iroh endpoint. Keep platform endpoint construction outside shared node contracts; supply implementations of `NodeClient`, `NodeServer` and `NodeIdentity`.

# Package com.letta.mobile.data.controller.node.iroh

JVM/Android Iroh controller hosting: authentication, pairing, viewer fanout and explicit admin-RPC handlers. Native App Server commands and read-only local storage are separate ownership tiers; sensitive workspace/plugin operations use dedicated handlers. Keep product UI and direct backend-file mutation outside protocol handling; add routes through `AdminRpcRoutes` and inject trust/storage policy through `IrohAuthPolicy` and `PairedPeerStore`.

# Package com.letta.mobile.data.controller.reconnect

Reconnect supervision and forwarding App Server clients. Backoff, synchronization and external-tool registration follow replacement connections while callers retain a stable client boundary. Keep platform suspend detection behind `SystemSuspendSignal`; attach session behavior through `ReconnectingClientListener` and tool restoration through `ExternalToolRegistrar`.

# Package com.letta.mobile.data.controller.registry

Runtime records and registry coordination. The in-memory implementation supports ephemeral state and tests; persistence must be supplied rather than inferred from the interface. Keep durable persistence assumptions outside the in-memory registry; consumers depend on `RuntimeRegistry` and coordinate mutations through `RuntimeRegistryCoordinator`.

# Package com.letta.mobile.data.desktopshell

Portable shell layout, conversation-tab ordering, persistence contracts and tab search. Desktop/browser shells reuse this state without depending on a windowing toolkit. Keep AWT, browser widgets and platform navigation outside layout policy; persist through `ShellLayoutStore` and derive changes through `ShellLayoutReducer`.

# Package com.letta.mobile.data.diff

Unified-diff parsing and presentation models. Consumers classify additions, removals and context without interpreting raw diff text inside UI components. Keep filesystem mutation and patch application outside parsing; consumers interpret `UnifiedDiff` and `DiffLine` values in their own viewer or editing workflow.

# Package com.letta.mobile.data.document

Shared document operations, access control, presence, storage ports and bounded history. Canvas and other document kinds build on these mutation/history primitives. Keep platform painting and storage mechanisms outside document semantics; add document behavior through `DocumentReducer` and effects through `DocumentStore`, `OpLog` and `PresenceTransport`.

# Package com.letta.mobile.data.document.markdown

A Markdown text/title document kind over the shared document primitives. Persistent panels can use it without adopting the canvas scene model. Keep canvas-specific elements and text widgets outside the Markdown document kind; integrate shared backing through `openMarkdownDocument` and apply operations through `MarkdownReducer`.

# Package com.letta.mobile.data.dreaming

Dreaming settings, history, notifications and controller state. App Server and admin-RPC adapters expose available reflection operations through one backend contract. Keep runtime ownership and platform presentation outside dreaming controls; supply operations through `DreamingRepository` and history through `DreamHistorySource`.

# Package com.letta.mobile.data.health

Server health probing, state and repository contracts. Service availability is reported separately from whether a conversation currently has an active turn. Keep active-turn state distinct from reachability; provide probes through `AppServerReachability` and consume updates through `ServerHealthListener`.

# Package com.letta.mobile.data.home

Renderer-independent Home/fleet projections and page controller. Injected sources, pins, recent navigation and agent activity combine into state for the shared Home page. Keep host widgets and duplicate fleet calculations outside home state; bind resources through `HomePageSource`, pins through `HomePinStore` and actions through `HomePageActions`.

# Package com.letta.mobile.data.lens

The Work/Play presentation lens over common underlying data. It changes vocabulary and framing rather than creating separate agents, conversations or memory. Keep changes to underlying agents or memory outside lens selection; hosts map `LensDestination` and `WorkPlayMode` to their navigation surfaces.

# Package com.letta.mobile.data.mapper

JVM/Android message-to-domain mapping and generated-UI extraction helpers. This compatibility facade is platform-scoped; portable chat/timeline projections live in their respective packages. Keep JVM compatibility facades out of portable dependencies; conversion callers supply `MessageMappingState` to `toAppMessage` rather than introducing platform policy into shared models.

# Package com.letta.mobile.data.memory

Memory categorization and parity-page models/controllers. Categories carry semantic UI roles while access to block content stays behind repository/content ports. Keep graph I/O and fabricated availability counts outside parity projection; provide data through `MemoryParitySource` and section reads through `MemoryParitySectionReader`.

# Package com.letta.mobile.data.memory.graph

Memory graph models, layout/force calculations and page/detail state. Loading full block content and committing edits are port operations rather than graph-renderer responsibilities. Keep drawing and unguarded persistence outside graph state; supply block content through `MemoryBlockContentPort` and mutations through `MemoryPageActions` and `MemoryBlockLifecycleActions`.

# Package com.letta.mobile.data.memory.memfs

MemFS file/history/diff sources and browser/editor state, including an App Server source. Shared reducers/controllers handle loading, saving and conflict-aware editor transitions. Keep direct filesystem access and editor widgets outside the reducer; connect reads through `MemfsSource` and changes through `MemfsPageActions`.

# Package com.letta.mobile.data.meridian

Meridian command catalog, parser/router and tool-offer modes. Requests are mapped to host tools and validated before endpoint or shell adapters execute them. Keep UI rendering and tool-policy bypasses outside command dispatch; route accepted calls through `MeridianCommandRouter` and constrain them with `MeridianRateLimiter`.

# Package com.letta.mobile.data.meridian.endpoint

Caller-bound Meridian tool service and request/response wire handling. Live-call tracking and token comparison support the local command endpoint; a display label does not authenticate a caller. Keep caller identity independent of display labels; bind authenticated callers through `MeridianCallerBinder` and expose tool execution through `MeridianToolsService`.

# Package com.letta.mobile.data.messaging

Inter-agent delivery routing and structured provenance. Stable sender/recipient relationships and delivery state remain separate from display-only labels used by presentation. Keep display labels out of routing trust decisions; carry identity and provenance through `AgentMessageClientId` and `AgentMessageProvenanceProjection` when integrating `IrohAgentMessageRouter`.

# Package com.letta.mobile.data.model

Shared models for agents, conversations, messages, models, tools and related resources, with normalization/compatibility helpers. A model's existence does not imply that every backend serves that resource. Keep network access and storage effects outside resource values; repository adapters map backend data to models such as `LettaMessage` and bind recency persistence through `RecentModelsPersistence`.

# Package com.letta.mobile.data.model.provider

Provider-management domain models and model-route availability. Canonical route identity is shared by catalog composition, selection and management workflows. Keep credential access and network execution outside provider descriptions; adapters construct `ProviderDefinition` and `CanonicalModelRoute` values under `VisibilityPolicy`.

# Package com.letta.mobile.data.model.provider.composer

Pure projection of provider/catalog inputs into a canonical catalog. Cache and selection state enter explicitly rather than hiding inside platform picker code. Keep fetching and hidden caching outside catalog composition; supply `CatalogScope` inputs to `CanonicalCatalogComposer` and consume `SelectionResolution` results.

# Package com.letta.mobile.data.model.provider.rpc

Provider-management RPC contracts, granular capabilities and client gating. Callers can distinguish supported management operations from host limitations before mutation. Keep permission bypasses and host UI outside provider RPC; supply requests through `ProviderRpcTransport` with explicit `ProviderRpcAuthContext` and availability.

# Package com.letta.mobile.data.model.provider.wire

Provider-management JSON models, codec, errors and domain mappers. Wire normalization and mutation serialization are centralized rather than repeated in screens. Keep RPC execution and secret presentation outside wire encoding; translate mutation payloads through `ProviderManagementWireCodec` and represent secret writes with `SecretWriteValue`.

# Package com.letta.mobile.data.modelvalidation

Validation of model handles for configuration/runtime flows. Syntactic validity is distinct from a model actually being installed or reachable. Keep reachability and installation claims outside syntactic validation; callers apply `ModelHandleValidator` before their own backend capability checks.

# Package com.letta.mobile.data.notify

Attention preferences, event gating and notification copy. Shared policy identifies events warranting attention; hosts supply operating-system notification presentation. Keep OS notification APIs outside attention policy; bind preferences through `AttentionPrefsRepository` and translate `AttentionDecision` into host effects.

# Package com.letta.mobile.data.observability

JVM-side Kotzilla monitoring bootstrap via reflective generated initialization. Desktop integration stays out of portable source sets and does not require common code to import JVM SDK classes. Keep JVM monitoring SDK dependencies outside common code; JVM/Android hosts opt into the reflective integration through `startKotzillaMonitoring`.

# Package com.letta.mobile.data.onboarding

First-run agent setup tasks and onboarding models. These describe work offered to an agent, not a separate backend or agent identity. Keep agent creation and task execution outside onboarding suggestions; hosts interpret `OnboardingTask` values produced by `AgentOnboarding`.

# Package com.letta.mobile.data.pairing

Pair-invite parsing, flow state, device access and paired-host storage ports. Scanned data moves through explicit invite/session states before becoming a stored connection. Keep scanners, QR drawing and platform storage outside pairing state; provide effects through `PairingRedeemer`, `PairedHostStore`, `QrEncoder` and `BackendConfigSink`.

# Package com.letta.mobile.data.platform

Heap and memory-pressure signals for shared consumers. Platform observers produce the signals so cache-release policy does not import operating-system monitoring APIs. Keep OS metric collection outside common policy; hosts publish pressure through `MemoryPressureHub` and consumers apply `HeapPressureGate`.

# Package com.letta.mobile.data.plugin

Plugin manifests, capabilities, packages, registry transitions and settings/secrets templates. These describe installation and consent; platform package readers and host drivers supply execution where available. Keep native process execution and product widgets outside portable plugin state; provide packages through `PluginPackageReader` and action effects through `PluginActionInvoker`.

# Package com.letta.mobile.data.plugin.catalog

Plugin catalog ports and management-page state. Controllers expose inspection and consent workflows while the connected host remains authoritative for installed state. Keep locally inferred installation authority outside catalog state; supply host-backed inventory through `HostPluginCatalog` and mutations through `PluginsPageActions`.

# Package com.letta.mobile.data.plugin.host

JVM host-side plugin lifecycle, process drivers, placement, quotas and telemetry. Launch/environment policy and capability checks apply at the host boundary rather than executing plugins inside product UI. Keep product rendering outside JVM plugin lifecycle management; bind execution through `PluginDriver`, confinement through `ProcessConfinementSource` and canvas access through `PluginCanvasAccess`.

# Package com.letta.mobile.data.plugin.remote

JVM/Android admin-RPC adapters for plugin catalogs and live views. Operations follow advertised host support and report unavailability instead of opening an alternate connection silently. Keep independent fallback connections outside remote adapters; bind the existing admin connection through `PluginAdminLink` and uploads through `PluginPackageUploader`.

# Package com.letta.mobile.data.plugin.view

The shared lcp-view/1 bridge: handshake, validated messages, lifecycle, rate limits, CSP and host ports. Platform page containers bind rendering while the bridge defines supported method/permission boundaries. Keep platform WebView construction outside bridge policy; connect messaging through `PostMessagePort`, actions through `PluginViewTransport` and access decisions through `PluginViewConsent`.

# Package com.letta.mobile.data.plugin.wire

JVM LCP JSON-RPC codecs, framing, typed calls and sessions. NDJSON/WebSocket transports share protocol envelopes, deadlines, cancellation and capability/secret guards. Keep plugin lifecycle and permission expansion outside framing; supply bytes through `LcpByteSource` and `LcpByteSink` and dispatch allowed calls through `LcpHostHandlers`.

# Package com.letta.mobile.data.presence

Agent/conversation run-presence state and reduction. Live status and avatar behavior consume these projections instead of inferring work state from message text. Keep text-derived guesses outside run identity and phase state; publish authoritative transitions through `ConversationRunPhasePublisher` and retain them in `ConversationRunRegistry`.

# Package com.letta.mobile.data.projects

Project page state, backend ports and controller operations. Host project summaries/identifiers are separate from issue-work repository capabilities. Keep issue-work capabilities distinct from project catalog access; provide backend operations through `ProjectsBackend` and pin persistence through `ProjectPinStore`.

# Package com.letta.mobile.data.repository

Cached repositories, change observers and channel/admin-RPC source adapters. Refresh, cursor and cache behavior uses injected persistence/remote boundaries; source tags identify remaining platform-scoped implementations. Keep platform storage engines and UI state outside repository policy; construct shared cached repositories with the source/cache contracts from `data.repository.api`.

# Package com.letta.mobile.data.repository.api

Repository interfaces and local/remote source ports for features and session graphs. Paging/older-message contracts expose bounded access independently of a platform cache implementation. Keep transport construction and process-wide singleton policy outside resource contracts; implement ports such as `IAgentRepository`, `AgentRemoteSource` and `AgentLocalCache` in adapters.

# Package com.letta.mobile.data.repository.appserver

App Server repository adapters and local admin gateway. They translate shared repository/client contracts into commands on an existing session. Keep connection ownership and host UI outside repository adapters; bind an existing session through `AppServerLocalRepositoryTransport` and admin operations through `AppServerLocalAdminGateway`.

# Package com.letta.mobile.data.repository.iroh

Admin-RPC repository and chat-gateway adapters for Iroh sessions. Resource operations use the connected channel rather than creating a separate REST backend. Keep silent REST fallback and a second session lifecycle outside these adapters; bind RPC through the injected `IChannelTransport` and supply agent directory access through `IrohAgentRepository`'s `directoryProvider`.

# Package com.letta.mobile.data.repository.modelcontrol

Model/provider management state, selection, connection forms and OAuth flows. Shared controllers own workflows while platform HTTP actuals and injected RPC invokers supply effects. Keep platform picker rendering and duplicate host policy outside model control; provide catalog access through `ModelPickerSource`, admin calls through `AdminRpcInvoker` and OAuth HTTP through `ProviderOAuthHttp`.

# Package com.letta.mobile.data.repository.subagent

Parent Agent-tool dispatch/return correlation keyed by tool-call identity. The reducer backfills known context without inventing child identity, and a dispatch return does not declare the spawned child's lifecycle complete. Keep tool-return completion distinct from child-run completion; callers supply `ParentContext` to `SubagentCorrelator` for identity correlation.

# Package com.letta.mobile.data.runs

Run-monitor controller state and run/step formatting. Sources provide execution records; inspection does not transfer ownership of the execution engine. Keep platform detail widgets outside run monitoring; hosts drive `RunMonitorController` and consume `RunSummary`, `RunDetail` and `RunFilter` values.

# Package com.letta.mobile.data.runtime

App Server turn execution, scoped leases, approval dispatch, queued input and terminal settlement. State machines preserve turn identity/delivery ownership across streaming, cancellation and connection loss; platform helpers validate local-backend paths where needed. Keep platform process creation outside turn lifecycle policy; provide defaults through `AgentDefaultsRepository`, identities through `TurnIdentityStore` and conversation reads through `ConversationRowsSource`.

# Package com.letta.mobile.data.runtime.host

Local App Server launch contracts and lease-based supervision. Platform launchers start processes; shared host state tracks readiness/lifecycle, including embedded runtimes that permit only one launch per process. Keep platform launch mechanics behind `LocalAppServerLauncher` and `LocalAppServerProcess`; use `LocalAppServerHost` for shared lease and lifecycle coordination.

# Package com.letta.mobile.data.runtime.supervisor

Pure crash/backoff policy and runtime health models. Exit decisions describe ignore/retry/intervention outcomes; process creation and termination remain host effects. Keep process creation and host UI outside crash decisions; feed `RuntimeExit` and `RuntimeHealth` into `RuntimeCrashPolicy` under `RuntimeCrashPolicyConfig`.

# Package com.letta.mobile.data.schedules

Cron construction, next-run projection, formatting and schedule-library state. Backend ports keep editing policy independent of the renderer and transport serving cron commands. Keep schedule execution in the backend rather than client timers; provide schedule reads through `CronScheduleSource` and control through `CronApi`.

# Package com.letta.mobile.data.schema

Checks and example generation for supported JSON-schema features. Structured problems identify invalid input paths before callers dispatch malformed payloads. Keep backend mutation and unsupported full-schema assumptions outside schema helpers; supply optional integrations through `JsonSchemaHooks` and consume `JsonSchemaCheck` results.

# Package com.letta.mobile.data.search

Message-search query/match models and text helpers. Search mode and source availability remain explicit rather than being inferred from the visible page. Keep invented routes and platform search widgets outside indexing; provide message access through `MessageSearchSource` or `ConversationMessageReader`.

# Package com.letta.mobile.data.secrets

Agent secret sources and vault state/controller logic. Secret values have redacted representations; explicit source operations access plaintext rather than exposing it through normal state formatting. Keep secret values out of ordinary display formatting and logs; provide guarded reads through `AgentSecretsSource` and mutations through `AgentVaultActions`.

# Package com.letta.mobile.data.serialization

Named production JSON configurations for wire and persistence layers. Central codec policy makes additive-field handling and deterministic serialization choices visible to callers. Keep independent ad hoc serializer policy outside adapters; choose the appropriate shared `LettaJson` wire, admin RPC or persisted configuration at each boundary.

# Package com.letta.mobile.data.session

Backend connection keys, session graph contracts and backend-scoped cache coordination. Session changes define repository/cache lifetime boundaries instead of leaving global state attached to obsolete backends. Keep stale process-global backend state outside resource graphs; construct graphs through `SessionRepositoryGraphFactory` and scope caches with `BackendConnectionKey`.

# Package com.letta.mobile.data.session.graph

Shared host repository graph construction and session-client adapters. Explicit unserved repositories preserve unsupported-route behavior when a host cannot provide a backend capability. Keep unsupported sources explicit instead of fabricating host capabilities; bind clients through `HostAppServerClients` and repository implementations through `HostRepositoryAdapters`.

# Package com.letta.mobile.data.settings

Settings page models and effect/storage ports. Section organization and host contributions are defined separately from UI layout and platform preference stores. Keep platform preference backing and widgets outside shared settings models; supply connection validation through `BackendConnectionTester` and render `SettingsRow` values in the host.

# Package com.letta.mobile.data.shell

Portable shell destinations, routes, workspace navigation and page errors. Entry points translate external navigation into these destinations instead of duplicating route policy. Keep platform navigation controllers outside portable routes; hosts map `ShellRoute` and `ShellDestination` and bind chat behavior through `ShellChatPort`.

# Package com.letta.mobile.data.skills

Skill catalog API, categorization and controller state. Available/installed entries and controllability come from active backend contracts, including explicit read-only/unavailable outcomes. Keep direct filesystem deletion outside catalog actions; provide supported backend operations through `SkillsApi` to `SkillsCatalogController`.

# Package com.letta.mobile.data.storage

Key/value, secure-settings and content-addressed asset ports. Large payloads can live outside document histories; file-backed implementations are platform bindings rather than universal storage. Keep filesystem and secure-store mechanisms behind effect contracts; implement `KeyValueStore`, `AssetStore`, `ImageBlobStore` and `SecureSettingsStore` for each host.

# Package com.letta.mobile.data.stream

SSE framing and incremental UTF-8 line parsing. These low-level utilities support transports using SSE; they do not define current Iroh timeline ownership. Keep reconnect policy and conversation state outside stream framing; feed bytes through `Utf8LineReader` and interpret `SseFrame` results from `SseParser`.

# Package com.letta.mobile.data.subagents

Durable keyed subagent registry, roster projections and stale-state reconciliation. Parent/child lifecycle records remain independent of display chips; supported hosts supply file-backed registry storage. Keep text-inferred relationships and platform UI outside durable child identity; provide persistence through `SubagentRegistryStore` to `DurableSubagentRegistry`.

# Package com.letta.mobile.data.swarm

Swarm sessions, directory/history access and queued-delivery tracking. Sender labels are display metadata; device profiles, stable IDs and send attempts carry messaging relationships. Keep display labels distinct from peer identity and durable send provenance; bind network effects through `SwarmLink` and pending delivery through `SwarmOutbox`.

# Package com.letta.mobile.data.system1

Optional System 1 decision/reflex contracts and turn interception. Auxiliary decisions degrade when unavailable rather than becoming a mandatory chat dependency. Keep the optional interceptor from becoming a requirement for ordinary chat; bind decisions through `System1DecisionEngine` and backend calls through `System1Client`.

# Package com.letta.mobile.data.templates

Starter-agent template models and controller operations. Templates describe initialization while agent creation remains an injected backend operation. Keep separate backend ownership and platform picker widgets outside templates; hosts drive `AgentTemplatesController` with `StarterAgentTemplate` values and their existing creation bindings.

# Package com.letta.mobile.data.timeline

Timeline reducers, synchronization and bounded canonical storage/presentation. Gateway loops and the paged canonical ledger are distinct APIs; live overlays, pending locals, durable rows and generation fences retain their respective ownership. Keep painting and direct host mutations outside timeline authority; supply persistence through `TimelineBoundedStore`, ingress through `TimelineTransport` and canonical commits through `TimelineCanonicalWriter`.

# Package com.letta.mobile.data.timeline.api

The narrow external-transport timeline writer contract. Send/transport code performs optimistic append, delivery changes and ingest through this boundary rather than directly mutating presentation rows. Keep UI code from bypassing canonical commit policy; external transports submit through `TimelineExternalTransportWriter`.

# Package com.letta.mobile.data.timeline.headless

Headless timeline storage, replay and streaming-reveal state. Semantic behavior can be exercised without a Compose viewport or platform application shell. Keep replay diagnostics from becoming a second product timeline writer; supply replay inputs and backing through `HeadlessTimelineReplaySession` and `HeadlessTimelineStore` to `HeadlessTimelineReplayer`.

# Package com.letta.mobile.data.timeline.snapshot

Stored envelopes, scope keys, snapshot codecs and incremental persistence planning. Persistence formats stay separate from live rendering so storage preserves scoped identities and replayable rows. Keep rendering and connection policy outside storage encoding; implement `ConfirmedTimelineStore` and image-body effects through `TimelineImageBodyReader` and `TimelineImageBodyWriter`.

# Package com.letta.mobile.data.tooloutput

Structured tool-output parsing for presentation. Classified content replaces repeated ad hoc interpretation inside individual tool cards. Keep tool execution and file writes outside output interpretation; viewers consume `ToolOutputDocument` and `ToolOutputBlock` produced by `ToolOutputParser`.

# Package com.letta.mobile.data.tools

Tool-library controller state and operations. Source loading and selection are coordinated outside platform-specific screens. Keep platform tool-list widgets outside library state; hosts drive `ToolLibraryController` and present its `ToolLibraryState` under `ToolLibraryFilter`.

# Package com.letta.mobile.data.transport

Channel events, server frames and chat bridge mappings. Scope routing and ingest deduplication are transport boundaries; these types do not themselves own a durable timeline. Keep durable timeline storage and product rendering outside frame routing; supply the channel through `IChannelTransport` when integrating `WsChatBridge` and `AgentEventRouter`.

# Package com.letta.mobile.data.transport.api

Channel/control interfaces consumed by session, chat and repository code. Explicit no-op/unavailable implementations support hosts lacking a particular connection capability. Keep unsupported optional operations explicit rather than simulating them; implement `IChannelTransport` and opt into capability interfaces such as `CronControlTransport` or `LivenessProbingChannelTransport` only when supported.

# Package com.letta.mobile.data.transport.appserver

App Server commands/frames, correlated requests, WebSocket transport and client adapters. Generations/retry classes constrain replay; workspace/provider operations share client boundaries with chat. Keep blind retries of non-idempotent operations outside client dispatch; supply a connection through `AppServerTransport` and adapt it with `AppServerTransportAdapter`.

# Package com.letta.mobile.data.transport.iroh

Iroh channel/turn transport, supervision, liveness and observer ingestion, with canvas/inter-agent adapters. Native endpoint and filesystem facilities follow platform tags; common frame contracts do not imply native Iroh availability everywhere. Keep native endpoint availability explicit per target; supply direct dialing through `DirectAppServerDialer`, address persistence through `IrohAgentAddressStore` and invitation verification through `InviteAuthenticator`.

# Package com.letta.mobile.data.workspace

Workspace file search, mentions and viewer state over injected file sources. App Server retrieval stays separate from UI navigation and file presentation. Keep local filesystem assumptions and cross-backend path interpretation outside viewers; provide content through `WorkspaceFileSource` and host opening through `WorkspaceFileOpener`.

# Package com.letta.mobile.data.workspace.directory

Working-directory picker state and path operations for remote POSIX/Windows devices. Paths describe the execution environment, which may differ from the client's operating system. Keep backend device paths distinct from the client's filesystem; provide listing through `WorkspaceDirectorySource`, creation through `FolderCreator` and persistence through `DefaultWorkingDirectoryStore`.

# Package com.letta.mobile.data.workspace.relay

Workspace command routing through a direct client or Iroh host relay. Sources retain one contract while route availability follows the active session. Keep independent session reconnection outside workspace relay calls; bind routing through `WorkspaceClientRoute` and requests through `WorkspaceRelayCall`.

# Package com.letta.mobile.qr

JVM/Android QR encoding and rendering contracts backed by ZXing. They produce bit matrices; pairing validation and trust decisions remain in pairing/controller layers. Keep pairing trust decisions outside QR generation; hosts use `ZxingQrEncoder` through the pairing `QrEncoder` contract and consume `QrCode` output.

# Package com.letta.mobile.runtime.iroh

Platform initialization hooks for the native Iroh runtime. Actual implementations vary by target; an unsupported-target hook does not promise a working native transport. Keep target-specific native initialization behind the `IrohAndroidInit` expect/actual seam; callers must respect the implementation available in their source set.

# Package com.letta.mobile.ui.ambient

Portable status-to-motion and shader inputs for ambient agent effects. Android/Skia renderers consume shared policy without importing rendering APIs into common logic. Keep renderer objects and OS animation APIs outside portable motion policy; renderers consume `AmbientMotionSpec` and feed activity changes through `AmbientEnvelopeRamp`.

# Package com.letta.mobile.ui.chat.render

Chat render models, timeline projection/presentation helpers and streaming-text smoothing. These are UI-facing transformations; composable row painting belongs to sharedUI. Keep composable painting and durable timeline writes outside presentation; bind transport inputs through `ChatTransport` and derive state through `ChatTimelinePresenter` and `ChatTimelineProjector`.

# Package com.letta.mobile.ui.chat.session

Chat session ports and action contracts consumed by shared surfaces. Hosts bind models, composer state, timelines and effects without importing platform ViewModels into product UI. Keep platform ViewModels and screen lifecycle APIs outside session contracts; provide behavior through `ChatSessionPort` and optional resources through `ChatModelSource` and `ChatWorkingDirectorySource`.

# Package com.letta.mobile.ui.common

General UI-state containers and timeline grouping helpers. These carry presentation data/policy rather than platform navigation or rendering infrastructure. Keep platform navigation and widget painting outside common presentation values; consumers map `UiState` and `GroupPosition` to their own host surfaces.

# Package com.letta.mobile.ui.haptics

Haptic cue identities, policy and effect contracts. Components request semantic feedback while hosts decide whether hardware feedback is available. Keep OS vibration APIs outside common cue policy; implement `Haptics` in each host or choose `NoHaptics` while sharing `HapticPolicy`.

# Package com.letta.mobile.ui.state

Refresh policy for retaining previously loaded content during newer requests. Request identities prevent obsolete outcomes from replacing the current screen state. Keep backend fetching outside retained-content transitions; callers supply request identity and results to `RetainedContentRefresh`.

# Package com.letta.mobile.ui.theme

Portable palette, spacing, shape, motion and chat tokens. Compose theme construction and semantic-color adaptation live in sharedUI/platform bindings. Keep Compose colors and platform theme objects outside portable tokens; rendering layers translate `LettaThemeTokens`, including spacing and color tokens, into their toolkit.

# Package com.letta.mobile.util

Formatting, telemetry and coroutine/error helpers. Platform actuals supply logging/time-format details so portable callers need not import operating-system utilities. Keep domain workflows and sensitive payload logging outside general helpers; bind telemetry through `TelemetryDelegate` and platform formatting through the declared expect/actual functions.
