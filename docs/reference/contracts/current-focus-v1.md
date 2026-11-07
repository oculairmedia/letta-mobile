# Current focus v1: contract proposal

Status: **PROPOSED for Emmanuel's review; contract-only slice of letta-mobile-vbha8.3.**

This proposes one workspace owner for current page, agent and conversation on Android,
desktop and web. This delivery adds serializable values, a pure startup-precedence
function and a shared fixture. It does not migrate storage, implement the live owner
transition loop, rewire consumers or claim device recreation acceptance. Migration
starts only after review. Existing APIs below are evidence, not the proposed API.

## Verified problem and inventory

Audit base: **bccf1570f**, the merged #2389 narrow rotation fix; rechecked after
rebasing onto **aa03569c9** (only notebook caching changed). Searches covered
production sources in `android-compose/{sharedLogic,sharedUI,feature-chat,app,desktop,web,core}`
for `selected`, `focused`, `current`, `active`, `agentId`, `conversationId`,
`SavedStateHandle`, `rememberSaveable` and `lastChatSelection`; direct writers and
callers were followed. R = Android rotation/configuration recreation; P = process
death; B = backend switch. Desktop resize normally recomposes the existing window,
not a new process or state holder. A memory-only value can survive that resize.

| Copy, verified file:symbol | Writers; readers | R / P / B today |
| --- | --- | --- |
| [ShellWorkspaceState.kt][workspace]: `page`, `focusedAgentId`, `selectedConversationId` | Constructor, `navigate`, `showPage`, `showChat`, `openNewAgent`, `conversationShown`, `rosterChanged`, `sessionChanged`; navigator and host direct assignments. Read by `destination`, `route`, `pageFor`, navigation and shared pages | Saver restores **destination** route since #2389, not bare page. P only where host saved-state delivery exists. `sessionChanged` clears IDs, retains the main page kind; first key only records itself |
| [ShellWorkspaceState.kt][workspace]: `lastActiveByAgent`, `conversationAgents`, `partialConversationAuthority` | `conversationShown`, both `conversationsReported` overloads, `sessionChanged`; `resolveAgentChat` and pruning read them | Memory only: lost on holder recreation/P, cleared on B when `sessionChanged` is called. These are history/ownership evidence, not separate current selections |
| [ConversationTabsState.kt][tabs]: `pinnedConversationIds`, `transientConversationId`; workspace `tabs` | `ConversationTabsReducer`, navigator tab verbs, `conversationShown`, `conversationsReported`; header and strip read | Memory only, **not saved** by current saver. Cleared by `sessionChanged`. No independent active-tab field |
| [ShellWorkspaceState.kt][workspace]: `agentEditor` / `editAgentId`, `railExpanded` | Navigation, new-agent/editor callbacks, rail gestures; main pane/drawer read | Saver retains rail; editor route replaces underlying page route when open. Editor target can restore, but underlying chat/focus and tabs are not independently encoded. Editor cleared on B; rail retained |
| [ShellWorkspaceBinding.kt][binding]: `ShellWorkspaceSaver`, `ShellChatSelection`, `SyncShellWorkspace` | Saver serializes `+/-` plus `workspace.route`; three independent effects apply session, conversation and authoritative list from chat | Saved representation of workspace, not another live owner. Effects can report null or an older chat after restored focus. No shared atomic arbitration |
| [ChatRoutes.kt][routes]: `AgentChatRoute`; [ChatRouteArgs.kt][args]: handle keys `agentId`, `conversationId`, `freshRouteKey`, construction snapshots and `pinnedExplicitConversationId` | Android page builds route from `Opening`; navigation/default args populate handle. `setRouteConversationId` writes resolved ID. Coordinator, VM, send/load paths read it | VM/store can survive R. Handle restoration is a separate mechanism; workspace chat store does not establish its own SavedStateRegistryOwner or prove independent P restoration. No source fence on these keys. Must not infer a P guarantee from SavedStateHandle alone |
| [ChatConversationCoordinator.kt][coordinator]: `activeConversationId`, `hasResolvedConversationOnce`, `hydratedConversationId`; [AdminChatViewModel.kt][vm]: `agentId`, `activeAgent`, `_sessionState`, `_uiState`, `conversationId` | Coordinator resolves route/recent target and mutates handle, updates `ChatSessionState`; VM maps session to `ChatUiState.conversationState`. Load/send/search and host `onConversationShown` read | `activeConversationId` is a **getter over the same handle**, not a second backing field. Agent ID is captured from route; active agent is a roster projection. Session/render projections and resolution flags are VM memory, survive R with retained store, lost on P. Config observer refreshes agents, not source-safe focus reconciliation |
| [WorkspaceChatStoreOwner.kt][chat-store] / [SharedNavDrawerViewModel.kt][drawer]: `chatStores`, `chatStore`, `releaseChat`; [AgentScaffoldBodyState.kt][body]: `rememberChatStoreOwner` | [ChatGraph.kt][route-key]: `toViewModelKey` chooses a store from agent, conversation, freshness and project; `onForgotten` releases except during changing configurations; VM clears all on owner clear | Retains chat VMs on R **if the route key matches**; lost on P. No selected agent/conversation field in drawer VM. Key lacks a backend namespace; B does not itself release all stores |
| [CachedSettingsRepository.kt][settings]: `_lastChatSelection`; [ISettingsRepository.kt][settings-api]: `lastChatSelection`, `setLastChatSelection`; [LastChatSelectionStorage.kt][selection-storage] | [AdminChatAgentSelectionCoordinator.kt][settings-writer]: `observeLastChatSelection` writes initially and on ready/name changes; constructor loads one atomic string `last_chat_selection_v1`. `NavViewModel.startDestination` reads once | Survives R/P via secure store. **Unscoped** triple (agent ID, name, conversation ID), not cleared by `clearCachesBeforeActiveConfigChange`; `clearAllData` clears it. [ActiveConfigSettingsRepository.kt][settings-stub] has null/no-op implementation, not another persisted store |
| [AppStartDestination.kt][start]: `resolveStartDestination`; [AppNavGraph.kt][nav]: remembered `start`; [MainActivity.kt][activity]: `launchTarget` | Activity `onCreate` / `onNewIntent` load the pending entry; callback clears it. Startup reads notification > settings last chat > favourite/admin agent > Home (Settings without config). Launch effect opens later notifications | `start` is a composition-time input, `launchTarget` an Activity-memory mailbox, not ongoing owners. Fresh composition rereads startup inputs then saver may restore over them; launch effect is another writer. Intent replay depends on consumption, not saved focus. Favourite/admin IDs are preferences, **not current focus** |
| [AndroidShellChatPort.kt][android-port]: `opening: Opening?`, `Opening.agent`, `conversation`, `fresh`, `resolved` | `show`, `open`, `adopt`, `selectConversation`, `createConversationForAgent`, `startNewChat`, `send`, `conversationShown`; [ConversationsGraph.kt][android-chat]: `AndroidChatPage` builds route, `openingFor` matches it | Port remembered by workspace, not saveable; R/P reconstruct it, while VM may survive R. `selectedConversation` itself delegates to workspace. Late `conversationShown` writes agent and conversation unconditionally. `agentOf` falls back to focused agent for an unknown ID |
| [DesktopChatModels.kt][desktop-models]: `DesktopChatSurfaceState.selectedConversationId`; `runtimeState` / `withRuntimeState`; [DesktopChatController.kt][desktop-controller]: `_state` | `selectConversation`, reload/merge, create/delete/reconnect through `ChatSessionReducer`; desktop chat, composer, frame, tabs, context and main pane read | Live controller memory, survives resize; not a durable last-focus preference. New controller/bootstrap after P/B has its own anchor-selection rules. Runtime state conversion is a projection, not another stored controller |
| [DesktopShellRoutes.kt][desktop-routes]: `focusFor`, `pageFor`, `shellChatSelection`, `DesktopShellChatPort`; [DesktopShellFrame.kt][desktop-frame]: `rememberDesktopAgentFocus`, `DesktopAgentFocus` | Derive focus from selected conversation's agent, else workspace/rail head; effects sync controller selection back to workspace | Frame and helpers are **derived**, not extra saved owners. Can override freshly focused shell agent with old selected chat until resolution. Desktop shell uses same saver from `LettaDesktopApp` |
| [DesktopChatShellHelpers.kt][desktop-helpers]: `filterStackConversations`, `contextFocus`; [DesktopSidebarBinding.kt][desktop-sidebar]: `desktopSidebarPanelState`, `desktopOpenConversation`; [DesktopShellMainPane.kt][desktop-main]: `desktopMainContentInputs`, `desktopPageHost` | Read frame/chat/workspace; sidebar/main-pane actions call router, controller or workspace setters | No extra durable store. Resize recomputes projections. Sidebar uses desktop destination plus chat selected ID, main pane refines page from frame rather than one focus snapshot |
| [LettaDesktopApp.kt][desktop-app]: `DesktopDeepLinkRouting.pendingConversationId`, `pendingAgentId`; [DesktopDeepLinkRouting.kt][desktop-links]: `DesktopDeepLinkRequest` | Deep-link callbacks set two remembered buffers; separate effects wait for conversation presence or settled initial load, then select controller/open agent and clear their buffer. `Main.kt` supplies the sequenced delivery flow | Pending buffers survive resize, not holder recreation/P; no source key or B reset. A missing conversation remains pending, and later catalog arrival can activate it after other navigation. The request flow is a delivery mailbox, not current focus |
| [TimelineConversations.kt][timeline]: `TimelineConversationsState.selected`, `failedInitialRoute`, `openInitial`; [ShellTimelineConversationList.kt][timeline-binding]: `SyncTimelineConversations` captured `routed` | Selection/create/send/delete and [TimelineConversationPages.kt][timeline-pages]: `TimelineConversationsState.without` write selection; web's per-conversation chat/port reads it. Sync captures workspace route before owner null selection overwrites it, then writes selected agent back | Per-session memory, rebuilt on B/P, survives normal resize. Explicit missing route can fall into `initialRouteFailed`; delete can choose another conversation. Captured `routed` is a second bootstrap target, not durable state |
| [BrowserRouteBinding.kt][browser-route]: URL `location.hash` / browser history | `publish(workspace.route)` writes fragment; `start` / `hashchange` / `followUrl` read it and call `workspace.openRoute` | Survives browser reload/back-forward as URL input; not a full saved workspace (no tabs/rail/underlying chat beside editor). No source namespace in route; current backend binding is separate. B can publish a reset route |
| [MemoryParityController.kt][memory]: private `selectedAgentId`, output memory `selectedAgentId`; [MemoryPageController.kt][memory-graph]: graph projection | `selectAgent` / `load` / `resolveSelection` write; Memory UI/backend queries read. Shell focus effect calls selectAgent | Private memory plus output projection. Factory rebuilds on graph change; Compose controllers rebuild on R/P. Missing requested ID falls back to first roster agent |
| [ScheduleLibraryController.kt][schedules]: `ScheduleLibraryState.selectedAgentId`, `initialAgentId` | `loadData`, `selectAgent`, `resolveSelection`, create/cron paths; page picker, CRUD, leases read. Shell effect and SchedulesPage effect both select | Per-controller memory, same graph/Compose lifetime as Memory. Missing ID falls back to first agent. Page `focusedAgentId` can disagree with controller's selected agent |
| [MemfsPageController.kt][memfs]: `MemfsPageState.agentId`, pending `SwitchAgent`; [SkillsCatalogController.kt][skills]: `SkillsCatalogState.agentId` | MemFS `selectAgent` and discard guard; Files picker reads/writes. Skills `reload(agentId)` caches query context; shell focus effect supplies it | Memory only. MemFS controller is remembered by source/scope, **not graph generation**, unlike Memory/Schedules; it can retain a target across B. Skills is per graph. These query/draft contexts must not become navigation owners |
| [CurrentConversationTracker.kt][tracker]: `_current` | Chat coordinator and lifecycle `setCurrent`, pause/clear set null; [NotificationDeliveryCoordinator.kt][notifications] suppresses visible-chat notification | Process singleton, memory only, re-established on R resume, lost on P; no source namespace. Visibility projection, not permission to navigate |
| [AgentEventScopes.kt][event-scopes]: `ChatSelection.selected`, attachment owner; [IrohNodeConnectionSupport.kt][viewer]: `ConversationViewerSubscription.viewed`, `confirmed`, `inFlight` | Attachment `select/release/claimForSend`; server subscription `subscribe/observe/settle/unsubscribe`; delivery routing/viewer registry read | Runtime subscription/connection memory, not user navigation or save state. Busy detached chat can remain attached. Never restore these as current focus or remove their concurrency guards |
| [CanonicalTimelineEngine.kt][engine]: `TimelineEnginePublication.selection`; [TimelineSyncLoop.kt][loop]: constructor `conversationId` | Engine `open(scope)` selects a generation-scoped timeline; paging/live presentation reads it. Loop constructor captures one conversation for load/send/subscription | Effect/data targets, not navigation owners. No saved current-focus field; underlying timeline data can persist independently. Rebuild/rebind on target/source change; retained R VM may retain its existing loop. Never erase busy-turn attribution to force it to match newly focused chat |

