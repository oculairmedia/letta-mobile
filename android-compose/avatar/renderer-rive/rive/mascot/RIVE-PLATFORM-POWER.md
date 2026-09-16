# Rive platform power — implementor brief (mascot)

**Audience:** Fable / whoever owns `scene.rml` → `.riv` for the letta-mobile mascot
**Goal:** Use the full modern Rive stack, not just "timelines + a few inputs."
**Contract SoT:** `RiveAvatarContract.kt` + `ARTIST.md` (View Model `Avatar` on artboard `Mascot`, SM `Avatar`)
**Docs index:** https://rive.app/docs/llms.txt

> Delivered by the design agent (pasted by the user, committed verbatim in structure). The
> implementation audit against this brief is in `README.md` ("Platform audit").

## TL;DR — what "full power" means for us

| Prefer | Avoid / deprecate |
|---|---|
| View Model properties (numbers, bools, triggers, enums, colors, nested VMs) | Legacy State Machine Inputs as the app API |
| Listen to VM property changes (incl. triggers) for app ↔ file feedback | Runtime Rive Event listeners as the primary channel |
| One state machine, multiple layers for parallel concerns | Multiple top-level SMs fighting one controller |
| Blend states + Joysticks for continuous look/mouth | Discrete timeline hops for every lookX sample |
| Solos for mutually exclusive glyphs / body variants | Opacity-fading N copies of the same glyph |
| Converters (range map, interpolator, formula) between app units and art | Hardcoding remap math only in Kotlin |
| Components (nested artboards) with their own VM / stateful data | One giant flat artboard that can't be reused |
| Listeners for pointer/drag → VM writes | App-only drag if the file can own hit targets |
| Keep constraints for pure spatial links | Replacing constraints with VM when object↔object is enough |

Rive's own migration guide: data binding replaces SM inputs + runtime event listening for new
work. Legacy still runs; we should not build new surface area on it.
https://rive.app/docs/editor/data-binding/migration-guide

## 1. What we already do well (keep)

From `ARTIST.md` / `RiveAvatarContract`:

- Sustained mood as one enum `state` (`AvatarState`) — mutually exclusive; not N booleans.
- Momentary flashes as triggers (`success`, `error`, `blink`) — file owns duration/return.
- Continuous channels: `mouthOpen`, `lookX`, `lookY` as numbers.
- Identity: `color` as Color property (bound to fills), `shape` as enum.
- Held: `dragged` bool; file listeners can write it too.
- Layered SM already sketched: Expression / Flash / Drag / IdleVariety / Breath / Blink / Hover.
- Nested Plate component with local `expr` + scrub timelines `LookX` / `LookY` / `Open`.
- CLI agent loop: `rive . --verify`, `rive inspect`, schema via `rive schema`.

This is already data-binding-native, not 2022-style inputs. Naming in Kotlin still says
`INPUT_*` — treat those as VM property paths.

## 2. Feature map → mascot leverage

### A. Data binding / View Models (core)

Docs: https://rive.app/docs/editor/data-binding/overview · property types · view-models · controlling-data

| Type | Mascot use |
|---|---|
| Enum | `state`, `shape` (done) |
| Color | `color` (done); optional secondary tint enums via converter |
| Number | look / mouth / future energy, volume, hover intensity |
| Boolean | `dragged`, `hovered` |
| Trigger | `success` / `error` / `blink` (+ optional `nudge`, `tapAck`) |
| View Model (nested) | face / body / fx sub-instances so Plate doesn't share root paths awkwardly |
| List / Artboard / Image | later: sticker overlays, alternate plates, runtime swap — not v1 unless needed |

Patterns:

- **Artboard-attached instance** — default for `Mascot` (app binds one VMI).
- **Nested VM properties** — parent holds `face: FaceVM` so Plate binds `face/expr` without path soup.
- **Stateful components** — Plate keeps local state (blink cooldown) without parent creating the instance.
- **Global instances** — only if multiple artboards must share one color/theme.

Android Compose (app side, for when runtime catches up): `rememberViewModelInstance` +
`setNumber` / `setBoolean` / `setEnum` / `setColor` / `fireTrigger` + `get*Flow` for reverse sync.
https://rive.app/docs/runtimes/android/data-binding

