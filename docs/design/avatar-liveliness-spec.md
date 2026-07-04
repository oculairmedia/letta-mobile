# Spec — Avatar Passive Liveliness Pack (Mate-Engine behavior study, clean-room)

**Status:** Draft for review · **Date:** 2026-07-04
**Companion to:** `docs/design/avatar-system-prd.md` (the primitives contract; read §4 P2/P6, §6 state matrix, §8 [NEW-*] vocab first)
**Scope:** Passive / ambient "liveliness" only — the behaviors that make an idle avatar read as *alive* rather than a frozen model. No new agent-intent tooling, no expression/emotion policy already covered by the director.

---

## 1. Provenance + license note (READ THIS FIRST)

This spec is a **clean-room functional design**. Its behavior inventory (§2) is drawn from studying **Mate-Engine** (`https://github.com/shinyflvre/Mate-Engine`) and the broader Desktop Mate / Bongo Cat / Codex-pet genre — via public README, wiki manual, DeepWiki, release notes, Steam listings and reviews — to understand *what* ambient behaviors these apps have and *why users like them*.

**Mate-Engine is AGPL-v3 (dual-licensed AGPL + a proprietary "MateProv2" license), Unity/C#.** Its default avatar is separately "All Rights Reserved."

**Hard constraint for every implementer of this spec:**

- **Do NOT copy, translate, transliterate, or closely paraphrase Mate-Engine source code**, component names, class structures, or asset files. AGPL is copyleft; porting their code would virally license our tree.
- We take **behavior-level inspiration only**: the feature exists, roughly how it feels, roughly what cadence users tolerate. All parameters in §4 are **our own proposals**, chosen to fit our architecture and perf budget — not lifted values.
- Reading their source to understand a behavior is fine; producing code that derives from it is not. Treat this document as the *only* permitted bridge between their work and ours: everything downstream must trace to this clean-room description, not to their repo.
- Component/behavior names in this spec (`weight-shift`, `pickup-float`, `window-perch`, …) are **ours**, already seeded in PRD §8 [NEW-BEHAVIOR]. Any resemblance to Mate-Engine identifiers is coincidental and must stay that way.

Where a Mate-Engine number is cited below (e.g. "≈200 MB RAM bar", "10 idle animations", "hold ≥1 s to sit"), it is quoted as a **market data point for calibration**, not a value to reproduce in code.

---

## 2. Mate-Engine behavior inventory

Every *passive / ambient* liveliness behavior observed, described functionally. "Cadence" and "feel" are as-observed or as-inferred; treat as calibration, not spec.

| Behavior | Trigger | Feel (what it looks like) | Cadence / duration | Anti-annoyance guard (theirs, inferred) |
|---|---|---|---|---|
| **Idle animation variety** | Avatar stationary, no other state | Full-body loops (breathe, look about, small shifts). ~10 distinct idle clips cycled | Each idle plays a while, then cross-fades to a random other; both dwell-time and transition length are user sliders | Randomized so it never feels like a 2-frame loop; slow dwell so motion is background, not attention-grabbing |
| **Blinking** | Continuous, all states | Natural eyelid blinks | Irregular human cadence | Irregular timing avoids metronome feel |
| **Head / eye / spine cursor tracking** | Cursor moves; active in Idle, HoverReaction, Drag, Dance | Head + spine turn to face the cursor via IK; eyes track too | Continuous, blend-weighted | Yaw ~45° / pitch ~30° clamp; separate head/spine "blend" sliders (0 = off, preserves idle animation; 100 = full follow, degrades idle) so the user dials intensity |
| **Drag / pickup reaction** | User click-drags the avatar body | Character lifts and "gently floats," a surprised/`>_<` face; smoother than competitors; spring-bone hair/cloth swings freely | Duration of drag; settle on release | Float is soft (no jerk); designed to feel like lifting a light plush, not a rigid asset |
| **Window / taskbar sitting ("perch")** | Drag avatar onto a window top edge or taskbar; **hold ≥1 s** | Avatar "sits" on the edge, legs dangle, hair/legs layer correctly over the window; 3 sit poses | Persists until moved; snap zone ~100×10 px | Hold-to-sit debounce prevents accidental sits during fast moves — the single most-loved genre feature (Desktop Mate) done deliberately |
| **Taskbar relax / sleepy sit** | Dragged to taskbar | Lays down / relaxes, sleepy half-lidded eyes still tracking cursor | Persistent | Turns "parked at edge" into a charming pose, not a dead model |
| **Head-pat / touch reaction** | **Circular or back-and-forth mouse motion over the head region** | Eyes close, sparkle particles, pat sound; seated variant differs | Per-interaction, short | Requires deliberate gesture (not a single click) → won't fire from incidental cursor passes |
| **Sensitive-region reaction** | Hover/interact over model-defined regions | `>_<` expression | Per-interaction | Region-gated; opt-in-ish per model |
| **Sleep / inactivity** | Extended inactivity | Explicit Sleep state; calmer/resting | Long threshold | Quiets the avatar during genuine away time |
| **Walking** | Idle only; experimental toggle | Avatar wanders left/right on the desktop on its own | Occasional; suppressed the instant any non-idle state begins | Idle-gated + off by default (experimental) so it never wanders mid-interaction |
| **Dance / audio reactivity** | Audio detected from whitelisted apps above a volume threshold (default ~0.2) | Full dance clips, shuffle/loop, smooth in/out of idle | While audio present | Threshold slider + per-app whitelist so silence ≠ twitching; multi-instance sync <0.5 s desync |
| **Size / scale** | Slider or scroll-wheel over avatar | Whole avatar scales | User-set, persistent | User owns size; no auto-resize surprises |
| **Always-on-top / fullscreen** | Global | Stays above normal windows | — | May yield to fullscreen apps (acknowledged limitation) |
| **Performance envelope** | Global | Lightweight; FPS cap 15–120+, graphics-quality dropdown, optional AO | — | ≈200 MB RAM for a hi-res VRM is the stated bar; scales with texture size — the genre's perf-credibility benchmark |