Corrections to the premise: there is already a shared workspace, navigator and saver;
#2389 fixes the ordinary chat route round trip. The remaining problem is independent
authority and ordering, not the absence of a shared holder. `conversationShown` does
not set focusedAgentId; Android and web do that separately, desktop usually derives
it with `focusFor`. Android's `AndroidShellWorkspace` refreshes on `config?.id` but
does **not** call `sessionChanged`; desktop `SyncShellWorkspace` and web
`WebBackend.loadRoster` in [WebShellBindings.kt][web] do. The Android binding is
[AdaptiveScaffold.kt][adaptive]: `AndroidShellWorkspace`; desktop calls sync from
[DesktopShellEffects.kt][desktop-effects]: `DesktopShellEffects`. Empty/loading
rosters can trigger the first-agent fallback. Derived `ShellPageFocus`,
`ShellRailFocus`, drawer input, context/model focus and immutable frame DTOs carry
copies for rendering/query attribution, with no independent restoration. Canvas,
subagent dual-view, outbox and busy-turn targets can name a different conversation
on purpose; they must retain their own captured operation identity and cannot
write current shell focus as a side effect of receiving data.

The 9cb37 patch snapshots **SavedStateHandle at construction**, then pins that ID on
first resolve. Its comment explains restored arguments shadowing a new explicit
conversation, but the snapshot is not a separate read of the original external
entry. It cannot establish precedence if restoration already replaced the value
before construction. New intent must enter the one owner before route projection.

