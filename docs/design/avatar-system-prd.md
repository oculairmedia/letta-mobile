# PRD — Avatar System (in-app surfaces, pet mode, library)

**Status:** Ready for implementation · **Date:** 2026-07-03
**Design source:** Penpot file "letta mobile", page **App Mockups v2**, boards `Desktop · Avatar · …` (row y=14000): A1 Header bust, A2 Companion dock, A3 Play stage, B1 Pet mode (hover UI), B1 Redlines, B2 Pet mode (menu + modes), C Library, D State matrix, Rationale + NEW-* scope.
**Audience:** dev agent implementing core primitives. Read this top-to-bottom before writing code; the primitives in §4 are the contract everything else consumes.

---

## 1. Summary

Letta agents get a live 3D presence: a real-time VRM character (anime-shaded MToon via three.js + pixiv three-vrm, plain glTF/GLB for non-humanoid mascots) rendered on a **transparent canvas**, driven by a command protocol. It appears in four surfaces: a chat-header bust, a collapsible companion dock, a Play-mode multi-avatar stage, and — the flagship — **pet mode**, a frameless always-on-top OS window standing on the desktop.

**Architectural stance (decided):** the avatar is *not bolted on*. Core ships only three things — the render surface, the command protocol, and a reflexive "director" baseline. Everything expressive above that is **agent-extensible mod-space**: agent-callable `avatar.*` tools, behaviors-as-data, pet mode itself distributed as a packaged mod. The agent drives and extends its own presence (precedent: Codex pets are literally a `hatch-pet` skill).

## 2. Goals / Non-goals

**Goals**

