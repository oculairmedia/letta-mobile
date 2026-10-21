# Module :sharedUI

Compose Multiplatform product pages and reusable UI for Letta. The shell, chat/composer,
canvas and resource pages consume `:sharedLogic` controllers and session ports. Android,
desktop and browser hosts supply platform effects rather than separate product behavior.
Start with shell/page bindings for integration, chat surfaces for conversations, and
components/theme packages for reusable visual structure.

Read each declaration's platform/source-set tags. `commonMain` owns shared composables
and state adapters; Android actuals bind Android UI services. `skikoMain` supplies Skia-side
implementations consumed by JVM/wasmJs where configured, while JVM and wasmJs actuals
remain distinct where host APIs differ. Native mascot/image/shader facilities can have
fallbacks: shared declarations do not promise identical hardware services on every host.

# Package com.letta.mobile.ui.a11y

Accessibility traversal ordering for the shared shell. Apply the modifier helpers at shell integration points; keep transport events and platform accessibility-service access outside this ordering layer.

# Package com.letta.mobile.ui.a2ui

Compose rendering of A2UI surfaces and supported widgets. Integrate through renderer actions, local state and link-policy composition locals; backend execution and permission decisions belong to the supplied protocol/action handlers.

# Package com.letta.mobile.ui.agenteditor

Agent-editor page, identity section and adaptive panes. Supply the editor controller and host bindings to the page; keep identity persistence and backend validation out of pane composition.

# Package com.letta.mobile.ui.canvas

Interactive canvas layers, gestures, cameras, selection, notes, formatting and synchronization status. Compose CanvasWorkspace around a supplied CanvasSession and asset/image bindings; durable document storage and replication belong to sharedLogic rather than gesture handlers.

# Package com.letta.mobile.ui.canvas.library

Canvas-library status presented through the shared overlay host. `CanvasLibraryNotice` keeps storage failures visible, offers reload-only retry, and withdraws the notice after recovery. Hosts supply the shared library controller; keep persistence and retry policy in sharedLogic rather than the composable.

# Package com.letta.mobile.ui.canvas.plugin

Plugin element renderers, fallback cards and live-view host contracts. Extend through PluginElementRenderers and PluginViewHost composition locals; plugin execution, capability enforcement and sandbox setup belong to the runtime/host, not fallback rendering.

# Package com.letta.mobile.ui.chat

Reusable chat identity, orb/layout and queued-send components. Consume these composables with supplied identity, activity and queue actions; do not add another send queue or agent repository here.

# Package com.letta.mobile.ui.chat.branch

Branch/name dialogs and message menus. MessageMenuActions and dialog callbacks are the integration seams; menu code must not decide backend branch support independently of supplied capabilities.

# Package com.letta.mobile.ui.chat.provenance

Visual labels for structured inter-agent provenance. Consume the label composables directly with structured provenance; transport decoding and identity inference must stay upstream.

# Package com.letta.mobile.ui.chat.render

Compose chat geometry, bubble styling, streaming reveal and tool syntax highlighting. Consume the geometry/style helpers and streaming-text adapter directly; durable message reduction and RPC handling stay in sharedLogic.

# Package com.letta.mobile.ui.chat.subagents

Subagent activity bars, roster/detail panes and dual-conversation views. OpenSubagentConversation, dual-view bindings and the todo-sheet opener connect navigation; discovery and lifecycle authority stay with the supplied subagent/session state.

# Package com.letta.mobile.ui.chat.surface

The shared chat/canvas surface in docked, collapsed and full-chat presentations. ChatSurface configuration and host/session bindings are the assembly seams; transport, conversation persistence and native back handling must remain in their owning layers.

# Package com.letta.mobile.ui.chat.surface.ambient

Ambient chat-panel glow rendering. Consume the glow modifier with supplied activity inputs; this drawing layer must not poll runtime status or assume an AGSL/Skia backend exists everywhere.

# Package com.letta.mobile.ui.chat.surface.composer

Prompt composer, autocomplete, attachments, model/permission controls and companion affordances. Session actions, composer inputs and platform image-ingress actuals connect the panel; network sends and durable draft policy stay behind the supplied session contracts.

# Package com.letta.mobile.ui.chat.surface.recents

Recent-interaction lists for the canvas chat bubble. Bind the list to supplied recent-interaction data and open callbacks; fetching and persistence stay outside the component.

# Package com.letta.mobile.ui.chat.surface.sendflight

Prompt-to-chat send-flight state, bindings and choreography. Integrate through SendFlightState, source/destination bindings and LocalSendFlight; animation completion must not become the authority for message delivery.

# Package com.letta.mobile.ui.chat.surface.status