Another lifecycle risk follows from those verified mappings: a new chat's `Opening`
keeps its original null conversation plus `fresh` after `settle` records `resolved`;
recreation rebuilds `adopt(agent, resolvedConversation)` without freshness. These
produce different `toViewModelKey` values. Keeping a VM store on rotation alone
does not prove the recreated page reuses that VM. This is a source-level inference,
not a device-tested mid-send failure; slice 3 must exercise that exact transition.

## One owner and exact state

**The existing `ShellWorkspaceState`, once per workspace/window and bound to a trusted
backend source, is the sole live owner.** Migration replaces its independently
writable focus fields with one immutable `CurrentFocus` published atomically. No
new platform state manager or parallel registry. The [CurrentFocus.kt][values] values
are proposed serializable contract types; the live transition implementation is deferred.
The four contract types live in the existing `com.letta.mobile.data.shell` package,
which already depends on `data.model`. At the contract-only base, shell had 25 types;
these four bring it to 29. With the 13 additional slice-1 owner/helper types, the
package has exactly 42 types, below the structure ratchet's default cap of 50.

| Authoritative value | Exact meaning |
| --- | --- |
| `CurrentFocus.pageRoute: String` | Canonical `ShellRoute` main-pane route **without `agent` or `conversation`** query fields; never `agent-editor`. Retains other payload/anchors, e.g. `skills?section=tools`, `settings?section=pairing`, `canvas?id=board`. Default `home` |
| `agentId: AgentId?` | Requested current agent, including a temporarily unavailable one. Null means no agent chosen; never implicitly the roster's head |
| `conversationId: ConversationId?` | Requested/resolved conversation **of that agent/source**. `exact` requires a conversation ID and either the agent ID or an **unresolved owner**: a conversation-only entry (`meridian://conversation/<id>`) is held as `exact` with `agentId` null until its owner is established by a targeted lookup, and its ID survives lookup failure, deletion and recreation as the unavailable target (never replaced by a guess, never dropped). A non-null conversation requires `exact`. Null is distinguished by mode |
| `chatMode: CurrentFocusChatMode` | `resume`: only chosen agent's human chat may resolve; `exact`: open precisely that conversation, including `default` resolved with its agent; `new`: intentionally empty chat, no recent fallback. `resume/new` require null conversation |
| `CurrentFocusSnapshot.sourceKey: String` | Opaque trusted source namespace, established by session infrastructure; no address, credentials or principal claims from a route. Same raw IDs on different sources are unrelated |
| `focus`, ordered `pinnedConversationIds`, `previewConversationId`, `editorRoute`, `railExpanded`, `contractVersion=1` | One atomic envelope. Tab pin means keep in strip, independent of object Pin. Tab IDs unique, preview not duplicated in pinned group; active tab derives from focus. Editor is null (closed), `agent-editor?agent=…` (edit) or `agent-editor` (create). Editor target can differ from focused chat agent |

