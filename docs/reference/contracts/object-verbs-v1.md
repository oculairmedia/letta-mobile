# Object verbs v1: contract proposal

Status: **Accepted (Emmanuel, 2026-11-06); deliverable 1 of letta-mobile-i5jdm.9. Decisions recorded below; implementation is deliverable 2.**

This is the action and menu half of letta-mobile-i5jdm.3. It specifies portable
semantics shared by Android, desktop and web. This change supplies serializable
values, a compatibility projection and a shared fixture. It supplies no registry,
provider invocation, pin migration, UI adapter or plugin execution. Deliverable 2
will implement one shared `VerbRegistry` and one `core.pin` store after review.

## Identity and context

`ObjectRef = {kind, id}` identifies an existing canonical object within a trusted,
bound source. It never identifies a row, tab, view implementation or mount.

| kind | Existing typed identity | v1 wire id |
| --- | --- | --- |
| `agent` | `AgentId` | its opaque `value` |
| `conversation` | `ConversationId` | its opaque, resolved `value` |
| `project` | `ProjectId`, or the Projects catalog's `ProjectKey` | its existing host identifier; do not infer equality between different identifiers |
| `canvas` | `CanvasId` | its `value`, unwrapped at this boundary only |
| `shortcut` | `HomeShortcut` | existing stable `name`, such as `TOOLS` |

The typed companion extensions in [ObjectRefs.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/objectverbs/refs/ObjectRefs.kt)
reuse these types. Agent/conversation/project wrappers live in
[DomainIds.kt](../../../android-compose/core/ids/src/commonMain/kotlin/com/letta/mobile/data/model/DomainIds.kt);
[CanvasIds.kt](../../../android-compose/core/ids/src/commonMain/kotlin/com/letta/mobile/data/model/CanvasIds.kt)
keeps `CanvasId`'s existing persisted object shape. `ProjectKey` is in
[ProjectsBackend.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/projects/ProjectsBackend.kt),
and `HomeShortcut` is in
[HomeDashboard.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/home/HomeDashboard.kt).

Kinds are open strings, not a closed enum. Unknown kinds decode but expose no
verbs or facets to a v1 lens. Preserve opaque identity upstream. Do not derive
kind from an ID prefix or resolve an unknown kind as an agent.

The trusted host binds source/session, principal, object resolution, locale and
navigation services outside the serialized values. `VerbContext.lens` is a
presentation hint: `rail`, `list-row`, `tab`, `home-tile`, `context-menu`, or future
`command-palette`. It is never an authorization claim. The same `(kind,id)` on two
backends is two scoped references, not a reason to share a pin or access rights.
A bare conversation `default` is ambiguous: resolve the owner through the existing
[ConversationReadTarget.kt](../../../android-compose/core/ids/src/commonMain/kotlin/com/letta/mobile/data/model/ConversationReadTarget.kt)
and `IdFormats` rules before dispatch; keep unresolved legacy entries for migration.
Object schema version, object-verbs contract version and view mount binding remain
separate, as in
[RuntimeViews.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/plugin/runtimeview/RuntimeViews.kt).

## Verb descriptor and invocation

An `ObjectVerb` contains:

- `id`: stable, namespaced identity (`core.open`, `core.pin`, `core.rename`,
  `core.archive`). Never use the translated label or array position as identity.
  `core` is reserved; a future plugin owns its own namespace.
- `label`: provider-resolved, localized action text. The provider selects Pin or
  Unpin; a lens does not derive a second verb ID or local toggle policy.
- `icon`: portable token, initially `open`, `pin`, `pin-off`, `rename`, `archive`.
  Adapters map tokens to their icon system. Unknown tokens omit the icon and keep
  a recognized verb's accessible text.
- `availability`: evaluated result, never executable code in configuration.
  `{state:"enabled"}` permits a request; `{state:"disabled",reason:"…"}` remains
  visible with its nonblank accessible explanation; `{state:"hidden"}` is absent.
  Unknown states or disabled without a nonblank reason render nothing.
- `toggle`: optional current state. For `core.pin`, `{pinned:true}` means Unpin;
  `{pinned:false}` means Pin. It must agree with the pinned facet in that snapshot.
  `core.archive` similarly carries `{archived:false}` for Archive or
  `{archived:true}` for Restore. Missing state is unknown, never false. Future
  toggle fields are additive and ignored by older decoders.