Pitfall: On some Compose builds, `fireTrigger` alone may not dirty the render loop when the SM is
settled — pair with a known-good dirty path or keep runtime current (see rive-android #384).

### B. State machine — layers, blends, transitions

Docs: state-machine · states · layers · transitions

Layers (parallel): one controller advances one SM; put parallel concerns on layers, not separate
SMs. (Community confirmation: multi-SM in one file needs one combined SM or multiple widgets.)

Suggested layer ownership (align with `ARTIST.md`):

| Layer | Driven by | Owns |
|---|---|---|
| Expression | `state` enum | body morph, tint, glyph index, base motion |
| Flash | `success` / `error` triggers | self-returning hops; does not rewrite silhouette permanently |
| Drag | `dragged` | squash / lift; highest arbitration with Expression |
| Gaze | `lookX`/`lookY` or joystick | eye plate offset (prefer blend/joystick over discrete states) |
| Mouth | `mouthOpen` | open amount via 1D blend |
| Breath / IdleVariety | internal random / time | micro-motion; never fights Drag |
| Blink | `blink` + internal | eye close |
| Hover | `hovered` | glow / scale tick |

Blend states (underused gold):

- **1D blend** — mix timelines with one number (`mouthOpen` 0→1, loading progress).
- **Additive / Direct blend** — mix independent pose timelines (`lookX` and `lookY` without
  baking a 2D grid of keys).

Use blend states so AvatarDirector can stream continuous floats every frame without Rive hopping
discrete "look left / look right" animations.

Transitions:

- Conditions on VM properties (enum equals, number thresholds, trigger fire). Multiple conditions = AND.
- Transition / state actions: set properties, fire scripts — e.g. on Flash end, ensure `state`
  settled for error path.
- Transition condition scripts when arbitration needs custom logic (dragged > error > …). Prefer
  keeping arbitration in AvatarDirector when possible; use scripts only for art-local rules.

### C. Listeners (editor-side intelligence)

Docs: https://rive.app/docs/editor/state-machine/listeners

Listen to: pointer enter/exit/move/down/up/click, VM property change, Rive events (legacy).

- Pointer → write `hovered` / `dragged` (already intended).
- Prefer VM property change listeners over nested event plumbing across Plate.
- Listener actions can set other VM props (e.g. click fires `nudge` trigger).
- Strong recommendation from Rive: data bind between artboards, don't rely on nested Rive events.

### D. Joysticks (gaze / expression rigs)

Docs: https://rive.app/docs/editor/manipulating-shapes/joysticks

- Assign `LookX` / `LookY` timelines to joystick axes; scrub instead of keyframing every gaze
  sample on the SM.
- Handle source can follow a target (cursor proxy).
- Conflict rule: don't animate the same property on both axis timelines.
- Can nest joysticks (joystick drives joystick) for eyelids vs pupil.

vs Blend states: Joystick is authoring UX for pose space; blend states are SM-native mixing.
Either is fine; for app-driven `lookX`/`lookY`, 1D blends or additive blends bound to those
numbers is the cleanest runtime story. Joysticks shine when artists author extreme poses once.

### E. Solos

Docs: https://rive.app/docs/editor/manipulating-shapes/solos

- Only one child renders — cheaper than opacity stacks.
- Glyph solo (idle crescent / sleep arc / error mark / waiting dots) keyed by `expr` or `state`.
- Shape solo if/when multiple body silhouettes ship without morphing everything.
- Skin / angle variants later without duplicating full hierarchies.

### F. Components (nested artboards)

Docs: https://rive.app/docs/editor/fundamentals/components

- Mark exportable artboards as components.
- Instance modes: Leaf / Layout (responsive) — Layout matters if mascot sits in reflowing UI chrome.
- Libraries: publish Plate once, reuse in chat header / settings / onboarding.
- Bind nested VM or expose stateful component props in sidebar.

Mascot split that scales:

```
Mascot (root)
├── Body component     ← shape × color, breath, drag squash
├── Plate component    ← single eye + glyph solo + look/mouth blends
└── Halo / FX          ← optional, color-bound
```

### G. Constraints vs data binding

Docs: constraints-overview · migration-guide "Constraints vs Data Binding"

| Use constraints | Use data binding |
|---|---|
| Eye follows a local aim target object | App writes `lookX`/`lookY` |
| Halo locked to body transform | Identity color shared by N fills |
| Pure spatial copy/limit | Anything needing converters / runtime / multi-consumer |

Don't force VM for "child sticks to parent."

### H. Converters (remap without Kotlin)

Docs: https://rive.app/docs/editor/data-binding/converters

| Converter | Mascot idea |
|---|---|
| Range Map | speech RMS dB → `mouthOpen` 0..1; or look px → -1..1 |
| Numeric Interpolator | smooth app snaps (reduce Kotlin lerps) |
| Color Interpolator | soft tint shifts on state without hard cuts |
| Formula | `breathScale = 1 + 0.02*sin(...)` style micro-motion from time/input |
| Toggle | invert hover for "pressed" look |
| Converter Scripts | custom when built-ins aren't enough |
| Groups | chain range-map → interpolator |

Keep AvatarDirector authoritative for arbitration; use converters for units and smoothing.

### I. Scripting (Luau in-editor)

Docs: https://rive.app/docs/scripting/getting-started · protocols (node, converter,
transition-condition, listener-action) · data-binding · tests

Worth it when:

- IdleVariety random glance timing lives in-file (testable with Test scripts).
- Transition conditions need multi-property rules the graph can't express cleanly.
- Custom path effects / shaders (WGSL) for premium glow — only if runtime feature-support
  allows (feathering needs Rive Renderer).

Avoid: putting AvatarDirector policy (who wins between speaking vs dragged) only in scripts —
that belongs in shared Kotlin so both platforms stay in sync.

### J. Audio

Docs: audio assets · audio events · runtime playing-audio