The owner validates these invariants and `ShellRoute` decode/encode before publishing
or accepting a saved value. Reject malformed/unsupported snapshots as a unit; never
join an agent from one with a conversation from another. `LettaJson.wire` handles
additive unknown keys; unknown version/mode is unsupported, not a guessed target.
Saved envelopes use `LettaJson.persisted` so defaults/version/null slots are written;
fixtures decode with the strict `LettaJson.wire` convention. `initialCurrentFocus`
only arbitrates already validated candidates, filters source
and snapshot version, and projects settings focus; it does not validate routes or
resolve IDs. DTO decoding is not navigation authority.

Derived: full `ShellDestination`/route, page agent IDs, selected rail orb, active
tab, titles/names/identity, selected conversation summary, availability and loading,
context/model query targets, chat route args and notification visibility. Do not
serialize a second active key. Roster absence is an availability fact, not a write.
Editor form state belongs to its editor controller, not to current focus.

Source binding must distinguish durable backend identity from a live connection
generation. Existing [SessionGraphContracts.kt][session-graph]: `SessionRepositoryGraph.id`
is a generation; `backendDescriptor.backendId.value` is the existing device namespace
also projected by [SessionPins.kt][session-pins]: `pinSource`. However,
[RemoteLettaBackendDescriptors.kt][descriptors]: `remoteLettaBackendDescriptor`
uses platform prefix plus config ID, so editing a profile's endpoint can retain
that ID. Migration must bind the opaque durable `sourceKey` to that profile's
endpoint/principal binding revision and retire it when either changes. Reconnecting
the same binding keeps its key but advances runtime generation. No current API
provides that durable revision; adding it belongs to slices 1 and 7, with host
bindings in 3–5. Until source equality is established, do not restore identity.

Workspace auxiliary state: `lastActiveByAgent` is optional session-local history;
`conversationAgents` is rebuildable ownership evidence; neither can override an
exact target. A selected ID is not itself proof of ownership. Partial catalog
authority, overlay flags, pending pair links, modal drawer, background pane,
Work/Play lens and responsive [ShellLayoutStore.kt][layout]: `ShellLayoutStore` dimensions remain with their
existing owners; only rail expansion is in this navigation envelope. Lens/layout
are not additional page/agent/conversation state.

## Write path and ordering

All navigation writes go through the existing shared `ShellWorkspaceNavigator`
into the workspace's one serialized transition path. The following are semantic
operations for migration, **not newly implemented method signatures**:

1. Open page/agent/conversation, explicit new chat, deep link/notification, Home send,
   select/close/pin/reorder tab, open/close editor, change rail width, apply startup
   snapshot, and change source. A picker in Memory/Files/Schedules dispatches the
   same focus operation. A dirty MemFS draft may guard the transition; publish
   focus only after consent to leave, with no temporary header/backend mismatch.
2. Opening an agent atomically publishes its ID, null conversation and `resume`
   (or `new` for explicit New), before starting resolution. Never retain A's
   conversation while claiming focus B. For resume use existing home-lane semantics,
   then validated per-agent history, then eligible human conversation, then empty;
   never inbox/work or another agent. Creating a conversation is an effect, not
   the meaning of a null ID. Explicit New never resumes a recent conversation.