- One presence language: the existing ThinkingRing semantics (amber `#E0A33E` working, green `#34C759` firing/speaking, red `#E5484D` error) appear identically on rail orbs, bust frames, dock status, stage name chips, and the pet's floor-glow. The avatar never invents a second status system.
- Any-model safety: every surface works for tall/short/chibi VRMs and degrades to non-humanoid GLB mascots via capability flags. No design may assume a specific character.
- Open ecosystem: VRM 1.0 canonical (0.x best-effort), GLB for mascots; authorable from VRoid Studio/Blender; **license facts displayed wherever an avatar is chosen or shown** (structured VRM facets, "declared by file" wording).
- Never annoying: pet never steals focus, never blocks clicks unintentionally; all attention-seeking behavior is opt-in and budget-limited **in core** (not mod etiquette).
- Perf as a product requirement (genre lesson: Desktop Mate is review-bombed for ~500MB/50% GPU; Mate-Engine's bar is ~200MB): FPS caps, occlusion pause, fullscreen-game auto-hide, no process injection.

**Non-goals (this phase)**

- TTS-amplitude lip sync (viseme channel exists; amplitude wiring later)
- Mobile pet overlay, VR, avatar marketplace/Workshop, dance-to-music
- Cloud/Constellation agents driving local avatar events that only fire locally (`llm_*`)

## 3. What already exists (build on, don't rebuild)

- Web renderer (three.js + three-vrm), transparent canvas, opens in a browser window today; next step is embedding (JCEF) and/or frameless OS window.
- Command protocol: expressions with weights (neutral/happy/angry/sad/surprised/relaxed/blink*/look* + model-custom), visemes (aa/ih/ou/ee/oh) + mouth-open level, look-at (world-space or screen-space target; detached = idle gaze), embedded animation clips (play/loop/gesture with fade), accessory toggles, spring-bone physics (always on), per-model capability flags.
- Behavior layer ("director") driving automatically: IDLE randomized blink, THINKING relaxed expression, SPEAKING procedural mouth chatter, ERROR sad flash → settle.
- v1 UX to replace: Face icon in rail → plain "Avatar library" modal → "Use" opens a browser tab.

## 4. Primitives to implement (the contract)

Implement in this order; each is consumed by everything after it.

### P1 — Protocol formalization + versioning

Freeze the current renderer commands as a versioned protocol (`avatar-protocol v1`). Add a handshake that returns renderer version + the model's `AvatarCapabilities` (hasExpressions, hasVisemes, hasLookAt, hasHumanoidRig, customExpressions[], accessories[], touchRegions[]). Every consumer (director, tools, mods, surfaces) must branch on capabilities, never on model type.

New protocol commands, tagged from design:

| Command | Tag | Notes |
|---|---|---|
| `camera.frame(preset, tweenMs)` — `headshot \| bust \| waist \| full` | [NEW-CAMERA] | 400ms default tween; locked headshot crop used by 48/28px busts |
| `camera.distance(preset)` — `S \| M \| L` (240/320/480px pet heights) | [NEW-CAMERA] | pet size = camera distance, not window scale |
| `camera.scene(preset)` — `lineup \| focusSpeaker \| theatre` | [NEW-CAMERA] | Play stage only, phase 4 |
| `scene.load(models[])` + per-model command addressing | [NEW-PROTOCOL] | multi-model scene, phase 4; cap cast size (3 VRM ≈ 150–300MB VRAM) |
| `model.swap(url, crossfadeMs)` | [NEW-PROTOCOL] | in-place avatar switch from pet menu |
| `touch.regions` capability + `touch(region)` event out of renderer | [NEW-PROTOCOL] | per-model map; head-pat → happy 0.5wt 1.5s, body → poke-response |

### P2 — Director state machine (core, reflexive layer)

A single state machine with **12 states**, fed by existing lifecycle events (`turn_start/end`, `tool_start/end`, `llm_start/end`, approval requests, stream events). It emits state enter/exit (observable by mods) and issues protocol commands per the matrix in §6. The renderer owns animation curves; the director owns *what* and *when* (exact seconds in §6).

Priority when states overlap:
`DRAGGED > ERROR > WAITING_INPUT > SPEAKING > SUCCESS > THINKING > LISTENING > IDLE`; `SLEEPING` gates everything except `DRAGGED`/`ERROR`.

### P3 — Presence semantics service (one source of truth)

A tiny core service mapping director state → `{color, mode}`:

- THINKING → amber, **pulse** (1.2s ease)
- WAITING_INPUT → amber, **steady** (Codex running/waiting/ready pattern: pulse = working, steady = waiting on YOU)
- SPEAKING → green; SUCCESS → green flash 0.6s
- ERROR → red
- transitions 250ms

Consumed by: rail orb ThinkingRing (existing), bust frame stroke, dock status card, stage name chips, pet floor-glow, pet status chip dot. No surface computes its own colors.

### P4 — Surface host abstraction

One composable/host component wrapping a renderer instance with the universal lifecycle chrome:

- `LOADING`: dashed frame + shimmer (per-surface variants in §6)
- `FAILED`: collapse to the existing gradient orb language (250ms) + retry affordance; **the orb is always the fallback** — an agent never loses presence
- `DEGRADED`: capability-gated feature hiding (no expressions → bust hidden, gesture chips hidden, mascot motion set)

Two hosts: **JCEF embed** (in-app: bust, dock, library thumbs, stage) and **frameless OS window** (pet: transparent, always-on-top, no-activate/never-steals-focus; Windows layered window vs macOS NSPanel — spec is behavior-level). Prefer one shared renderer process with multiple swapchains if dock + pet run simultaneously.

### P5 — Agent-callable avatar tools (intentional layer)

Registered tools the model can invoke: `avatar.express(expression, weight, holdMs)`, `avatar.gesture(clip)`, `avatar.lookAt(target)`, `avatar.frame(preset)`, `avatar.say(text)` (pet bubble). Conditionally registered only when the agent has an avatar with the needed capability. In Play mode, each agent animates **its own** character via these tools — the stage is agents acting, not the app puppeting.

### P6 — Guardrails in core (hard requirement)

Because agents/mods can author behaviors, the anti-annoyance rules are enforced by a core permission policy on `avatar.*` and attention APIs — not by convention:

- attention behaviors (wave, badge, glow, `avatar.say` while unfocused) fire **max 1× per task completion**, opt-in globally
- quiet hours suppress all attention (pet plays sleep-pose, doesn't hide)
- follow-cursor and click-through are opt-in, off by default
- pet window never activates/steals focus; clicks land on it only while hovered

### P7 — Mod capability surface

`letta.avatar` namespace for mods: subscribe to director state enter/exit, register **behaviors as data** (event → motion rule: `{on: 'SUCCESS', clip: 'celebrate', maxPerTask: 1}`), register gesture clips, drive the pet UI slots (status chip text). Pet mode itself ships as a packaged mod (`npm:@letta/pet-mode`) registering `/pet` command + statusline chip. Behaviors listed as [NEW-BEHAVIOR] in §8 are the **seed vocabulary, not a closed set** — agents write new ones and `/reload`.

### P8 — Diagnostics

Avatar load failures, capability mismatches, and license-pipeline rejections report via `letta.diagnostics.report()` → `~/.letta/mods/diagnostics/latest.json`, so the agent can read and self-repair ("Check my mod diagnostics and fix whatever is wrong").

### P9 — Asset pipeline + license facets

Import validation (glTF-Validator + structural humanoid check) with three outcomes: **accepted**, **accepted-downgraded** (unknown/missing license → `LOCAL / PRIVATE ONLY`, amber), **rejected** (e.g. missing humanoid bones → card with "View report"). Manifest carries **structured VRM license facets** (redistribution / modification / commercial use / credit / characterization) rendered as chips, with "declared by file" wording — VRM meta is self-declared, never claim verification. One sample ships pre-imported (Miko, MIT, pixiv). Per-agent assignment: `agent → avatarId` mapping; the library shows "used by <agent>" with the agent's orb.

## 5. Surfaces (consumers of the primitives)

Ship order: **B1 pet → A2 dock → A1 bust → A3 stage** (same renderer, rising protocol cost).

**B1 · Pet mode (flagship; packaged mod).** Frameless transparent window 380×560 @ M; sizes S 240 / M 320 / L 480 via `camera.distance`. Drag anywhere on body = move window (grip dots as hint); hover reveals halo (`#FFFFFF` 4% fill, 1px 35% stroke, r24) + micro-row [chat · behaviors · menu · close], hover-in 150ms, hover-out 300ms delay + 200ms fade. **Persistent status chip at feet** (Codex pattern): active thread + one-line progress, visible in all states; click = open thread. Speech bubble streams reply text, max-W 300, click-to-open-chat, auto-hide 4s after stream end. Snapping (12px magnet): taskbar-top, screen corners, window edges, **window tops** ([NEW-BEHAVIOR window-perch] — Desktop Mate's most-loved feature). Context menu: open chat, switch agent (retargets pet), switch avatar (`model.swap`), size, sit on taskbar, follow cursor, click-through (ghost pill + global hotkey escape ⌃⌥P), **peek behind 3s** (transient translucency, Bongo Cat pattern), quiet hours, close. Wording: "Wake pet / Tuck away". Multi-monitor: position/size remembered per monitor; on disconnect migrate to primary, same corner.

**A2 · Companion dock.** 320px right panel, collapsible to a 48px edge tab that keeps ring color visible. Waist-up 260×360 stage (smallest framing where expressions + gestures + spring-bones all read), status card (state dot + live tool line + progress), gesture chips (wave/nod/shrug), framing switcher (`camera.frame`). Gaze targets newest message bubble while SPEAKING.

**A1 · Header bust.** 48px r14 frame beside agent name, 2px ring stroke = presence colors; headshot crop locked. Rail orb upgrade: 28px micro-head with the existing concentric pulse outside the frame. Ambient tier — cheap once dock exists.

**A3 · Play stage (phase 4).** 300px scene strip over roleplay chat; one shared canvas, N avatars floor-anchored auto-scaled 150–260px; speaker = green chip ring + streamed bubble; idle cast gazes at speaker; `camera.scene` presets; "Hide" collapses to today's avatar-less room. Per-slot loading/failed states.

**C · Library.** Full surface (not modal): card grid with live thumbs, name/format/creator, license facet chips, per-agent assignment row, "Use ▾" agent picker (license line repeated in picker), import card (drag-drop anywhere), rejection/downgrade cards, first-run = Miko + import card + VRoid Studio/Blender links.

## 6. State matrix (director spec — timings are the contract)

| State | Header bust 48px | Dock 260×360 | Pet 320px | Library thumb |
|---|---|---|---|---|
| IDLE | blink 2–6s rand · ring off | blink + spring sway · gaze detached | blink + weight-shift ~8s [NEW-BEHAVIOR weight-shift] · UI hidden | static pose, blink only |
| LISTENING (user typing) | gaze → composer caret | + lean-in 0.3wt [NEW-BEHAVIOR lean-in] · release 0.8s after typing stops | gaze → cursor only if opt-in · optional typing-react bap [NEW-BEHAVIOR, opt-in] | — |
| DRAGGED | — | — | pickup-float clip [NEW-BEHAVIOR pickup-float] · spring-bones swing free · surprised 0.2wt · drop = settle bounce 0.4s | — |
| THINKING | ring amber pulse 1.2s · relaxed 0.6wt (attack 0.4s) | + status card amber + tool line + progress | amber floor-glow pulse [NEW-BEHAVIOR floor-glow] · chip shows current step | — |
| WAITING_INPUT (tool approval) | ring amber **steady** · surprised 0.3wt | status "needs approval" + Approve/Deny inline | hand-raise [NEW-BEHAVIOR hand-raise] + steady amber + chip "waiting: approve web_fetch?" · click → approval dialog | — |
| SPEAKING | ring green · visemes 60ms attack / 90ms release | + talk-beats ~6s [NEW-BEHAVIOR talk-beats] · gaze → newest bubble | bubble streams · visemes · auto-hide 4.0s after end | — |
| SUCCESS | ring green flash 0.6s → off | happy 0.7wt hold 1.5s + "done ✓" | celebrate 2.0s [NEW-BEHAVIOR celebrate] · chip "ready for review" persists until clicked | — |
| ERROR | ring red · sad 0.8wt hold 1.2s, decay 0.4s | + status red "run failed" + Retry | red floor-glow + sad flash · chip "failed — open log" | — |
| SLEEPING (quiet hours) | ring off · eyes closed | stage dims 60% + "quiet until 15:32" | sleep-pose [NEW-BEHAVIOR sleep-pose] · all attention suppressed · chip ⏾ | — |
| LOADING | frame shimmer 1.5s | dashed stage + shimmer | ghost silhouette 40% + spinner at feet | skeleton shimmer |
| FAILED | → gradient orb 250ms | stage collapses · orb badge + Retry/Library | toast "avatar failed — orb mode" · pet hides | ⚠ + "View report" |
| DEGRADED (no expressions) | bust hidden → orb | idle sway only · gesture chips hidden | mascot set: bob/lean/bounce/shake [NEW-BEHAVIOR mascot-motion-set] | "limited" chip |

Global: ring transitions 250ms · expression cross-fades 300ms unless specified · director emits state enter/exit; renderer owns curves.

## 7. Perf & compat budget (acceptance criteria)

- ≤ 250MB RAM with the sample model; RAM is texture-dominated — warn on import for >2K texture sets
- FPS cap exposed in pet menu; 30fps idle cap default; pause render loop when fully occluded or display off
- auto-hide when a fullscreen game/app is focused; own-window only, **no injection** (anti-cheat safe)
- pet window: zero focus steals (verified by test), zero clicks captured while not hovered

## 8. [NEW-*] vocabulary (seed set — mod-space, agent-authorable)

[NEW-CAMERA] headshot-crop · framing presets (headshot/bust/waist/full, 400ms) · distance presets S/M/L · scene framing (lineup/focus-speaker/theatre)
[NEW-BEHAVIOR] wave/nod/shrug · lean-in · talk-beats · weight-shift · floor-glow · attention-wave · poke-response · hand-raise · celebrate · window-perch · typing-react · pickup-float · sleep-pose · mascot-motion-set
[NEW-PROTOCOL] multi-model scene · model-swap-crossfade · touch-regions

Everything else maps to existing commands (expressions, visemes, look-at, clips, accessories, spring-bones, capability flags).

## 9. Milestones

- **M0 — Primitives:** P1–P4 + P6 + P8 (protocol v1 + handshake, director w/ 12 states, presence service, surface hosts, guardrails, diagnostics). Exit: state matrix demo scene passes; orb fallback proven.
- **M1 — Pet (flagship):** frameless window host, B1 interactions + status chip + bubble, B2 menu + modes, packaged as `npm:@letta/pet-mode`. Exit: perf budget met; focus/click tests green.
- **M2 — Dock:** JCEF embed, A2 panel + collapse tab; P5 agent tools land here (gesture chips call them).
- **M3 — Bust + Library:** A1 header/rail crops; P9 pipeline + C surface (facet chips, assignment, rejection states).
- **M4 — Play stage:** multi-model scene, per-model addressing, scene framing.

## 10. Risks / open questions

- JCEF perf with two live canvases (dock + pet) → shared renderer process, two swapchains; measure in M2
- OS transparency/always-on-top differences (Windows layered vs macOS NSPanel) — keep spec behavior-level
- VRM meta is self-declared → always "declared by file", never "verified"
- Guardrail boundary: `avatar.*` tool rate/attention budgets live in core permission policy — mods cannot lift them

## 11. Research basis

Codex Pets ([settings docs](https://developers.openai.com/codex/app/settings)): persistent overlay w/ thread + progress line, running/waiting/ready distinction, Wake/Tuck-away, pets-as-skill. Desktop Mate ([Steam](https://store.steampowered.com/app/3301060/Desktop_Mate/)): window-perching loved, 500MB/50% GPU hated. [Mate-Engine](https://github.com/shinyflvre/Mate-Engine) (OSS, AGPL): genre table-stakes checklist, ~200MB bar, exists because Desktop Mate paywalled models — validates open-VRM stance. Bongo Cat ([PC Gamer](https://www.pcgamer.com/games/life-sim/one-of-the-biggest-games-on-steam-right-now-is-bongo-cat-a-cat-with-a-hat-who-smacks-your-windows-taskbar-like-a-bongo-drum-when-you-type/)): transient translucency, taskbar-native. [VRoid Hub license model](https://vroid.pixiv.help/hc/en-us/articles/360016417013): structured facets. Grok Ani: director-triggered gestures validated; desktop companions are open ground.