Koji-style volume → mouth can stay app amplitude → `mouthOpen`. Optional: in-file audio events
for UI ticks on success/error. Don't embed agent speech audio in the `.riv`.

### K. Accessibility / reduced motion

Docs: semantics · reduced-motion

- Tag interactive hit targets if the mascot is tappable.
- Honor reduced motion: shorter blends, disable IdleVariety/Breath via a VM bool `reduceMotion`
  (app sets from system setting). Add to contract when we implement.

### L. CLI / agent workflow (already on path)

Docs: https://rive.app/docs/cli/overview · agents · RML

- `rive docs` / `rive schema` / `rive . --verify` / `rive inspect . --json`
- After editor becomes SoT: no blind `rive push` over artist work (`ARTIST.md` rule).
- SVG→RML converter should emit bindable structure (named fills for color, solos for glyphs,
  VM property declarations matching contract keys).

## 3. Capability checklist (implementor self-audit)

Mark each when the `.riv` / generator actually uses it intentionally.

**Must (v1 identity + states)**

- [ ] All app-facing names are View Model properties on `Avatar` (not legacy SM inputs)
- [ ] `state` enum keys match `RiveAvatarContract.stateKey` exactly (order-independent keys)
- [ ] `success` / `error` / `blink` are triggers; Flash layer self-returns
- [ ] `color` Color property bound to body + halo fills (no hardcoded hex for identity)
- [ ] `shape` enum wired (solo or morph); unused values don't break
- [ ] `mouthOpen`, `lookX`, `lookY` drive blend states or joystick-scrubbed timelines, not only "nearest keyframe" hops
- [ ] `dragged` / `hovered` updated by listeners and/or app
- [ ] One SM `Avatar` with layers for Expression / Flash / Drag / continuous channels
- [ ] Glyphs in a Solo (or equivalent one-active child), not N opacity layers
- [ ] `check_contract.py` passes against shipped `.riv`
- [ ] Vector feathering / soft glow only if target Android/desktop runtime + renderer support it (feathering → Rive Renderer)

**Should (full platform leverage)**

- [ ] Plate (and ideally Body) as Components with clear VM boundary
- [ ] Nested VM or stateful component for face so path strings stay stable
- [ ] Converters for mouth/look smoothing or range mapping
- [ ] IdleVariety / random glances via formula or scripts + Test scripts
- [ ] Transition actions for flash→sustained error settle if not 100 % app-timed
- [ ] Reduced-motion bool (contract + layer gates)
- [ ] `rive inspect --json` asserts: property types, layer names, solo children, bound color targets

**Later / don't block ship**

- [ ] List / Artboard properties for interchangeable plates
- [ ] Libraries publish of Plate
- [ ] WGSL / GPU Canvas effects
- [ ] In-riv UI sound events
- [ ] Layout mode for chrome-embedded mascot

## 4. Gaps vs "full power" (honest)

| Area | Status | Gap |
|---|---|---|
| Data binding types | Strong | Nested VMs / stateful Plate not required yet |
| SM layers | Planned in ARTIST | Ensure generator emits real parallel layers, not one mega-graph |
| Blend / joystick | Timelines named LookX/Y/Open | Confirm they are blends/joystick-driven, not discrete state hops |
| Solos | Glyph language implies solo | Verify Solo nodes in inspect JSON |
| Converters | Unused? | Easy win for mouth/look feel without Kotlin churn |
| Scripting / tests | Unused? | IdleVariety + contract tests in-file |
| Events | Prefer avoid | Don't add runtime event API for new signals |
| Multi-SM | Avoid | Keep one `Avatar` SM |
| Runtime | Android + desktop bridge | Stay on current Rive Android; verify Compose trigger dirty behavior; desktop spike must speak same VM API |

## 5. Recommended build order (after SVG→RML)

1. Emit VM schema matching contract (enums + color + numbers + triggers + bools).
2. Body path + color bindings + halo.
3. Plate component + glyph Solo + `expr` mapping from `state`.
4. Expression layer (enum transitions) — no silhouette mutation for identity.
5. Flash layer (triggers).
6. 1D blends for mouth + look (or joystick authoring → same number binds).
7. Drag + pointer listeners.
8. Breath / IdleVariety / Blink.
9. Converters for smoothing; optional `reduceMotion`.
10. verify + inspect + `check_contract.py` gate.

## 6. Canonical links (bookmark)

- Docs index: https://rive.app/docs/llms.txt
- Data binding overview / migration / converters / enums / lists
- State machine: states (incl. blends), layers, transitions, listeners
- Joysticks, Solos, Components, Constraints
- Scripting + Test scripts
- Android data binding: https://rive.app/docs/runtimes/android/data-binding
- Feature support: https://rive.app/docs/feature-support
- CLI agents: https://rive.app/docs/cli/agents
- RML: https://rive.app/docs/runtimes/advanced-topic/rml

## 7. One sentence for Fable

Drive everything through the `Avatar` View Model; parallelize with SM layers; continuous face
channels through blends (or joysticks); exclusive glyphs through Solos; remap with converters;
keep AvatarDirector policy in Kotlin; treat editor listeners + nested components as first-class —
not optional polish.