3. Every accepted target/source change creates a runtime request token scoped to
   **source generation + focus revision + requested agent + mode + exact ID**.
   Resolution, roster reads, creates and `conversationShown` return that token.
   Commit only while all fields still match. A later explicit selection wins;
   an old null emission is not a request to clear it. Focusing B while A resolves,
   selecting B's exact conversation before its resume completes, or switching
   sources all invalidate the earlier completion. Same-focus hydration does not
   bump selection or replay effects. Runtime tokens are never restored.
   Retained jobs may rebind explicitly to a recreated owner's current token;
   retaining a VM is not permission to accept its stale selection callback.
4. Once resolved, atomically set agent + conversation + `exact`, update optional
   history/ownership and tab strip, then project one snapshot to every reader.
   Persist confirmed focus as the next cold-start seed from this same path.
   Resolution can update availability without selecting. An in-flight send keeps
   its captured original target; navigation must never retarget or replay it.
5. Tab close uses existing deterministic adjacent-tab order. If the active tab
   has no neighbour, show Home and clear the conversation; every neighbour needs
   trusted same-source ownership before activation. Passive roster/list refresh
   never performs this explicit close operation. Editor open sets the main page
   to `chat`, preserving its agent/conversation, and fills the separate editor
   slot; closing leaves that chat intact. Editing B does not select B's chat.

Retire independent authority, not necessarily every public compatibility name:
workspace direct setters (`focusedAgentId`, `agentEditor`, `editAgentId`, `tabs`,
`railExpanded`); `navigate/showPage/showChat/openNewAgent/openRoute` bypass writes;
`conversationShown`, `rosterChanged` fallback, `focusFor` fallback and independent
session/list effects; Android `Opening.resolved/show/settle` writes; route
`setRouteConversationId` and pinned-argument precedence; coordinator
`setActiveConversationId`, `resolveMostRecentConversation`/initializer recent
resume writes; `AdminChatAgentSelectionCoordinator.observeLastChatSelection`;
desktop controller/reducer auto-selection on load/delete/retry; web
`selectConversation/openInitial/initialRouteFailed` and `without` fallback;
Memory/Schedules/MemFS picker selection authority. Keep old facades temporarily
only as dispatch/read adapters. No bidirectional sync loops.

Also retire `DesktopDeepLinkRouting`'s two independent pending-ID buffers: the
owner's newest token-bound entry replaces any older pending entry, including one
whose conversation never becomes listed. Browser fragment publication is an
output projection; back/forward is an explicit new entry with the same precedence
and source checks, not a second restoration loop. Android's NavHost may still own
temporary platform exits (camera/scanner/system screens); `CanvasRoute` arguments
are captured canvas/share context, not authority to change current chat selection.

## Read path

| Consumer | Read after migration; writes |
| --- | --- |
| Android `AndroidChatPage`, `ChatRouteArgs`, coordinator and session projection | Workspace focus plus its request token. Route is a transport input once, then a projection; SavedStateHandle cannot override the owner. Send/hydration uses a captured target/token |
| Desktop frame, helpers, sidebar, main pane, header/tabs, context/model/mention readers | Same workspace snapshot, with catalog joins for names and summaries. Remove chat-selected-agent/rail-head precedence and `pageFor(frame)` competing refinement |
| Web `TimelineConversations` and `SyncTimelineConversations` | Workspace target; list owner keeps catalog, paging and effects, not selected authority or captured startup `routed` |
| Drawer/rail/Home/search/keyboard/tabs | Workspace focus and tab order; actions dispatch navigator operations. `SharedNavDrawerViewModel` remains roster/actions and chat VM lifecycle binding |
| Memory, MemFS, Schedules, Skills, canvas owner | Workspace agent/conversation; controllers retain target-keyed query results and drafts only. A local picker cannot silently choose first agent or send writes under an old query target |
| Settings/startup | Read `lastChatSelection` once as a seed, never continuously sync it into focus. Only owner settlement writes future seeds |
| Notifications, event attachment and viewer subscription | Visibility/subscription projection of committed focus plus host lifecycle; retain busy-turn subscriptions and existing host permission/concurrency rules. These do not select the UI |

## Cold start and precedence

Arbitrate once before starting chat effects; a live `sessionChanged` source switch
is a reset transition and never re-seeds from settings. Then new user/external intents are
ordered transitions. Precedence is **new deep link/notification > new explicit
route entry > valid same-source restored workspace > source-verified settings
lastChatSelection > Home**. No-config startup still opens Settings before chat
resolution. A new external link wins as a whole target; never mix its conversation
with a restored agent. Deep link and its generated route represent one entry,
not two events. If independently supplied together, external entry wins. Consume
entry identity once; recomposition/rotation must not replay it. An old restored
route's `conversationId` is not a new explicit entry. This preserves 9cb37's intent
without relying on when SavedStateHandle happened to restore.

