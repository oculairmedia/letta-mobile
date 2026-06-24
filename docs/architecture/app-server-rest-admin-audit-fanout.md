# App Server REST/Admin Audit and Fanout Decision

Date: 2026-06-24

Beads: `letta-mobile-ph9ws.14`, `letta-mobile-ph9ws.15`

## Scope

This note audits the current REST/Admin clients under
`android-compose/core/data/src/main/java/com/letta/mobile/data/api` and the
repository/UI surfaces that depend on them. It also records the multi-client
App Server control-session decision for the `letta-mobile-ph9ws` migration.

No production code should move as part of this audit. The App Server migration
sequence remains:

1. Phase A: embedded Android loopback App Server for runtime transport.
2. Phase B: shared `commonMain` App Server client.
3. Phase C: headless CLI / remote runtime host.

## Boundary Decision

App Server is a runtime control plane: start a runtime, send input, receive
runtime events, interrupt/abort, reconnect, and expose runtime-native tool
execution. It is not a replacement for the Admin REST product API.

Keep Admin REST for product screens and durable server state:

- agent CRUD, import/export, context inspection, archive/tool/block bindings
- conversation CRUD, history listing, archival snapshots, message search
- block, archive, folder, identity, group, provider, model, passage management
- tool library and MCP server administration
- run/job/step monitors, usage dashboards, batch-message admin
- project catalog, beads remote provisioning, issue/work dashboards
- settings validation and backend capability probes

Only runtime-turn behavior is an App Server migration candidate. If App Server
later exposes native capability commands for a specific operation, treat that
as a runtime capability path, not as permission to mirror every REST endpoint
through WebSocket tools. A fanout controller must not become admin-shim v2.

## Wiring Summary

Android creates REST-backed repositories in `SessionGraphFactory`, injecting the
API clients into repository contracts such as `IAgentRepository`,
`IConversationRepository`, `IMessageRepository`, `IToolRepository`,
`IProjectRepository`, and the admin monitors. Local runtime mode only swaps in
limited local sources for agents, conversations, models, and timeline routing:

- `LettaCodeLocalBackendStore` implements `LocalRuntimeAgentSource` and
  `LocalRuntimeConversationSource`.
- `EmbeddedCatalogModelSource` implements `LocalRuntimeModelSource`.
- `LocalRoutingTimelineTransport` routes local conversation IDs to
  `LettaCodeLocalTimelineTransport`; remote conversations still use
  `MessageApiTimelineTransport`.
- Everything else in the session graph remains repository/Admin REST backed.

Desktop already shows the desired platform split: `LettaHttpAdminRepositories`
is a shared HTTP implementation for agents, tools, and schedules, while most
other admin contracts remain unavailable until a platform-neutral REST
implementation exists. This supports lifting reusable REST repository logic to
`commonMain`, but it does not make App Server the admin API.

## API Inventory

