# letta-mobile Architecture Review

Updated review of the current letta-mobile architecture after the timeline migration and recent client-mode stabilization work.

## Current Structure

The project is now best understood as a **module-based architecture**, not a single package tree.

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
   - `app` owns screen state and navigation
   - `core` owns message/timeline persistence and sync
   - `bot` owns gateway/session/runtime transport behavior
   - `designsystem` owns reusable Compose UI

2. **`stream/` and `timeline/` were the right places to elevate**
   - The recent regressions confirmed this: the hard bugs now cluster around stream completion, timeline reconcile, and client-mode session ownership.

3. **The UI component vs screen split already exists**
   - Shared components mostly live in `designsystem/ui/components/`
   - Screen flows live in `app/ui/screens/...`
   - This recommendation from the earlier review is effectively complete.

4. **There are already some useful contract seams**
   - Examples: `LettaRuntimeClient`, several `I*Repository` interfaces, immutable `Timeline` state objects, and strong unit coverage around timeline behavior.

## What Is Still Accurate From the Older Review

1. **`util/` still needs to be watched**
   - It is not obviously a dumping ground yet, but utilities still accumulate in `core/util/`.

2. **`data/mapper/` is still present**
   - The layer leakage risk is lower than before because the mapper footprint is small, but the concern still exists.

3. **A clearer domain-model boundary would still help**
   - `domain/` exists, but most model types still live in `data/model/`, so upstream API shape can still ripple outward more than ideal.

## What Is Stale In the Older Review

1. **The top-level structure is outdated**
   - The codebase is no longer best summarized as one package tree under `com.letta.mobile/`.

2. **The review under-described the real UI architecture**
   - Shared UI is not primarily in `app/ui/common/`; it is centered in `designsystem/`.

3. **The review omitted the `bot/` module entirely**
   - That is now a first-class architectural subsystem, especially for Client Mode and gateway-backed chat.

4. **The warning about paging being the main duplication hotspot is no longer the best framing**
   - The highest-risk bug surface is now the seam between:
     - route state in `app`
     - gateway/session selection in `bot`
     - message ingestion and reconcile in `core/timeline`

## Current Risk Concentration

The architecture is no longer bottlenecked by broad layering problems. The current risk is concentrated in the **chat contract boundary**:

- `AdminChatViewModel` and route/session state in `app`
- Client Mode gateway/session selection in `app/clientmode` + `bot`
- stream chunk semantics in `bot`
- reconcile and timeline ingestion in `core/data/timeline`

This is the area where recent regressions came from:

- wrong-agent existing-conversation sends
- duplicate terminal assistant content
- fragile assumptions around stream completion and conversation ownership

## Open Architectural Gaps

1. **Timeline/sync boundaries are still more concrete than interface-first**
   - `TimelineRepository` and `TimelineSyncLoop` are still concrete classes
   - tests are good, but the abstraction layer is not yet as explicit as the rest of the repo

2. **Client Mode crosses modules without a single explicit contract document**
   - route agent selection
   - conversation ownership
   - terminal chunk semantics
   - timeline ingestion expectations
   These all exist in code, but not yet as one stable architecture contract.

3. **Domain models remain partly coupled to data models**
   - still worth improving, but lower priority than the live chat boundary issues

## Recommended Next Section Of Work

Now that the message-sync migration is complete, the next chat-focused work section should be:

### **Define explicit chat contracts across app, bot, and timeline**

This should cover, at minimum:

1. **Conversation ownership contract**
   - which agent owns an existing conversation
   - how route agent, gateway agent, and conversation agent must align

2. **Client Mode session contract**
   - when a route reuses a session
   - when it must restart one
   - what inputs are authoritative for selection

3. **Streaming contract**
   - what non-terminal chunks may contain
   - what terminal `done=true` chunks may contain
   - whether completion chunks are metadata-only or content-bearing

4. **Timeline ingestion contract**
   - which layers may append optimistic locals
   - which layers may replace or confirm them
   - how reconcile behaves for user vs assistant vs tool vs reasoning events

This is the natural next step because it turns the completed migration into a stable platform instead of a moving target.

## Deferred Context

The agent-admin surface has real bugs and deserves attention, especially around model switching and save consistency. That is important, but it should be treated as a separate workstream. The current recommendation is to finish hardening the chat boundary first, section by section, before shifting focus.

## Overall

The architecture is in better shape than the older review suggests. The biggest remaining challenge is not broad layering — it is making the **chat boundary contracts explicit** across `app`, `bot`, and `core/timeline` so future chat work stops reintroducing state-machine regressions.