`CurrentFocusInputs` records that distinction. `deepLink`/`explicitRoute` are
already validated, source-bound snapshot envelopes; only their `focus` and
`editorRoute` are used. A normal entry closes the old editor; an editor entry
supplies the underlying chat focus and its separate editor target. Entry tabs
and rail values are ignored; valid same-source saved tabs/rail are retained.
Explicit Home and `new`
are present targets, not absent seeds. Settings contributes **focus only**;
it cannot restore tabs/editor. A late settings emission never overrides startup
or user navigation. Favourite/admin agent preferences are explicit conveniences
for a future chooser, not a silent replacement for missing current focus.

The existing settings triple has no backend ID. Do not assign it to whichever
backend is active. During migration preserve it pending source resolution; only
independent trusted ownership/provenance may produce `settingsSeed`. With unknown
source, no seed. New owner-written seeds must be scoped atomically. A raw legacy
`default` conversation needs its agent and targeted resolution; never identify it
globally. This proposal adds no settings migration or extra persisted key yet.

## Recreation and unavailable targets

| Event | Restore/retain | Deliberately drop / perform |
| --- | --- | --- |
| Rotation/fold/configuration recreation | Same-source atomic focus, main page payload, ordered kept + preview tabs, editor **target/mode** and rail expansion | Rebuild derived views/availability. Retain existing live chat VM/job where host lifecycle allows; snapshot never replays create/send. Editor form draft is not in focus; this contract restores editor navigation, not unsaved form content |
| Desktop window resize | Existing owner and all live workspace/navigation/editor state; a holder round trip uses the same envelope | No new startup arbitration, default agent pick, new chat or send. Responsive layout changes geometry only |
| Backend switch (`sessionChanged`) | Device presentation rail width; bind new trusted source/generation | Atomically show Home, clear agent/conversation, tabs, editor and per-agent history/ownership/partial authority. Invalidate old requests/ports/visibility before any new-source effect. Old source IDs never seed new source. **Proposal changes today's retained page-kind rule to Home**; do not interpret a roster refresh/reconnect on the same source as a switch |
| Process death with valid same-source host saved state | Same navigation envelope, including tabs and editor target; validate source/version first | No jobs, subscriptions, connections, initial-message text, composer/editor drafts, pair secrets, request tokens, history or authoritative-list flags. Reconcile/rehydrate target, never replay send. Pending `new` stays an empty chat until an explicit operation, not a replayed creation |
| Process death without host saved state / desktop process restart / browser reload lacking saved envelope | Apply startup precedence, source-verified settings seed if available, else Home | Do not claim Compose `rememberSaveable` itself provides a disk store on desktop/web. Host durable snapshot plumbing is a later slice; settings alone cannot restore tabs/editor |

Android's retained chat owner must use source-scoped logical identity and alias
the pending fresh-chat owner to its resolved conversation when it settles. A
recreated page must find that same owner; it must not create a second VM merely
because the route changed from fresh to exact. No fresh timestamp is restored or
replayed as a navigation command. This lifecycle binding is slice 3, not another
serialized selection field.

Snapshot round trips preserve requested identity even while loading or mid-send.
Unavailable data never makes the owner quietly pick another agent:

- Empty/not-yet-loaded/truncated roster is **unknown**. Keep requested IDs and
  targeted lookup; never interpret partial absence as removal or enumerate the
  whole fleet to validate one target. An authoritative roster absence plus a
  targeted not-found marks agent unavailable while retaining its ID. An exact
  known conversation may still hydrate when policy permits; a missing list row
  alone cannot veto it. Disable operations that require unavailable ownership;
  show Retry / Choose agent, with explicit user selection required to change focus.
- Deleted/not-found exact conversation retains agent and conversation IDs as an
  unavailable target with Retry / New chat / Choose conversation. No recent/home
  fallback, automatic replacement or create-on-404. Clear its history entry and
  inactive tab only on trustworthy deletion evidence; keep the selected tab as
  an unavailable reference until explicitly closed/replaced. Owner/source mismatch
  is an error, not permission to use focused-agent fallback. Archived is distinct
  from deleted and an explicit archived conversation can remain open.
- A complete per-agent page sequence may prune that agent's inactive tabs/history;
  visible recents, a failed fetch, a page before its final cursor, or another
  agent's complete list cannot. Return of the object resolves availability without
  changing identity. An unavailable editor keeps its target and retry/close UI;
  never opens another agent's form.

## Versioning, fixture and acceptance

The [v1.json fixture][fixture] is embedded by the existing Gradle fixture mechanism
for filesystem-free common tests. `CurrentFocusContractTest` decodes it using
`LettaJson.wire`, round-trips inputs and persisted snapshots (including explicit
version), and compares the pure arbitration result to
independently written expected snapshots. It covers rotation mid-chat; tabs/editor;
source change; missing agent; deleted conversation; external/explicit/saved/settings
precedence; explicit New/Home; invalid version/source; desktop resize; process death;
new-agent editor, editor links, foreign-source entries and empty startup.
Availability/event metadata are scenario context,
not an implementation of the deferred live reducer.

These tests establish **value serialization and startup precedence only**. They
do not prove live lifecycle, deletion detection, race rejection, host persistence,
editor drafts or a device rotation. Runtime migration acceptance must include:

1. A's late conversation resolves after shell focuses B: every reader stays B;
   B's late resume after exact B2 cannot displace B2. Include stale null, create,
   failure and old-source completions. No send into a target the owner did not commit.