- `inputSchema`: optional JSON object defining the input form. v1 supports JSON
  Schema's `type:object`, `properties` with string/boolean types, `required`,
  string `minLength`/`maxLength`, `enum` and `additionalProperties:false`.
  No remote `$ref`, scripts or executable validators. Unsupported required schema
  features disable invocation with an adapter explanation; never submit guessed input.
  An absent schema means no form. `core.rename` requires a nonblank `name` string;
  `core.pin` requires no form, but its invocation carries the desired `pinned` boolean.
- `group` and `order`: semantic menu placement, described below.

The future provider boundary is `describe(ObjectRef, trustedContext) -> observable
ObjectVerbSnapshot?` and `invoke(VerbInvocation, trustedContext) -> VerbOutcome`.
These are semantic signatures, not a second executable registry in this delivery.
A snapshot includes its object, presentation context, contribution, facets and verbs.
The host refreshes it when object state, provider ownership, permissions or binding
changes. The provider re-resolves and rechecks permissions/availability at invoke;
a stale enabled descriptor is not authority. Context cannot broaden rights.

A `VerbInvocation` names the reference, verb ID, context and optional JSON input.
For pin, send the desired state derived from the descriptor, not an instruction to
blindly invert storage. Repeated `pinned:true` requests are idempotent. A missing
object/provider or unsupported verb returns `Unavailable`, without side effects.
Concurrent state is serialized at the future shared store; refresh all lenses from
its confirmed state. No roster-wide fetch is necessary to pin one reference.

`VerbOutcome` is a serializable sealed result, discriminated by `type`:

| Outcome | Meaning and lens response |
| --- | --- |
| `updated` + snapshot | Mutation durably applied, or desired state already present; refresh from the authoritative snapshot |
| `open` + target | Host navigates using its existing authorized opener; a lens does not build a route from the ID |
| `cancelled` | User dismissed input/confirmation; no mutation |
| `unavailable` + reason | Provider/object absent, rights denied or stale availability; no mutation; refresh |
| `invalid_input` + property errors | Validation failed before mutation; keep the input and show errors |
| `failed` + reason, retryable | Effect failed; preserve confirmed state and offer explicit retry only when indicated |

Cancellation propagates through the future suspend boundary. A result that arrives
after a lens closes cannot update a replacement binding. If persistence cannot
confirm success, return failure and reconcile by rereading; do not claim Updated.
Unknown future outcome tags must be treated by the invocation adapter as unsupported
and trigger refresh, never success. The v1 sealed DTO does not decode unknown outcome
tags; that adapter boundary is deferred along with invocation.

## Facets are state

`ObjectFacets` is typed and read-only: `pinned:Boolean?`, `pinOrder:Int?`,
`running:Boolean?`, `needsAttention:Boolean?`, `kindTag:String?`. Missing/null means
unknown or unavailable, not false. `pinOrder` is zero-based and meaningful only
when pinned. `kindTag`, including `utility` or `persistent`, classifies the object;
it does not replace `ObjectRef.kind`. Future unknown facet fields are ignored.

Facets support grouping and badges even when the corresponding verb is hidden or
disabled. A badge is not an action. The utility tag cannot displace an explicit pin;
running and needsAttention do not reorder explicit user pin order. The rail's
pinned section, divider and display cap are lens presentation over these facets.
A cap never drops persisted pins. Home drag ordering changes the shared pin order
through a provider operation in deliverable 2, preserving objects outside the
visible subset; it does not create another lens-specific pin list.

## Contributions, ordering and absent owners

`VerbContribution` declares `owner`, supported `kinds` and `contractVersion`.
The first provider is trusted core. Core owns reserved IDs and the v1 facets and
publishes at most one descriptor per `(source,ObjectRef,verb id)`; duplicate
ownership is an error, never last-writer-wins. Future plugins may add descriptors
only after discovery through the existing registry, consent and host guards:
[PluginRegistryState.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/plugin/PluginRegistryState.kt),
[PluginRegistryReducer.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/plugin/PluginRegistryReducer.kt),
and [PluginHostRuntime.kt](../../../android-compose/sharedLogic/src/jvmMain/kotlin/com/letta/mobile/data/plugin/host/PluginHostRuntime.kt).
`PluginRegistryReducer.configureView` already selects runtime bindings without installing/enabling or
granting capabilities. This contract follows that boundary. Config can select an
already trusted owner; it cannot grant capabilities, run code, install a package
or claim the core namespace. No parallel plugin registry is proposed.

