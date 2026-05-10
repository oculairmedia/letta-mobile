# letta-mobile Architecture Review

Updated review of the current letta-mobile architecture after the timeline migration, Client Mode stabilization, and chat contract hardening work.

## Current Structure

The project is best understood as a **module-based architecture**, not a single package tree.

```
android-compose/
├── app/           # Screens, navigation, ViewModels, app-specific orchestration
├── core/          # API, repositories, timeline, stream utilities, shared models
├── bot/           # Bot gateway, WS/HTTP protocol, sessions, runtime adapters
├── designsystem/  # Reusable UI components, theme, icons, dialogs
├── cli/           # Developer / validation CLI tools
├── macrobenchmark/
├── baselineprofile/
└── native/
```

Within those modules, the older `data / domain / ui / util` split still exists, especially in `core/`, but it is no longer the primary architectural story.

## What Is Working Well

1. **Module boundaries are clearer than the older review implied**
   - `app` owns screen state, navigation, and app-specific orchestration.
   - `core` owns message/timeline persistence and sync.
   - `bot` owns gateway/session/runtime transport behavior.
   - `designsystem` owns reusable Compose UI.

2. **TimelineRepository is now the chat source of truth**
   - Client Mode assistant/reasoning/tool chunks route through `TimelineRepository`.
   - Fresh-route pre-conversation chunks are buffered and replayed into the timeline once the gateway returns a conversation id.
   - Tool batch/result bookkeeping is in timeline metadata/reducer code, not ViewModel maps.
   - The legacy `clientModeMessages` assistant/tool path and old `ClientModeStreamReducer` are gone; only a quarantined fresh-route USER bootstrap echo remains before a real conversation id exists.

3. **The app ↔ bot ↔ timeline contract is now explicit**
   - Client Mode timeline lifecycle: [`client-mode-timeline-rules.md`](architecture/client-mode-timeline-rules.md)
   - Cross-boundary regression matrix: [`chat-boundary-regression-harness.md`](architecture/chat-boundary-regression-harness.md)
   - These documents identify the canonical tests for route/session ownership, delta-stream semantics, terminal frames, and local→confirmed timeline folding.

4. **The UI component vs screen split already exists**
   - Shared components mostly live in `designsystem/ui/components/`.
   - Screen flows live in `app/ui/screens/...`.
   - This recommendation from the earlier review is effectively complete.

5. **There are useful contract seams**
   - Examples: `LettaRuntimeClient`, `ClientModeChatSender`, several `I*Repository` interfaces, immutable `Timeline` state objects, and focused timeline/bot/app regression coverage.

## What Is Still Accurate From the Older Review

1. **`util/` still needs to be watched**
   - It is not obviously a dumping ground yet, but utilities still accumulate in `core/util/`.

2. **`data/mapper/` is still present**
   - The layer leakage risk is lower than before because the mapper footprint is small, but the concern still exists.

3. **A clearer domain-model boundary would still help**
   - `domain/` exists, but most model types still live in `data/model/`, so upstream API shape can still ripple outward more than ideal.

4. **Large-file pressure remains real**
   - `AdminChatViewModel.kt` is still far too large even after collaborator extraction.
   - Several UI screens remain above the preferred size threshold and should be decomposed as they are touched.

## What Is Stale In the Older Review

1. **The top-level structure is outdated**
   - The codebase is no longer best summarized as one package tree under `com.letta.mobile/`.

2. **The review under-described the real UI architecture**
   - Shared UI is not primarily in `app/ui/common/`; it is centered in `designsystem/`.

3. **The review omitted the `bot/` module entirely**
   - That is now a first-class architectural subsystem, especially for Client Mode and gateway-backed chat.

4. **The warning about paging being the main duplication hotspot is no longer the best framing**
   - The highest-risk bug surface was the seam between route state, gateway/session selection, stream chunk semantics, and timeline reconcile. That seam now has explicit docs and regression tripwires.

## Current Risk Concentration

The architecture is no longer bottlenecked by broad layering problems or undocumented chat-boundary contracts. The current risk is concentrated in **oversized orchestrators and screens**:

- `AdminChatViewModel` still coordinates route resolution, timeline observation, Client Mode sends, stream lifecycle flags, notifications, composer state, project context, and reset/search gates.
- Several large Compose screens still mix layout, dialogs, state-specific sections, and interaction wiring in one file.
- Some lower-level classes such as `WsBotClient` are still large enough that protocol handling and lifecycle behavior are harder to audit than necessary.

## Completed Chat Hardening Section

The previously recommended chat-focused work section was:

> Define explicit chat contracts across app, bot, and timeline.

That section is now complete:

1. **Conversation ownership and route-agent/session binding** are covered by Client Mode sender and bot lifecycle tests.
2. **Bot stream semantics** are covered by delta and terminal-frame tests in `bot`.
3. **Timeline ingestion/reconcile rules** are documented in `client-mode-timeline-rules.md` and covered by timeline reducer characterization tests.
4. **Cross-boundary regression harness** is documented in `chat-boundary-regression-harness.md`.

## Recommended Next Section Of Work

The next chat-focused workstream should be:

### **Thin `AdminChatViewModel` into an orchestration shell**

The goal is no longer to discover hidden chat contracts; those are now documented. The next step is to move remaining business logic behind tested collaborators so `AdminChatViewModel` primarily composes state and delegates work.

Recommended boundaries:

1. **Client Mode send/session coordinator**
   - Own `sendMessageViaClientMode`, pre-conversation buffering/replay calls, and stream-completion state transitions.
   - Keep the existing `ClientModeChatSender` request-shape contract intact.

2. **Timeline observer/state projector**
   - Own `startTimelineObserver`, hydrate signal handling, older-message prefix merge, and streaming/typing flag derivation.
   - Keep `TimelineEventToUiMessage` as the pure projection boundary.

3. **Run/composer gate coordinator**
   - Own duplicate initial-message delivery guard, active-run steering, interrupt/stop behavior, and composer effects.

4. **Project context coordinator**
   - Further reduce direct VM ownership of project brief, bug report, folder/activity, and location-prompt flows.

Success criteria for the first pass should be pragmatic:

- `AdminChatViewModel.kt` trends substantially downward and has clear section boundaries.
- No behavior change in chat open, send, approval, stream, search, or project chat.
- The canonical chat-boundary test matrix remains green after each extraction.

## Deferred Context

The agent-admin surface has real bugs and deserves attention, especially around model switching and save consistency. That is important, but it should be treated as a separate workstream. The current recommendation is to finish thinning the chat orchestrator first, then shift to agent-admin consistency once chat ownership is easier to reason about.

## Overall

The architecture is in better shape than the older review suggests. The biggest remaining challenge is no longer broad layering or undocumented chat contracts — it is reducing the size and responsibility density of key orchestrators, starting with `AdminChatViewModel`, while preserving the now-explicit app ↔ bot ↔ timeline contracts.