2. Save at every state boundary, including mid-send and editor over chat; recreate
   owner, restore exact page/agent/conversation/tabs/editor target/rail, reconcile
   without replaying effects. Original send retains its original target.
3. Empty/loading then authoritative missing roster; partial pagination; deleted,
   archived and out-of-recents conversation. No hidden fallback or roster-wide fetch.
4. Real Android rotation/fold and process recreation; desktop resize plus window
   owner recreation; browser reload. Assert same identity in header, drawer, tabs,
   Memory/Schedules and actual chat load/send target, not just DTO equality.
5. Grep inventory after cutover: no consumer-owned persisted/current selection or
   autonomous fallback; derived snapshots, query keys and busy runtime subscriptions
   are allowed only with explicit owner provenance. Existing send/runtime tests stay.

## MIGRATION (after Emmanuel reviews; no implementation in this PR)

Owned file sets below are disjoint. A worker may read across slices but must not
edit another slice's files. Shared types/owner land first; consumers can then use
that API concurrently. Coordinate integration in slice 7; do not duplicate owners
to unblock a parallel slice. New files listed as directories are future work,
not claims that an API already exists.

| Slice / owned files | Work and acceptance |
| --- | --- |
| 1. Shared owner: `sharedLogic/.../data/shell/`, `ShellWorkspaceState.kt`, `ShellWorkspaceNavigator.kt`; `data/desktopshell/ConversationTabsState.kt`; corresponding common tests, `sharedLogic/Module.md` and API dumps | Implement one atomic transition owner, invariant validation, versioned snapshot/restore, source reset and request tokens. Existing navigator facades dispatch it. Test races A→B→B2, switch generations, missing/deleted targets, tab close and all round trips. No consumer edits |
| 2. Shared UI binding: `sharedUI/.../ui/shell/` **except `pages/`** and its tests | Saver uses envelope; remove competing Sync effects; rail/header/tabs and web timeline binding project owner. Persist editor separately from main focus. Test state-holder recreation and identical navigation projections with null/stale chat emissions |
| 3. Android host: `app/.../ui/navigation/`, `feature-chat/.../route/`, `feature-chat/.../coordination/`, `feature-chat/.../session/`, `feature-chat/.../screen/` and corresponding tests | Launch arbitration, backend source fence, token-bound `Opening` replacement, route/handle reads as projections, one lifecycle binding. Remove 9cb37 mutable precedence only when explicit `default`/fresh route tests pass; retain VM work on rotation. Instrument rotation, tabs/editor, source switch, process death and stale callback tests |
| 4. Desktop: `desktop/src/main/kotlin/` and matching tests | Controller selection becomes projection/effects, frame/helpers/sidebar/main pane read owner. Remove first-conversation/rail-head fallback. Test delayed resolution against rail switch, explicit deleted target, resize and recreated window; real gateway load/send target must match header |
| 5. Web host: `web/src/wasmJsMain/kotlin/`, `sharedLogic/.../data/chat/conversations/` and matching tests | Move TimelineConversations selection/fallback authority to owner; URL entries dispatched once; per-session ports consume token. Test outside-recents explicit link, failed exact target, browser reload and backend switch with old in-flight results |
| 6. Agent pages: `sharedLogic/.../data/memory/`, `data/schedules/`, `data/skills/`; `sharedUI/.../ui/shell/pages/` and matching tests | Controllers keep target-keyed loaded data/drafts; picker actions dispatch owner, no first-agent fallback; MemFS discard guard participates before focus changes. Assert shell agent equals query/CRUD agent across pages, failed roster, rapid picks and guarded navigation |
| 7. Settings/visibility and integration: `sharedLogic/.../data/repository/` and `repository/api/`; `core/android-data/.../data/channel/CurrentConversationTracker.kt`; `app/.../channel/NotificationDeliveryCoordinator.kt`; corresponding tests; final contract/doc status | Scope next-seed writes behind owner; retain unresolved legacy triple, seed once, verify cold start and saved/new-entry precedence. Bind notification visibility from committed focus and lifecycle. Run cross-slice inventory/acceptance above. Transport attachment/viewer implementation remains with its existing owner; inspect it, do not turn it into focus storage |

Order: 1 → (2, 3, 4, 5, 6, 7), then integrated exact-head acceptance before
removing compatibility adapters. Slice 3 retires the old settings writer while
slice 7 supplies the shared sink; coordinate this API in slice 1. None of these
slices may claim the whole bead accepted from the fixture test alone.

## Review choices for Emmanuel

Recommendations encoded above, awaiting review:

1. Backend switch: **Home with cleared source state (recommended)**, or retain
   main page kind without IDs (today's shared rule). Home avoids an agent page
   implying a new backend's default agent.
2. Editor recreation: **target/mode only (recommended for this contract)**, or
   add a separately owned draft-restoration contract before migration acceptance.
   Navigation restoration must not imply unsaved form content is durable.
3. Unknown-source legacy last chat: **retain pending, open Home (recommended)**,
   or add an explicit chooser to associate it. Never silently assign current backend.
4. Missing exact conversation: **retain unavailable tab until user chooses
   (recommended)**, or explicitly return to that same agent's empty chat. Neither
   option may choose another conversation or agent silently.
5. Editing endpoint/principal within the same config profile: **invalidate that
   profile's saved focus namespace (recommended)**, or require explicit reassociation
   of its old navigation. Config ID alone cannot prove object identity survived.

[workspace]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/shell/ShellWorkspaceState.kt
[tabs]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/desktopshell/ConversationTabsState.kt
[binding]: ../../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/ShellWorkspaceBinding.kt
[routes]: ../../../android-compose/feature-chat/src/main/java/com/letta/mobile/feature/chat/route/ChatRoutes.kt
[args]: ../../../android-compose/feature-chat/src/main/java/com/letta/mobile/feature/chat/route/ChatRouteArgs.kt
[coordinator]: ../../../android-compose/feature-chat/src/main/java/com/letta/mobile/feature/chat/coordination/ChatConversationCoordinator.kt
[vm]: ../../../android-compose/feature-chat/src/main/java/com/letta/mobile/feature/chat/screen/AdminChatViewModel.kt
[chat-store]: ../../../android-compose/feature-chat/src/main/java/com/letta/mobile/feature/chat/screen/WorkspaceChatStoreOwner.kt
[drawer]: ../../../android-compose/feature-chat/src/main/java/com/letta/mobile/feature/chat/screen/SharedNavDrawerViewModel.kt
[body]: ../../../android-compose/feature-chat/src/main/java/com/letta/mobile/feature/chat/screen/AgentScaffoldBodyState.kt
[route-key]: ../../../android-compose/feature-chat/src/main/java/com/letta/mobile/feature/chat/route/ChatGraph.kt
[settings]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/repository/CachedSettingsRepository.kt
[settings-api]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/repository/api/ISettingsRepository.kt
[selection-storage]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/repository/LastChatSelectionStorage.kt
[settings-writer]: ../../../android-compose/feature-chat/src/main/java/com/letta/mobile/feature/chat/coordination/AdminChatAgentSelectionCoordinator.kt
[settings-stub]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/repository/ActiveConfigSettingsRepository.kt
[start]: ../../../android-compose/app/src/main/java/com/letta/mobile/ui/navigation/AppStartDestination.kt
[nav]: ../../../android-compose/app/src/main/java/com/letta/mobile/ui/navigation/AppNavGraph.kt
[activity]: ../../../android-compose/app/src/main/java/com/letta/mobile/MainActivity.kt
[android-port]: ../../../android-compose/app/src/main/java/com/letta/mobile/ui/navigation/AndroidShellChatPort.kt
[android-chat]: ../../../android-compose/app/src/main/java/com/letta/mobile/ui/navigation/ConversationsGraph.kt
[desktop-models]: ../../../android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/chat/DesktopChatModels.kt
[desktop-controller]: ../../../android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/chat/DesktopChatController.kt
[desktop-routes]: ../../../android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/DesktopShellRoutes.kt
[desktop-frame]: ../../../android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/DesktopShellFrame.kt
[desktop-helpers]: ../../../android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/DesktopChatShellHelpers.kt
[desktop-sidebar]: ../../../android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/DesktopSidebarBinding.kt
[desktop-main]: ../../../android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/DesktopShellMainPane.kt
[desktop-app]: ../../../android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/LettaDesktopApp.kt
[desktop-links]: ../../../android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/DesktopDeepLinkRouting.kt
[timeline]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/chat/conversations/TimelineConversations.kt
[timeline-pages]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/chat/conversations/TimelineConversationPages.kt
[timeline-binding]: ../../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/conversations/ShellTimelineConversationList.kt
[browser-route]: ../../../android-compose/web/src/wasmJsMain/kotlin/com/letta/mobile/web/BrowserRouteBinding.kt
[memory]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/memory/MemoryParityController.kt
[memory-graph]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/memory/graph/MemoryPageController.kt
[schedules]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/schedules/ScheduleLibraryController.kt
[memfs]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/memory/memfs/MemfsPageController.kt
[skills]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/skills/SkillsCatalogController.kt
[tracker]: ../../../android-compose/core/android-data/src/main/java/com/letta/mobile/data/channel/CurrentConversationTracker.kt
[notifications]: ../../../android-compose/app/src/main/java/com/letta/mobile/channel/NotificationDeliveryCoordinator.kt
[event-scopes]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/transport/AgentEventScopes.kt
[viewer]: ../../../android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/IrohNodeConnectionSupport.kt
[engine]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/CanonicalTimelineEngine.kt
[loop]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/TimelineSyncLoop.kt
[web]: ../../../android-compose/web/src/wasmJsMain/kotlin/com/letta/mobile/web/WebShellBindings.kt
[adaptive]: ../../../android-compose/app/src/main/java/com/letta/mobile/ui/navigation/AdaptiveScaffold.kt
[desktop-effects]: ../../../android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/DesktopShellEffects.kt
[session-graph]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/session/SessionGraphContracts.kt
[session-pins]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/session/pins/SessionPins.kt
[descriptors]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/session/RemoteLettaBackendDescriptors.kt
[layout]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/desktopshell/ShellLayoutStore.kt
[values]: ../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/shell/CurrentFocus.kt
[fixture]: ../../../android-compose/sharedLogic/src/commonTest/resources/contracts/current-focus/v1.json