**Genre sentiment (Desktop Mate reviews, for calibration):**
- **Loved:** characters *perching on window tops / taskbar*, playing with the cursor, head-pats; "a little goober just chilling," praised specifically as **"not too distracting."** Perch is the flagship delight.
- **Hated:** ~500 MB RAM / ~50 % GPU (the review-bomb that set the ~200 MB bar); paywalled characters (a licensing gripe, not a liveliness one). Perf is a *product* requirement, not a nice-to-have.

**Design lesson distilled:** liveliness reads as *alive* when it is (a) **randomized** so it never loops visibly, (b) **slow / low-amplitude** so it lives in the background, (c) **cursor-aware** so it feels responsive, and (d) **cheap** so it never taxes the machine. It reads as *annoying* when it is periodic, loud, attention-stealing, or expensive.

---

## 3. Gap analysis — what we already have vs. the genre

Grounded in the current tree (worktree `letta-mobile-avatar-core`, branch state at the P4 spike + built-in gesture set):

| Genre behavior | Our current state | Gap |
|---|---|---|
| Rest pose (arms-down from T-pose) | ✅ `avatar-renderer.js` `REST_POSE` + `applyRestAndBreathing` — humanoid-only, additive, suspended while a clip drives | none — this is our liveliness floor |
| Breathing sway | ✅ deterministic sine, `BREATH_PERIOD=4s`, chest/shoulder/head, `Math.random`-free | none |
| Blinking | ✅ `AvatarDirector.tickBlink`, seeded `Random`, 2.5–6.5 s interval, 0.09 s close | none — already irregular + deterministic |
| Idle animation **variety** (glance/stretch/fidget cycling) | ❌ IDLE is rest+breath+blink only; no sub-behaviors, no cadence | **primary gap** — §4(b) |
| Micro weight-shift | ❌ | gap — §4(a); PRD §6 IDLE row already calls for it (`weight-shift ~8s`) |
| Idle head drift (look-about when no gaze target) | ⚠️ renderer leaves `lookAt.target` unset → three-vrm idle gaze only; no authored drift | gap — §4(a) |
| Cursor / head tracking | ⚠️ mechanism exists (`setLookTarget`, screen-space unproject) but **no ambient policy** wires the pointer to it | gap — §4(c), opt-in |
| Drag / pickup reaction | ⚠️ `PetWindowSpike` moves the window on drag, but the avatar has **no** pickup-float / surprised / settle | gap — §4(c) |
| Window / taskbar perch | ❌ no snapping, no sit pose | gap — §4(c); PRD B1 lists 12 px magnet + `window-perch` |
| Sleep / inactivity → SLEEPING | ⚠️ SLEEPING is a defined director state (PRD §6) but **not implemented**, and no inactivity timer drives it | gap — §4(c) |
| Gesture clips (wave/nod/shrug/celebrate) | ✅ procedural, rest-anchored, fade-safe, playable by id | reuse as idle-variety building blocks (§4b) |
| Imported animation ids (VRMA/Mixamo) | ⚠️ `playAnimation(id, loop)` + `animationIds` capability contract exists; `feat/avatar-animation-import` had **not** landed imported clips at time of writing | idle variety must degrade gracefully when absent (§4b) |
| Touch / head-pat regions | ❌ deferred — PRD [NEW-PROTOCOL] `touch.regions` not yet built | §4(d) deferred |
| Dance / audio reactivity | ❌ explicit PRD non-goal this phase | §4(d) deferred |
| Perf guardrails (FPS cap, occlusion pause) | ⚠️ PRD §7 acceptance criteria; 30 fps tick exists in `PetWindowSpike`; occlusion/fullscreen pause not built | out of scope here; each behavior below must respect the budget |