Menu groups sort `primary`, `organize`, `lifecycle`, `destructive`, then unknown
groups lexically. Within a group sort ascending `order`, then stable verb ID using
ordinal comparison. Never sort by translated label or provider registration timing.
Omit empty groups and trailing separators. A lens may promote recognized verbs to
inline slots but must use the same descriptor, availability and invocation path;
its overflow retains a deterministic order. No hard-coded Pin callback in any lens.

No provider, incompatible version, failed owner or missing object means no supplied
verbs/facets, not a crash or fallback execution. Keep the canonical object, view
binding, pin record and unknown data with their existing owner. Other independent
providers may still contribute recognized actions; unavailable state is not Unpinned.
A restored owner causes a fresh description. Absence never authorizes another owner.

## Lens and gesture contract

Rail, list row, tab, Home tile, context menu and future command palette ask for
verbs plus facets for the same `ObjectRef` in context. They render labels, enabled
state, disabled reasons, icons, groups and badges, collect supported input and send
an invocation. They never implement pin, archive, rename or permission predicates.
The host supplies platform input/confirmation/navigation bindings. This proposal
adds no command palette UI, agent-tool exposure or plugin verb implementation.

Long press, right click, overflow, keyboard Menu and Shift+F10 expose the same
context actions. Enter/Space on a selected enabled menu entry invokes that entry;
Escape dismisses. Activation/Enter on an object may invoke recognized `core.open`;
F2 may invoke recognized `core.rename` when offered and enabled. No global pin
shortcut is reserved in v1. Disabled or hidden actions cannot be invoked through a
keyboard bypass. Hold-drag remains the existing gesture arbitration: dragging
reorders, releasing a stationary hold opens the menu, and neither causes a second
click. Confirmation belongs to the shared action's input/host flow, not a custom
archive/delete implementation per menu.

Verified production menu entry points to replace in deliverable 2 (search at
`66b2c579c`; tests and KDoc references excluded):

| Existing constructor | Actual caller and lens |
| --- | --- |
| `ShellRowMenus.conversation` | `shellConversationMenu` in [ShellConversationRow.kt](../../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/sidebar/ShellConversationRow.kt): shared conversation context menu; called by `panelItemMenu` for chat rows in `ShellPanelLibrary.kt` |
| `ShellRowMenus.canvas` | `panelItemMenu` in [ShellPanelLibrary.kt](../../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/sidebar/ShellPanelLibrary.kt): canvas context menu in the mixed agent panel list |
| `ShellRowMenus.agent` | `ShellRailOrb` in [ShellAgentRail.kt](../../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/rail/ShellAgentRail.kt): collapsed rail orb menu |
| `ShellRowMenus.agent` | `ShellAgentLibrary` in [ShellAgentLibrary.kt](../../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/rail/ShellAgentLibrary.kt): expanded library `RosterActions.menu` |

There are four direct production constructor call sites, plus the chat forwarding
call in `panelItemMenu`; `ShellCanvasRow`'s KDoc is not an additional caller.
[ShellRowMenus.kt](../../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/ShellRowMenus.kt)
currently builds labels/callbacks itself. Desktop's `desktopWorkspaceActions` in
[DesktopShellContent.kt](../../../android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/DesktopShellContent.kt)
builds `ShellAgentRailActions` without `onAgentPinnedChange`; the nullable callback
in `ShellRailModels.kt` therefore suppresses agent Pin there. Android wires it in
[AdaptiveScaffold.kt](../../../android-compose/app/src/main/java/com/letta/mobile/ui/navigation/AdaptiveScaffold.kt).
Tabs and Home are additional consumers to adapt, not current direct callers.

## Versioning and shared fixture

`contractVersion:1` is explicit on snapshots and defaults to 1 in contributions.
It versions verb semantics independently of canonical object schemas and plugins.
Additive optional fields are allowed; incompatible behavior requires a new major
version. Decode through canonical `LettaJson.wire` (unknown keys ignored). Open
strings allow unknown kinds and verbs to decode. The pure `forRendering` projection
accepts the trusted adapter/provider's supported kind and verb sets, rejects
incompatible versions/unknown kinds completely, and omits unknown/hidden verbs and
invalid availability. It is not a registry, capability check or object resolver.
An absent provider has no snapshot. Support sets describe installed, compatible
handlers; they are not a permanent enum of core verbs, and a plugin descriptor
alone cannot add a supported handler. Preserve decoded unknown identities upstream;
rendering an unknown kind exposes no facets, even if it carries a known `core.pin`.