| API | Endpoint family | Primary repository/dependents | Migration stance |
|---|---|---|---|
| `AgentApi.kt` | `/v1/agents`, `/v1/agents/count`, `/v1/agents/{id}/context`, import/export, archive attach/detach | `AgentRepository`, `AgentPagingSource`, `AdminAgentManager`, `SessionGraphFactory`, `AgentListViewModel`, `DashboardViewModel`, `TemplatesViewModel`, `AdminChatViewModel`, archive/block/identity/schedule/tool screens, `LettaHttpAdminRepositories` on desktop | Keep REST. App Server can provide runtime lifecycle, not agent catalog/admin CRUD. Local runtime may keep local agent source for embedded agents. |
| `ConversationApi.kt` | `/v1/conversations`, fork/cancel/recompile | `ConversationRepository`, `AllConversationsRepository`, `ConversationPagingSource`, `MessageApiChatGateway`, `WsChatSendCoordinator`, `TimelineSendCoordinator`, `ChatSessionResolver`, `ConversationsViewModel`, `ChannelHeartbeatSync`, `ChatPushService` | Keep REST for list/create/update/delete/history navigation. Runtime send/cancel may move to App Server, but durable conversation CRUD remains Admin REST unless a first-class product API changes. |
| `MessageApi.kt` | `/v1/agents/{id}/messages`, `/v1/conversations/{id}/messages`, `/stream`, `/v1/messages/search`, `/v1/messages/batches` | `MessageRepository`, `MessagePagingSource`, `MessageApiTimelineTransport`, `MessageApiChatGateway`, `TimelineRepository`, `AdminChatViewModel`, conversations/dashboard/message batch screens | Split. Send/stream/cancel runtime turn paths are App Server candidates. History, search, batch admin, and hydration remain REST. |
| `ToolApi.kt` | `/v1/tools`, count, generate schema, attach/detach agent tools | `ToolRepository`, `ToolDetailViewModel` direct schema call, `ToolsViewModel`, `AllToolsViewModel`, `McpViewModel`, `AgentListViewModel`, `DashboardViewModel`, `TagDrillInViewModel`, `LettaHttpAdminRepositories` on desktop | Keep REST for tool library/admin binding. App Server external-tool execution must not mirror library CRUD. |
| `BlockApi.kt` | `/v1/blocks`, `/v1/agents/{id}/core-memory/blocks`, identity attach/detach | `BlockRepository`, `BlockLibraryViewModel`, `DashboardViewModel`, `AdminChatViewModel`, `ProjectChatCoordinator` | Keep REST. Blocks are memory/admin product state, not runtime transport. |
| `ArchiveApi.kt` | `/v1/archives`, archive agents, archive passages | `ArchiveRepository`, `ArchiveAdminViewModel` | Keep REST. Archive/library administration is product state. |
| `FolderApi.kt` | `/v1/folders`, metadata, upload, agents, passages, files | `FolderRepository`, `FolderAdminViewModel`, `AdminChatViewModel` | Keep REST. File/folder admin and RAG corpus state remain Admin REST. |
| `ProviderApi.kt` | `/v1/providers`, provider check | `ProviderRepository`, `ProviderAdminViewModel` | Keep REST. Provider configuration is admin state. |
| `RunApi.kt` | `/v1/runs`, messages, usage, metrics, steps, cancel/delete | `RunRepository`, `RunMonitorViewModel`, `UsageViewModel`, `DashboardViewModel` | Keep REST for monitors and history. App Server events may eventually feed live runtime status, but should not replace run admin queries until equivalent server semantics exist. |
| `JobApi.kt` | `/v1/jobs`, cancel/delete | `JobRepository`, `JobMonitorViewModel` | Keep REST. Jobs are server admin resources. |
| `StepApi.kt` | `/v1/steps`, metrics, trace, messages, feedback | `StepRepository`, `RunMonitorViewModel`, `UsageViewModel`, `TagDrillInViewModel` | Keep REST for persisted step inspection and feedback. App Server event replay may supplement live views only. |
| `ScheduleApi.kt` | `/v1/agents/{id}/schedule`, `/v1/crons` fallback | `ScheduleRepository`, `ScheduleListViewModel` direct cron fallback, `LettaHttpAdminRepositories`, `ScheduleLibraryController` | Keep REST for schedule management. App Server may emit runtime cron events, but schedule CRUD stays Admin REST. |
| `McpServerApi.kt` | `/v1/mcp-servers`, tools, refresh, run tool | `McpServerRepository`, `McpViewModel`, `McpServerToolsViewModel`, `AllToolsViewModel` | Keep REST for MCP server catalog/admin. Tool execution can be considered a runtime capability only if App Server provides native semantics; do not proxy admin CRUD through fanout. |
| `ProjectApi.kt` | `/api/projects`, `/api/projects/{id}/beads-remote`, `/api/sync/trigger`, `/api/registry/projects` | `ProjectRepository`, `ProjectHomeViewModel`, `CreateProjectViewModel`, `CapabilityRepository` probe | Keep REST. Project registry/sync/beads remote features are vibesync/admin product APIs. |
| `ProjectWorkApi.kt` | `/api/projects/{id}/ready-work`, `/api/projects/{id}/issues`, `/api/issues/{id}` mutations, analytics | `ProjectWorkRepository`, `ProjectIssuesViewModel` | Keep REST. Work/issue dashboards are product APIs, unrelated to runtime transport. |
| `GroupApi.kt` | `/v1/groups`, group messages, stream, reset | `GroupRepository`, `GroupAdminViewModel` | Keep REST for group admin. Group send/stream is runtime-adjacent but should only move if App Server gains native group-runtime semantics. |
| `IdentityApi.kt` | `/v1/identities`, properties, agent/block bindings | `IdentityRepository`, `IdentityListViewModel` | Keep REST. Identity management and bindings are admin state. |
| `ModelApi.kt` | `/v1/models`, `/v1/models/embedding` | `ModelRepository`, `AgentListViewModel`, `ModelBrowserViewModel`, `AdminChatViewModel`; local mode can use `EmbeddedCatalogModelSource` | Keep REST for remote model catalog. Local embedded catalogs remain local source behavior, not App Server replacement. |
| `PassageApi.kt` | `/v1/agents/{id}/archival-memory` | `PassageRepository`, `ArchivalViewModel` | Keep REST. Archival memory CRUD/search is product state. |
| `ProjectAgentApi.kt` | `/api/agents/lookup` | `ProjectHomeViewModel` optional project-agent lookup | Keep REST. This is project/product metadata. |