Live run status and retry-line presentation. Consume RunStatusLine with supplied status and retry actions; do not derive a competing runtime lifecycle or retry policy here.

# Package com.letta.mobile.ui.chat.surface.timeline

Chat timeline, paged/resident lists, viewport behavior and page overlays. Supply timeline/session state, row bindings and deferred-body readers; persistence, paging authority and history reconciliation remain in sharedLogic.

# Package com.letta.mobile.ui.chat.surface.timeline.rows

User/assistant, tool-step, approval, reasoning, error and canvas-receipt renderers. Row context and action callbacks connect interactions to the session; rows must not issue independent transport requests or reinterpret approval authority.

# Package com.letta.mobile.ui.components

Reusable menus, list parts, chips, errors, refresh and pane primitives. Supply menu entries and callbacks through [LettaMenuItem](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.components/-letta-menu-item/index.html) and [LettaPopupMenu](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.components/-letta-popup-menu.html), and content through composable slots and modifiers; business workflows and repository access belong to callers.

# Package com.letta.mobile.ui.components.list

Roster models, sections, row parts and drag/reorder affordances. Extend through roster models, row-content slots and action callbacks; ordering persistence and deletion policy belong to the owner of the list.

# Package com.letta.mobile.ui.components.picker

Picker models and searchable selection components. Supply picker sections, row models and callbacks; fetching choices and committing domain changes stay with callers.

# Package com.letta.mobile.ui.components.tabs

Shared tab strip with stable tab identities and display models. Supply tab display models and action callbacks; this strip must not create its own navigation history or tab persistence.

# Package com.letta.mobile.ui.context

Context cards, usage displays and limit controls. Integrate through AgentContextCardBinding and limit-action callbacks; token accounting and backend limit mutations stay behind sharedLogic contracts.

# Package com.letta.mobile.ui.dreaming

Dreaming settings/history presentation with host-specific availability hints. DreamingState, DreamingActions and DreamingHostNotes supply behavior and availability; execution scheduling and reflection policy do not belong in the panel.

# Package com.letta.mobile.ui.haptics

Composition-local access to shared haptics contracts. Hosts provide LocalHaptics with a shared Haptics implementation; hardware access and permission handling stay outside common UI.

# Package com.letta.mobile.ui.input