The canonical [v1.json](../../../android-compose/sharedLogic/src/commonTest/resources/contracts/object-verbs/v1.json)
is an array of snapshots: pinned and unpinned agents; a conversation with disabled
archive reason, an opaque unknown verb and a hidden verb; and an unknown object
kind with a recognizable pin verb. It includes utility, running and needsAttention
facets. The fixture is embedded at build time for filesystem-free common tests.
`ObjectVerbsContractTest` decodes it on all targets; JVM runs it through
`:sharedLogic:jvmTest`. This is a semantic contract fixture, not an Admin RPC method
or uncovered-method coverage entry.

## MIGRATION (deliverable 2 only)

Inventory verified against current source; storage bindings below are independent
even when some use shared helpers. All paths are repository-relative links.
Key definitions are in
[SettingsPreferenceKeys.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/repository/SettingsPreferenceKeys.kt),
[PinnedItemKeys.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/repository/PinnedItemKeys.kt)
and [ShellHomeShortcuts.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/shell/ShellHomeShortcuts.kt).
The production Home binding is
[ShellLibraryControllers.kt](../../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/pages/ShellLibraryControllers.kt);
desktop preferences are constructed in
[DesktopIrohBindings.kt](../../../android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/DesktopIrohBindings.kt).
Additional writers sharing those conversation preferences are
[DesktopConversationMarks.kt](../../../android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/chat/DesktopConversationMarks.kt)
and [ConversationBranches.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/chat/conversations/ConversationBranches.kt).

| Existing implementation and callers | Existing encoding/keys | Required import into the one store |
| --- | --- | --- |
| [ISettingsRepository.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/repository/api/ISettingsRepository.kt): `setConversationPinned`, `setAgentPinned`, `setProjectPinned`, `getPinnedAgentOrder`; [CachedSettingsRepository.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/repository/CachedSettingsRepository.kt) implements them | `SettingsPreferenceKeys.PINNED_CONVERSATION_IDS` = `pinned_conversation_ids` and `PINNED_PROJECT_IDS` = `pinned_project_ids` are string sets; `PINNED_ITEMS_ORDER` = `pinned_items_order` is an ordered JSON string list, with `agent:<id>` keys parsed by `PinnedItemKeys.kt` | Conversation/project sets and the full ordered agent source, without treating unordered sets as user order. `getPinnedAgentIds` is derived from `getPinnedAgentOrder`, not an additional store |
| [SharedNavDrawerViewModel.kt](../../../android-compose/feature-chat/src/main/java/com/letta/mobile/feature/chat/screen/SharedNavDrawerViewModel.kt) `setAgentPinned` / `setConversationPinned`; [AdminChatViewModel.kt](../../../android-compose/feature-chat/src/main/java/com/letta/mobile/feature/chat/screen/AdminChatViewModel.kt) `toggleAgentPinned` | Repository writes above; no extra store | Replace both mutation paths with the same `core.pin` provider; keep repository reads as adapters during cutover |
| `HomePageController.setAgentPinned` / `setShortcutPinned` / `reorderPins` in [HomePageController.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/home/HomePageController.kt); `HomePinnedItem` in `HomeDashboard.kt`; `SettingsStoreHomePinStore` in [HomePageSources.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/home/HomePageSources.kt) | Ordered newline keys `agent:<id>`, `shortcut:<HomeShortcut.name>`; default `home.pinnedItems`; production `ShellLibraryControllers.home` uses `ShellHomeShortcuts.PIN_STORAGE_KEY` = `desktop.home.pinnedItems` across hosts | Import both stored keys, preserve explicit empty vs absent/default, order, unavailable shortcuts, unresolved agents and persisted name caches |
| [HomePinnedGrid.kt](../../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/pages/home/HomePinnedGrid.kt) `unpin`, [HomeFleetSection.kt](../../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/pages/home/HomeFleetSection.kt) `AgentPinToggle` | Home controller callbacks above | Home tile and fleet action become lenses of the same pin verb and facets |
| [DesktopConversationPrefs.kt](../../../android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/chat/DesktopConversationPrefs.kt) `from`; `DesktopIrohBindings.kt` supplies it | `PinnedConversations` with `PINNED_KEY` = `desktop.conversations.pinned`, newline IDs | Import the actual desktop key as conversations; retain the existing fork/auto-pin behavior through the same shared writer |
| [PinnedConversations.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/chat/runtime/PinnedConversations.kt) `setPinned` / `toggle` / `inherit`; [ConversationManagement.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/chat/conversations/ConversationManagement.kt) `ConversationManagementPrefs.from`; [WebShellBindings.kt](../../../android-compose/web/src/wasmJsMain/kotlin/com/letta/mobile/web/WebShellBindings.kt) constructs it | Default `conversations.pinned`, newline IDs, distinct from desktop override | Import this key too. `ConversationManagement` and `DesktopConversationMarks` auto-pin/fork writes must delegate to the same store, not retain a second preference owner |
| `SettingsProjectPinStore` and `SettingsStoreProjectPinStore` in [ProjectsBackend.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/projects/ProjectsBackend.kt); `ProjectsPageController.togglePinned` | Repository project set, or newline `ProjectKey` values under default `projects.pinned`; production `rememberProjectPins` in [ShellAdminBinding.kt](../../../android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/pages/ShellAdminBinding.kt) overrides to `desktop.projects.pinned` | Import default and production override plus repository project pins; all project actions become one writer |
| [ActiveConfigSettingsRepository.kt](../../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/repository/ActiveConfigSettingsRepository.kt) | Pin setters are no-ops; reads empty | No persisted pins to import; do not interpret empty stub reads as deletion of positive imports |