**Architecture we must honor (non-negotiable):**
- **Mechanism lives in the JS renderer** (`avatar-renderer.js`), **policy lives in the Kotlin director** (`AvatarDirector.kt`). The renderer must contain **no randomness and no timers of its own** for behavior — it applies state deterministically per `update(delta)`. All *when*/*which*/*random* decisions come from the director.
- **Determinism:** all randomness flows from the director's injected seeded `Random`; all timing from `tick(delta)` deltas. This is already the contract (see `AvatarDirector` header) and every new behavior must preserve it so tests can drive it with a fixed seed + fixed steps.
- **Capability-gated:** every behavior branches on `AvatarCapabilities`, never on model type. Non-humanoid GLB mascots get the `mascot-motion-set` fallback, never humanoid-bone poses.
- **P6 guardrails are enforced in core**, not by etiquette: attention max 1×/task, quiet-hours suppression, opt-in cursor-follow/click-through, zero focus steal.

---

## 4. Spec — our passive liveliness pack, layered

Four layers matching the architecture. Each behavior gives: **trigger/cadence** (our proposal), **primitive** it uses, **determinism** note, **P6 guard**.

Two new director states are implied by PRD §6 and used below: **DRAGGED** and **SLEEPING** (both already in the PRD's 12-state director and priority order; the current `AvatarActivity` enum has only 5 states and must be extended — see §5). Nothing here needs a *new* wire command beyond what §4(c)/(d) flag as [NEW-PROTOCOL].

### 4(a) — Renderer baseline mechanics (always-on, mechanism-only)

These extend the existing rest-pose/breathing floor. They are **inert, deterministic, parameter-driven mechanisms** the renderer runs every frame while no clip drives the skeleton — exactly like breathing today. **The director owns their targets/enable; the renderer owns the curve.** They must be *pure functions of accumulated time + director-supplied targets*, with **no `Math.random`**.

Rule: baseline mechanics never make a *decision* (that's policy). Breathing is allowed to be always-on because it's continuous and choice-free. Anything that picks a direction or a moment is director-driven (§4b).

| Behavior | Mechanism (renderer) | Director input | Determinism | P6 guard |
|---|---|---|---|---|
| **micro weight-shift** [NEW-BEHAVIOR weight-shift] | A slow additive hip/spine lean toward a **target lean value** the director sets (e.g. `setBodyLean(x)` in [-1..1], driving a small `hips`/`spine` Z-rotation ≤ ~3–4°, plus a faint counter-tilt in the head). Renderer eases current→target with a fixed rate; zero target = centered. | Director picks a new small lean target on a seeded cadence (see below), renderer just eases to it | Renderer pure (ease to supplied target); *choice* of target is seeded in director | Amplitude hard-capped in renderer (can't be cranked into a sway); suspended while a clip drives, like breathing |
| **idle head drift** [NEW-BEHAVIOR, folds into weight-shift] | When no host gaze target is active, aim `lookAt` at a **director-supplied drift point** that eases between soft off-center points, instead of three-vrm's default idle gaze. Small radius (a few degrees of yaw/pitch). | Director supplies the next drift point on a seeded cadence; renderer eases | Renderer pure; director seeds the point sequence | Only active when `lookTargetActive === false`; a real host gaze (cursor, speaker) instantly wins |

**Proposed parameters (director-side, our numbers):**
- weight-shift: new lean target every **6–10 s** (seeded uniform), magnitude **0.3–1.0** of the ≤4° cap, ease rate ~**0.6 /s**. Matches PRD §6 IDLE "weight-shift ~8s".
- head drift: new drift point every **4–7 s** (seeded), within ~**±6° yaw / ±4° pitch**, ease rate ~**0.5 /s**.

**Renderer mechanism additions (mechanism-only):** two new host commands — `setBodyLean(x, y)` and (reuse) the existing `setLookTarget` for drift points, OR a dedicated `setIdleGaze(x,y)` so drift is clearly distinct from host gaze. Both apply as additive offsets on top of `applyRestAndBreathing`, suspended while `clipDriving()`. **No timers, no RNG in the renderer.**

### 4(b) — Director-driven idle variety (IDLE sub-behaviors)

The primary gap. While `activity == IDLE` and the avatar isn't otherwise busy, the director occasionally fires a **short, low-key idle action**, then returns to the rest/breath baseline. This is the "10 idle animations cycling" idea, done as **policy over our clip primitive** rather than a baked animation set.

**Idle action vocabulary (our seed set):**

| Idle action | What it is | Primitive | Fallback when no clip |
|---|---|---|---|
| **glance-around** | Head/eyes sweep to a point and back | idle head-drift with a larger, quicker excursion (§4a mechanism) — needs **no clip** | always available |
| **stretch** | Small arm/shoulder stretch + settle | `playGesture` of a new procedural clip `idle-stretch` (rest-anchored like wave/shrug) **or** imported clip id if present | fall back to a bigger weight-shift |
| **fidget** | Weight-shift + tiny head tilt, quick | pure §4a mechanism, larger amplitude for ~1 s | always available |
| **look-about** | Slow head drift to 2–3 points | §4a head-drift, extended | always available |
| *(imported)* | Any VRMA/Mixamo idle clip exposed via `animationIds` | `playAnimation(id, loop=false)` | skip if id absent |

**Cadence (our proposal):** after entering IDLE, schedule the next idle action at a **seeded interval of 12–24 s**. On fire, pick an action by **seeded weighted choice** (favor the cheap no-clip ones: glance/fidget ~70 %, stretch/imported ~30 %). Each action runs **1–2.5 s**, then the interval reschedules. Reset the timer on any state change out of IDLE.

**Graceful degradation:** the director inspects `capabilities.animationIds` (already surfaced in `avatarLoaded`) and `capabilities.supportsHumanoid`. If an imported/stretch clip id is absent → substitute a no-clip action. Non-humanoid GLB → only glance/look-about via `lookAt` (if `supportsLookAt`) else the `mascot-motion-set` bob/lean (see §5). **An avatar with zero capabilities still blinks + breathes** (already true).

**Determinism:** action selection, interval, and target points **all** draw from the director's seeded `Random`; timing from `tick(delta)`. A seeded test with fixed steps must reproduce the exact idle-action sequence. **The renderer contributes no randomness** — it only plays the clip id / eases to the point the director names.

**P6 guard:** idle variety is **ambient, not attention-seeking**, so it is *not* rate-limited by the 1×/task attention budget — BUT it must **fully suspend under SLEEPING and quiet hours** (avatar holds sleep-pose, §4c), and must never fire while any higher-priority state (LISTENING/THINKING/SPEAKING/…) is active. Amplitudes stay low (this is background motion). If the pet window is occluded/off-display, the render loop is paused anyway (PRD §7) so idle actions cost nothing.

### 4(c) — Pet-window behaviors needing OS integration

These require the desktop pet host (`PetWindowSpike.kt` and successors), not just the renderer. Policy still lives in the director; the **OS glue** (window position, hit-testing, snapping) lives in the pet host, which feeds *events* to the director and consumes its *state*.

#### pickup-float [NEW-BEHAVIOR pickup-float] — drag/pickup reaction
- **Trigger:** pet host detects drag-begin on the body (it already moves the window via `WindowDraggableArea`). It calls `director.setActivity(DRAGGED)` on drag-start and back to the prior state on drag-end.
- **What plays:** on DRAGGED enter — a brief **surprised** expression flash (~0.2 wt, per PRD §6), and a looping/`held` **pickup-float** pose (subtle upward body offset + relaxed limbs). **Spring bones swing free naturally** (already always-on in three-vrm — no work needed; the swing *is* the physics feel). On drag-end → **settle bounce** (~0.4 s) then return to prior state.
- **Primitive:** expression flash (`flashEmotion(Surprised, 0.2)` — needs a `Surprised` expression added to `AvatarExpression`), plus a procedural `pickup-float` clip **or** a director-set body offset (a `setBodyLean`-style vertical nudge). Settle bounce = a short procedural clip or damped offset.
- **Determinism:** DRAGGED is event-driven (drag start/stop), not random — fully deterministic. The float pose itself is a fixed clip/offset. No RNG.
- **P6 guard:** DRAGGED is **top priority** (PRD §6 priority: `DRAGGED > ERROR > …`), so it can interrupt anything — that's correct, the user is physically manipulating the avatar. No attention budget applies (user-initiated). Must not steal focus (host already sets `WS_EX_NOACTIVATE`).

#### window-perch / taskbar-sit [NEW-BEHAVIOR window-perch] — the flagship delight
- **Trigger:** on drag-end, the pet host tests proximity to snap targets (window top edges, screen corners, taskbar top) within a **12 px magnet** (PRD B1). To sit, require the drag to have **dwelled ≥ ~0.8 s near the edge** (our debounce, mirroring the genre's hold-to-sit intent) so fast moves don't trigger accidental perches. On snap, host emits a `perched(edge)` event.
- **What plays:** director enters a **perch pose** — a seated/dangling clip (`idle-perch`, procedural or imported) held while perched; idle variety continues at reduced amplitude; optional sleepy-eye look at cursor if cursor-gaze is enabled. Un-snap (drag away) exits the pose.
- **Primitive:** a `perch` procedural clip or imported id via `playAnimation(loop=true)`; if absent → fall back to a static lean + reduced idle variety (still reads as "resting there").
- **Determinism:** snap decision is geometric (host), pose is a fixed clip — deterministic. Idle variety during perch stays seeded.
- **P6 guard:** never covers/steals focus from the perched-on window; window stays click-through-friendly and non-activating. Perch is **opt-in-friendly** but low-risk (it's where the user dropped the avatar). Position/size remembered per monitor (PRD B1).
- **OS note:** requires querying other windows' top edges + taskbar rect. Keep it **own-window-only, no injection** (PRD §7 anti-cheat safe): perch snaps to *geometry*, we do not attach to or read into foreign processes beyond public window-rect enumeration.

#### cursor-gaze (opt-in) — ambient head/cursor tracking
- **Trigger:** enabled only when the user turns on "follow cursor" (PRD B1 menu; **off by default**, P6). While on, the pet host feeds the global cursor position (screen-space, normalized to the pet window) to `director.setLookTarget(screen-space target)` continuously; the renderer already unprojects screen→world and re-projects on reframe.
- **What it looks like:** head + eyes (+ faint spine via the head bone) track the cursor within the model's natural limits; **overrides idle head-drift** while active (idle drift only runs when no host target — already the renderer contract).
- **Primitive:** existing `setLookTarget` / screen-space path. No new mechanism — just a policy that pipes cursor → look target when opted in.
- **Determinism:** pass-through of a real input; no RNG. Idle drift resumes deterministically when cursor-gaze is off.
- **P6 guard:** **opt-in, off by default** (hard P6 rule). Suppressed under SLEEPING. Rate isn't an issue (continuous, low-cost), but we should throttle updates to the render tick (30 fps) — the pet host already ticks at 33 ms, so feed the look target on that beat, not per raw mouse event.

#### inactivity → SLEEPING [NEW-BEHAVIOR sleep-pose]
- **Trigger:** two independent paths, whichever comes first — (1) **quiet hours** (PRD P6, user-configured window) → SLEEPING; (2) **user inactivity**: no agent activity AND no user input for a threshold. **Our proposed idle-to-sleep threshold: ~90 s of continuous IDLE with no input.** (Distinct from quiet-hours, which is time-of-day.) The pet host owns the inactivity timer / OS idle query and quiet-hours clock; it calls `director.setActivity(SLEEPING)`.
- **What plays:** **sleep-pose** (eyes closed, relaxed/slumped or curled pose), `ring off`, all attention suppressed; chip shows ⏾ (PRD §6). Blink stops (eyes stay closed). Idle variety **suspended**. Wake on any agent activity or (if not quiet-hours) user interaction → smooth return to IDLE.
- **Primitive:** eyes-closed via the blink/`blink` expression held at 1.0 (or a dedicated closed-eye expression), plus a `sleep-pose` procedural/imported clip held. Fallback if no clip: eyes closed + a deeper static lean + slowed breathing (drop `BREATH_PERIOD` to ~6 s — a director-set breathing-rate multiplier, applied in renderer as a mechanism).
- **Determinism:** SLEEPING entry is timer/clock-driven (deterministic given the clock); sleep-pose is fixed. Breathing stays deterministic (just a slower period). No RNG.
- **P6 guard:** SLEEPING is exactly where P6 mandates **all attention suppressed** and quiet-hours behavior — the avatar **plays sleep-pose, does NOT hide** (PRD §6 / P6). SLEEPING gates everything except DRAGGED/ERROR (PRD priority), so a real error or a physical pickup still wakes/overrides it.

### 4(d) — Explicitly deferred (design intent noted, not built this phase)

| Deferred behavior | Why deferred | When it lands |
|---|---|---|
| **Touch / head-pat / poke reactions** | Needs [NEW-PROTOCOL] `touch.regions`: per-model region map + a `touch(region)` event out of the renderer (PRD P1/§8). Not yet built. | Ties to `touch.regions`. When it lands: head-pat → happy 0.5 wt 1.5 s (PRD row); body → poke-response. Trigger should require a **deliberate gesture** (circular/back-and-forth over the head region), not a single click, to avoid incidental firing — a genre lesson worth copying at the *policy* level. Determinism: the *event* is user input; the *reaction* is a seeded-free fixed response. P6: reactions are user-initiated so no attention budget, but must respect SLEEPING (a sleeping pet stirs, doesn't fully perform). |
| **Dance / audio reactivity** | Explicit PRD non-goal this phase ("dance-to-music" in §2 non-goals). Also raises perf + audio-capture + per-app-whitelist complexity, and the whole-body motion is the *opposite* of "ambient/background." | Future. If ever built: gate on an explicit user toggle + volume threshold + app whitelist (genre pattern), idle-only, seeded clip shuffle via the director. |

---

## 5. Implementation slicing — ordered small PRs

Each PR is small, independently reviewable, and respects **policy-in-director / mechanism-in-renderer**. File targets are relative to `android-compose/` in our tree. Renderer = `avatar/renderer-web/frontend/avatar-renderer.js`; director = `avatar/core/src/commonMain/kotlin/com/letta/mobile/avatar/core/AvatarDirector.kt`; pet host = `desktop/src/main/kotlin/com/letta/mobile/desktop/avatar/pet/`.

**PR 0 — Extend the director state model (prereq).**
The current `AvatarActivity` enum has 5 states; the PRD director is 12. This pack needs at least **DRAGGED** and **SLEEPING** (and a `Surprised` expression).
- `avatar/core/.../AvatarDirector.kt`: add `DRAGGED`, `SLEEPING` to `AvatarActivity`; wire base-expression + priority handling; add `Surprised` to `AvatarExpression` (`AvatarCommands.kt`).
- Tests: state-transition + priority (DRAGGED/SLEEPING override) in `AvatarDirectorTest`.
- No renderer change. Pure policy.

**PR 1 — Renderer baseline mechanics: body-lean + idle-gaze offsets (mechanism-only).**
- `avatar-renderer.js`: add `setBodyLean(x,y)` and `setIdleGaze(x,y)` commands; apply as additive offsets in `applyRestAndBreathing`, suspended while `clipDriving()`; amplitude-clamped; **no RNG, no timers**. Add a director-set `breathRateMultiplier` for the sleep slowdown.
- `avatar/renderer-web/.../AvatarWireProtocol.kt` + `WebAvatarRuntime.kt` + `AvatarRuntime.kt`: add the pass-through methods.
- Renderer test: offsets apply/clamp/suspend correctly (headless or the existing web runtime test).

**PR 2 — Director-driven micro weight-shift + idle head-drift (§4a policy).**
- `AvatarDirector.kt`: seeded cadence picks lean + drift targets while IDLE; ease off on state exit; feed via the PR 1 commands. Config values (6–10 s / 4–7 s, amplitudes) in `Config`.
- Tests: seeded `Random` + fixed steps reproduces the target sequence; nothing fires outside IDLE; head-drift yields to a host look target.
- Delivers the PRD §6 IDLE "weight-shift ~8s" contract.

**PR 3 — Idle variety scheduler (§4b).**
- `AvatarDirector.kt`: idle-action scheduler (12–24 s seeded interval, weighted seeded pick), dispatching glance/fidget (no-clip), stretch (`idle-stretch` gesture), and imported ids from `capabilities.animationIds` with graceful fallback.
- `avatar-renderer.js`: add the `idle-stretch` procedural clip to `buildStandardGestureClips` (rest-anchored, like wave/shrug).
- Tests: seeded sequence reproducibility; degradation when `animationIds` empty and when non-humanoid; suspends under non-IDLE / SLEEPING.

**PR 4 — pickup-float on drag (§4c).**
- `desktop/.../pet/PetWindowSpike.kt` (+ successors): detect drag-begin/end from the existing `WindowDraggableArea`, call `setActivity(DRAGGED)` / restore; emit drag events.
- `AvatarDirector.kt`: DRAGGED enter → `Surprised` flash + pickup-float offset/clip; exit → settle-bounce.
- `avatar-renderer.js`: `pickup-float` + `settle-bounce` procedural clips (or reuse `setBodyLean` vertical offset).
- Tests: DRAGGED priority interrupt; deterministic (event-driven).

**PR 5 — inactivity + quiet-hours → SLEEPING / sleep-pose (§4c).**
- `desktop/.../pet/`: inactivity timer (OS idle query) + quiet-hours clock → `setActivity(SLEEPING)` / wake.
- `AvatarDirector.kt`: SLEEPING → eyes-closed hold, suspend blink + idle variety, slow breathing (via PR 1 multiplier); wake transition.
- `avatar-renderer.js`: `sleep-pose` procedural clip (fallback path).
- Tests: SLEEPING suppresses idle variety + attention; DRAGGED/ERROR still override (PRD priority).

**PR 6 — window-perch / taskbar-sit (§4c) — the flagship.**
- `desktop/.../pet/`: snap detection (window-top / corner / taskbar rects, 12 px magnet, ≥0.8 s dwell debounce), per-monitor position memory, `perched(edge)` event. Own-window-only window-rect enumeration (no injection).
- `AvatarDirector.kt`: perch pose enter/exit; reduced-amplitude idle variety while perched.
- `avatar-renderer.js`: `idle-perch` procedural clip (fallback = static lean).
- Tests: snap geometry + dwell debounce; no focus steal (extends existing pet focus test).

**PR 7 — cursor-gaze opt-in (§4c).**
- `desktop/.../pet/`: menu toggle (off by default), feed throttled cursor screen-pos → `setLookTarget` on the 33 ms tick; suppress under SLEEPING.
- No renderer change (screen-space path exists). Director just relays.
- Tests: off-by-default; suppressed under SLEEPING; idle drift resumes when off.

**Deferred (separate future initiatives, not in this pack):** touch.regions + head-pat/poke (§4d), dance/audio (§4d). File nothing now beyond a tracking issue.

**Ordering rationale:** PR 0 unblocks everything (states). PRs 1–3 are pure in-renderer+director "always-alive" wins with no OS work — they make *every* surface (bust/dock/pet) livelier and are the cheapest, safest first slices. PRs 4–7 add OS-integrated pet delight in rising integration cost, ending on the highest-value/highest-effort **window-perch**. Every PR keeps the renderer RNG-free and the director seeded, so the whole pack stays deterministic and testable.

---

## 6. Determinism & guardrail checklist (apply to every PR)

- [ ] No `Math.random` / no self-scheduled timers in `avatar-renderer.js` — mechanism only.
- [ ] All randomness via the director's injected `Random`; all timing via `tick(delta)`.
- [ ] Behavior branches on `AvatarCapabilities`, never on model type; non-humanoid path exists.
- [ ] Graceful fallback when an animation id is absent (no hard dependency on imported clips).
- [ ] Suspended correctly under SLEEPING / quiet hours (avatar poses, never hides).
- [ ] Respects PRD §6 priority order (DRAGGED/ERROR override SLEEPING; higher states preempt IDLE variety).
- [ ] Attention behaviors (none here except future touch) obey the 1×/task budget; ambient behaviors are low-amplitude and pause under occlusion.
- [ ] Pet window: zero focus steal, no clicks captured while unhovered, no process injection (window-rect enumeration only).
- [ ] Amplitudes clamped in the renderer so a bad director value can't produce a violent sway.
- [ ] Perf: everything pauses when the render loop pauses (occlusion/off-display); idle variety amplitudes stay small; targets the ≈200 MB / capped-FPS budget (PRD §7).