Pointer-input plumbing shared by overlays: a layer drawn over other controls (the mascot transport layer, the composer's companion seat) passes the touches it does not answer on to what is under it. No platform input APIs.

# Package com.letta.mobile.ui.home

Small fleet-activity chart components. Consume FleetBarSpark with supplied samples and colors; collecting fleet events and aggregating history stay upstream.

# Package com.letta.mobile.ui.host

Pane, overlay and host-container infrastructure with lifecycle/pointer bindings. Provide LettaHost, OverlayHost and InspectorHost and use their container/content seams; page business logic and native window ownership stay outside generic pane presentation.

# Package com.letta.mobile.ui.icons

Centralized Lucide icon selection for product components. Consume LettaIcons directly; add shared icon mappings here rather than attaching navigation or backend capability decisions to icon selection.

# Package com.letta.mobile.ui.image

Image-byte to Compose bitmap decoding contracts and actual implementations. Call [decodeImageBitmap](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.image/decode-image-bitmap.html) through its shared contract and platform actuals; network loading, asset persistence and cache policy belong to the caller.

# Package com.letta.mobile.ui.markdown

Markdown rendering and streaming document/parser state. [LocalSharedRichMarkdownRenderer](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.markdown/-local-shared-rich-markdown-renderer.html) and [LocalMermaidDiagramRenderer](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.markdown/-local-mermaid-diagram-renderer.html) are optional rendering seams; message persistence, transport parsing and untrusted-code execution do not belong in markdown presentation.

# Package com.letta.mobile.ui.mascot

Agent avatars/mascots, identity, presence, gaze and seat coordination. Supply [MascotHost](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.mascot/-mascot-host/index.html), registry/transport locals and still-store bindings; native renderer creation and authoritative agent metadata persistence remain host/data responsibilities.

# Package com.letta.mobile.ui.mascot.frame

Skia frame-pipeline binding for live Rive mascots. [ProvideFrameMascots](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.mascot.frame/-provide-frame-mascots.html) accepts the frame renderer and its host dependencies; native renderer implementation and resource ownership must remain behind [RiveFrameRenderer](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.mascot.frame/-rive-frame-renderer/index.html) rather than enter common avatar components.

# Package com.letta.mobile.ui.memory

Memory page, graph and block editing components. Use the page/controller and content bindings plus graph callbacks; memory persistence and synchronization remain in sharedLogic, not graph gesture state.

# Package com.letta.mobile.ui.modelcontrol

Model/provider pickers, settings and sign-in/connection panes. Supply picker models, controller actions and provider forms; authentication, credential persistence and model routing remain outside presentation.

# Package com.letta.mobile.ui.notify

Per-agent notification preference controls. Provide [AttentionPrefsRepository](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.notify/-attention-prefs-repository/index.html) explicitly or through [LocalAttentionPrefs](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.notify/-local-attention-prefs.html); notification permission requests and operating-system delivery do not belong in these preference controls.

# Package com.letta.mobile.ui.pairing

Install/pair, invite, scanner and paired-device components. [PairDeviceBinding](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.pairing/-pair-device-binding/index.html), scan inputs and action callbacks connect host services; invite verification, identity storage and trust decisions stay outside the displayed cards.

# Package com.letta.mobile.ui.providers.icons

Provider-brand renderers and icon lookup. Consume [ProviderIcon](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.providers.icons/-provider-icon.html) and its lookup catalog directly; authentication and provider capability discovery stay outside brand artwork.

# Package com.letta.mobile.ui.runtime

Composition-local access to host-supplied per-agent defaults. Provide [LocalAgentDefaults](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.runtime/-local-agent-defaults.html) from the host repository graph; this package must not implement a second defaults store or runtime launcher.

# Package com.letta.mobile.ui.schedules

Agenda, week, timeline and history views. View parameter models and selection callbacks are the integration seams; cron execution, persistence and backend schedule mutations stay upstream.

# Package com.letta.mobile.ui.search

Reusable search scaffold, models and anchored/inline wrappers. Supply search models, result sections and selection callbacks to the shared wrappers; backend queries and navigation effects remain caller-owned.

# Package com.letta.mobile.ui.settings

Settings sections, search, rows and backend-connection presentation. Extend through [SettingsContribution](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.settings/-settings-contribution/index.html) and supplied settings models/actions; secure storage, connection establishment and platform permission effects remain behind those bindings.

# Package com.letta.mobile.ui.shell

Navigation drawer, page selection and contextual row/undo controls. [ShellPageDeps](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.shell/-shell-page-deps/index.html), workspace/navigation bindings and drawer actions connect destinations; repository implementations and native shell effects stay outside shared drawer composition.

# Package com.letta.mobile.ui.shell.header

Shell header, search slot and unified results. Supply [UnifiedSearchSources](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.shell.header/-unified-search-sources/index.html) and search-slot configuration; indexing inputs and navigation effects must come from the owning session/host rather than another repository in the header.

# Package com.letta.mobile.ui.shell.nowactive

The now-active conversation indicator. Supply [NowActiveBarState](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.shell.nowactive/-now-active-bar-state/index.html) and [NowActiveBarActions](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.shell.nowactive/-now-active-bar-actions/index.html) directly; active-run authority and conversation selection policy stay outside the indicator.

# Package com.letta.mobile.ui.shell.pages

Bindings between page controllers and shell destinations. Supply the session repository graph and host actions to the dependency/controller binding helpers; repositories and platform implementations must not be recreated inside page composition.

# Package com.letta.mobile.ui.shell.pages.agents

The agents library page. Supply agent page state and actions to [AgentsPage](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.shell.pages.agents/-agents-page.html); agent fetching, mutation policy and navigation effects stay with the controller/caller.

# Package com.letta.mobile.ui.shell.pages.channels

The channels page and status/filter parts. Supply channel state and action bindings; channel lifecycle and backend capability decisions must not be invented by filter or status components.

# Package com.letta.mobile.ui.shell.pages.conversations

Conversation library presentation and its UI model. [ConversationsPageState](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.shell.pages.conversations/-conversations-page-state/index.html) and [ConversationsPageActions](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.shell.pages.conversations/-conversations-page-actions/index.html) are the consumption seam; conversation storage and filtering authority stay with their upstream owner.

# Package com.letta.mobile.ui.shell.pages.home

Home dashboard and compact/expanded content sections. [HomePage](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.shell.pages.home/-home-page.html) inputs and callbacks bind dashboard sections; fleet collection, backend connection effects and pin persistence remain outside the layout.

The shared page requests fresh backend figures when entered, without repeating on ordinary recomposition. It offers Retry in its error banner and pull-to-refresh on touch hosts; there is no permanent header refresh button. Reconnection refresh belongs to the shared session source.

# Package com.letta.mobile.ui.shell.pages.memfs

MemFS file browser, editor and history/diff views. Supply MemFS editor/list/history state and actions; file I/O and conflict resolution authority stay in the shared controller/backend layer.

# Package com.letta.mobile.ui.shell.pages.memory

The Memory destination's block/file-oriented views of an agent. Supply [MemoryWorkspaceBinding](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.shell.pages.memory/-memory-workspace-binding/index.html) and its navigation actions; block/file persistence remains with the bound memory sources.

# Package com.letta.mobile.ui.shell.pages.plugins

Plugin management, inspection and consent UI. Supply [PluginViewsModel](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.plugin.view/-plugin-views-model/index.html) and consent/management actions; loading executable plugins and enforcing granted capabilities remain runtime responsibilities.

# Package com.letta.mobile.ui.shell.pages.projects

Projects list and detail surfaces driven by controller state. Bind [ProjectsPageController](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.projects/-projects-page-controller/index.html) through project actions and optional chat-opening callbacks; project persistence and issue-work support remain backend/controller concerns.

# Package com.letta.mobile.ui.shell.pages.runs

Run list/detail presentation in the shell. Bind [RunMonitorController](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.runs/-run-monitor-controller/index.html) through run-page actions; execution control and run/step retrieval remain controller operations rather than page-local polling.

# Package com.letta.mobile.ui.shell.pages.schedules

Schedule library and edit panes. Supply schedule state/actions to [SchedulesPage](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.shell.pages.schedules/-schedules-page.html) and its form panes; submission, execution and persistence stay in the shared controller.

# Package com.letta.mobile.ui.shell.pages.skills

Skills catalog rows and tool detail. Supply skill/tool state and supported actions; installing or mutating backend resources must remain behind controller capability checks.

# Package com.letta.mobile.ui.shell.pages.templates

Starter-agent template selection. Supply [AgentTemplatesState](https://oculairmedia.github.io/letta-mobile/api/sharedLogic/com.letta.mobile.data.templates/-agent-templates-state/index.html) and [TemplatesPageActions](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.shell.pages.templates/-templates-page-actions/index.html), optionally using [AgentTemplatesController.templatesPageActions(...)](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.shell.pages.templates/templates-page-actions.html); agent creation and template retrieval stay outside rendering.

# Package com.letta.mobile.ui.shell.pages.vault

Per-agent secrets vault and reveal/edit controls. Supply vault state and actions to AgentVaultPage; secret storage and authorization stay in the bound backend rather than the reveal control.

# Package com.letta.mobile.ui.shell.pages.workspace

Workspace file viewers/links and working-directory picker. Provide LocalWorkspaceFileOpener and picker/viewer callbacks; native file dialogs, process launching and file contents retrieval stay behind host/shared bindings.

# Package com.letta.mobile.ui.shell.palette

Command palette, new-conversation picker and quick-query panes. Supply [UnifiedSearchSources](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.shell.header/-unified-search-sources/index.html) and new-conversation/quick-query actions; command execution and conversation creation stay with the supplied handlers.

# Package com.letta.mobile.ui.shell.rail

Agent rail and expanded library. Supply rail/library models and ShellAgentRailActions; agent discovery and selection persistence stay outside tile and drag presentation.

# Package com.letta.mobile.ui.shell.scaffold

Page framing and adaptive list/detail structure. Supply [LettaPageScaffold](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.shell.scaffold/-letta-page-scaffold.html) content slots and [ShellListDetail](https://oculairmedia.github.io/letta-mobile/api/sharedUI/com.letta.mobile.ui.shell.scaffold/-shell-list-detail.html) list/detail callbacks; child business workflows and navigation state must remain caller-owned.

# Package com.letta.mobile.ui.shell.shortcuts

Shortcut catalog, dispatch and help overlay. Provide ShortcutHub and shell shortcut bindings; native reserved-key policy and command effects remain explicit inputs rather than hidden platform calls in help rendering.

# Package com.letta.mobile.ui.shell.sidebar

Agent panel/conversation sidebar with rename, archive and options dialogs. Supply sidebar row models and dialog/action callbacks; archive, rename and navigation effects stay in the owning controller/navigator.

# Package com.letta.mobile.ui.shell.workspace

The workspace mounted by desktop/browser hosts. LettaShellWorkspaceState and LettaShellWorkspaceHost connect product layout to hosts; native windows, backend repositories and transport lifecycles stay outside workspace composition.

# Package com.letta.mobile.ui.text

Touch text-selection and menu-density adaptation. Provide toolbar/quote-sink bindings and selection locals; clipboard/platform menu effects remain host bindings, while transport or message mutations stay outside selection handling.

# Package com.letta.mobile.ui.theme

Material theme construction, semantic colors, dimensions, motion and accessibility policy. Integrate through SharedMaterialTheme and accessibility preference/policy locals; platform preference observation belongs to actuals and domain behavior must not depend on color or motion tokens.