The shared store is device-local with a trusted source namespace, keyed by scoped
`ObjectRef`. It has one ordered collection of pinned references, exposes pinned
and pinOrder facets, and is the only writer behind `core.pin`. No new transport or
cross-device sync is implied. The importer must read the actual backings, not only
currently visible rows or the active lens. Settings-backed agent order and Home
are already partially unified in concept but use different encodings/backings;
never assume one has subsumed the other.

Migration requirements before any old writer is removed:

1. Take recoverable snapshots of every available legacy backing. Record each
   source/key's raw value and parse result. A failed read/decode is not an empty
   list and cannot be marked imported. Keep the legacy data until verified.
2. Union positive pins across all backings, deduplicating only the same resolved
   scoped `(kind,id)`. An empty old store cannot negate another store's pin.
   Preserve unknown kinds, shortcut names, missing objects and ambiguous source
   assignments as opaque pending imports. Never guess that a historic pin belongs
   to the active backend, or merge different backends' IDs. No matching object is
   required to retain a pin; restore visibility when its owner/source returns.
3. Preserve existing canonical order first; then settings `pinned_items_order`,
   Home production order, Home default-key order, and remaining sources in the
   inventory's deterministic order. Preserve each ordered source's first occurrence;
   append unordered memberships by ordinal `(kind,id)`. Conflicting orders cannot
   both win: the settings `pinned_items_order` (rail) order takes precedence over Home
   order (decided 2026-11-06), while membership must be lossless.
   Absent defaults seed Home shortcuts only when no explicit Home setting exists;
   an explicit empty Home list remains empty, except positive pins from other sources.
4. Persist the union, pending raw entries, import provenance and per-source completion
   markers atomically or with a recoverable journal. Reopen and verify membership
   and ordering before completing a source. On crash, resume idempotently; on failure,
   keep reading the still-unmigrated source. Markers distinguish pending/unavailable
   backings from completed ones. Never mark a desktop store migrated from Android
   merely because its backing was not supplied there.
5. Cut all old mutators over to adapters of the one store, including Home reorder,
   project toggle, fork inheritance and auto-pin. Retain a tombstone/marker for
   explicit new-store unpins so repeated import or an old untouched key cannot
   resurrect them. Do not continuously union stale legacy reads after cutover.
   Readers transition to the same facets; no per-host fallback writes.
6. Acceptance must seed all keys with overlapping and disjoint pins, explicit empty,
   corrupt and absent values, unknown shortcuts, unavailable sources and conflicting
   orders; interrupt migration, reopen, unpin and repeat import. Assert no lost user
   pin, no resurrection, stable order and equal facets in Android/desktop/web lenses.

## Decisions (Emmanuel, 2026-11-06)

1. **Pin-order precedence on import:** the settings `pinned_items_order` (rail) order wins over
   Home order. After import there is one order, which the user changes by dragging.
2. **Legacy pins with an unknowable backend:** kept as hidden pending imports, never assigned by
   guess, and restored automatically when their source reconnects. There is no sort-out UI.
3. **Classification:** exactly one `kindTag` per object in v1 (for example `utility`,
   `persistent`). Multiple tags are deferred to a later contract version; unknown tags render
   nothing.

Invocation and migration acceptance, plugin-supplied verbs and command palette UI remain
deliverable 2 or later; this contract does not claim those paths already exist.