Nearby support clients:

- `LettaApiClient.kt` owns Admin REST client creation and explicitly rejects
  local runtime mode as an Admin API endpoint.
- `CloudConnectionValidator.kt` validates `/v1/agents`.
- `VibesyncAdminApi.kt`, `VibesyncDebugApi.kt`, and
  `VibesyncEventStreamRepository.kt` are vibesync/admin surfaces and should not
  be reimplemented as App Server control commands.
- `SlashCommandRepository.kt` still calls admin-shim routes directly for slash
  commands/goals. It is not part of the requested list, but should be treated
  as an admin/product compatibility API until separately redesigned.

## WsChatSendCoordinator Finding

`WsChatSendCoordinator` currently owns the admin-shim mobile WebSocket send
path. Before sending over WS it checks the active conversation ID. If none is
active, it calls `conversationRepository.createConversation(AgentId(agentId))`
and uses the returned concrete conversation ID for the WS `send_message`.

The code comment states the live shim requires every `send_message` to carry a
real `conversation_id`; the client therefore pre-creates through REST instead
of sending a blank placeholder and relying on shim-side minting. That also
keeps the UI and timeline stable: the coordinator sets the active conversation,
starts the timeline observer, appends the optimistic local message against the
same ID, and reconciles `TurnStarted`, deltas, `TurnDone`, cursor repair, and
pending-send cleanup against that ID.

For App Server migration this means conversation identity is a boundary to
decide explicitly:

- If App Server accepts an existing conversation ID, preserve the REST
  pre-create path for remote Admin REST conversations.
- If App Server owns local embedded conversation creation, route local
  conversation creation through the local runtime source and keep the REST path
  for remote/Admin conversations.
- Do not reintroduce blank placeholder conversation IDs at the fanout layer.

## Fanout Decision

Recommended topology:

- Phase A embedded Android loopback: mobile connects directly to its own local
  App Server process. No fanout is needed.
- Phase B shared client: Android, desktop, and tests use the same App Server
  client abstraction, still direct for one local runtime/client.
- Phase C remote/headless: run one authoritative control client per App Server
  runtime process, then expose a narrow fanout controller for multiple product
  clients such as mobile, desktop, CLI, Matrix, or future collaboration hosts.

Reasoning:

- App Server permits one active control session per process; a new control
  session replaces the listener runtime. Multiple product clients connecting
  directly to the same App Server would evict each other.
- One App Server process per product client avoids eviction but multiplies
  runtime processes, ports, auth, working directories, replay buffers, and
  command arbitration. It also cannot represent "several clients observing the
  same runtime" without duplicating or desynchronizing runtime state.
- A single control client with fanout preserves App Server's ownership rule and
  gives mobile/desktop/CLI/Matrix a stable shared runtime view.

The fanout controller may own:

- runtime registry and routing to one control session per runtime process
- authenticated product-client sessions
- event replay/buffering with cursor semantics
- reconnect and backoff policy
- command arbitration and per-runtime locks
- presence/observer state
- protocol translation from App Server frames to the app's narrow runtime event
  surface

The fanout controller must not own:

- Admin REST endpoint mirroring
- agent/tool/block/archive/folder/provider/model/project CRUD
- product dashboards, issue/work APIs, usage reports, or admin search
- synthetic REST-over-WS external tools
- shim compatibility expansion beyond the narrow runtime transport surface

Clients should connect as follows:

- Mobile embedded/local: direct loopback App Server for runtime execution; Admin
  screens continue through local sources or REST depending on backend mode.
- Desktop local: direct App Server when hosting its own local runtime; Admin
  REST repositories remain separate.
- CLI/headless: starts or attaches to App Server and hosts the fanout controller
  for remote observers.
- Mobile/desktop remote: connect to fanout only for runtime transport/events;
  keep using Admin REST for product screens.
- Matrix/future multi-client integrations: connect as observers/command senders
  through fanout with arbitration, not directly to App Server.

Follow-up implementation work should be filed only when Phase C begins. Phase A
and Phase B should not block on fanout implementation.
