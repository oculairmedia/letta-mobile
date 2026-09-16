# Mascot motion references (encoded + cited)

**Purpose:** One place Astra / Fable can pull numbers, easings, and patterns from — with the datasource for every claim.
**Ship target:** Rive + SPEC.md (ms + cubic-bezier). Lottie is a library of feel, not a runtime.
**Vision lock:** soft blob + one plate-eye; stable silhouette; sparse mouth; AvatarState keys unchanged.

Last updated: 2026-09-13.

## 0. How to use this doc

| Layer | What to copy into SPEC |
|---|---|
| Easing tokens | Prefer M3 Standard for utility face/plate moves; Emphasized decelerate for flash landings / lean-in |
| Duration tokens | Map our transitions onto Short/Medium/Long/ExtraLong buckets |
| State machine shape | Lottie/dotLottie + VoiceOrbs + our Kotlin arbitration |
| Character micro-timing | Astra draft numbers (validated against M3 buckets below) |
| Continuous | mouthOpen / gaze / blink / hover — amplitude + intervals |

Every subsection ends with Sources.

## 1. Easing library (copy-paste beziers)

### 1.1 Material Design 3 — Standard set (utility / face chrome)

| Token | cubic-bezier(x1, y1, x2, y2) | Use |
|---|---|---|
| Standard | 0.2, 0, 0, 1 | On-screen state changes (glyph morph, plate settle) |
| Standard decelerate | 0, 0, 0, 1 | Enter / lean-in / eye widen |
| Standard accelerate | 0.3, 0, 1, 1 | Exit / dismiss |
| Linear | 0, 0, 1, 1 | Loops (breathe phase, loading pulse phase drive) |

Sources
- Material Components Android Motion theming (authoritative token values): https://github.com/material-components/material-components-android/blob/master/docs/theming/Motion.md
- Applying easing & duration (guidance): https://m3.material.io/styles/motion/easing-and-duration/applying-easing-and-duration

### 1.2 Material Design 3 — Emphasized set (expressive)

| Token | Value | Use |
|---|---|---|
| Emphasized | path (approx cubic 0.2, 0, 0, 1 for simple tools) — full path: `M 0,0 C 0.05,0 0.133333,0.06 0.166666,0.4 C 0.208333,0.82 0.25,1 1,1` | Begin+end on screen, expressive |
| Emphasized decelerate | 0.05, 0.7, 0.1, 1 | Soft landings (success hop land, sleep settle) |
| Emphasized accelerate | 0.3, 0, 0.8, 0.15 | Quick exits / flash anticipation out |

Sources: same M3 / MCC links as §1.1.

### 1.3 Sensible M3 pairings (defaults)

| Transition type | Easing | Duration (M3 default pairing) |
|---|---|---|
| Begin & end on screen (emphasized) | Emphasized | 500 ms |
| Enter screen | Emphasized decelerate | 400 ms |
| Exit screen | Emphasized accelerate | 200 ms |
| Begin & end on screen (standard/utility) | Standard | 300 ms |
| Enter (standard) | Standard decelerate | 250 ms |
| Exit (standard) | Standard accelerate | 200 ms |

Source: https://m3.material.io/styles/motion/easing-and-duration/applying-easing-and-duration

### 1.4 M3 duration token ladder

| Token | ms |
|---|---|
| Short1…4 | 50 / 100 / 150 / 200 |
| Medium1…4 | 250 / 300 / 350 / 400 |
| Long1…4 | 450 / 500 / 550 / 600 |
| ExtraLong1…4 | 700 / 800 / 900 / 1000 |

Rule: duration ↑ with travel distance / area.

Source: https://github.com/material-components/material-components-android/blob/master/docs/theming/Motion.md

### 1.5 M3 springs (optional — drag release / hop land)

| Attr | damping | stiffness | Use |
|---|---|---|---|
| Fast spatial | 0.9 | 1400 | Small (plate wiggle, eye) |
| Fast effects | 1.0 | 3800 | Color/opacity (no overshoot) |
| Default spatial | 0.9 | 700 | Mid travel |
| Default effects | 1.0 | 1600 | Mid effects |
| Slow spatial | 0.9 | 300 | Large |
| Slow effects | 1.0 | 800 | Large effects |

Source: same Motion.md (§ Springs).

### 1.6 Apple HIG / SwiftUI (interruptibility + springs)

Principles (encode as product rules, not ms):
- Motion is purposeful, brief, precise, interruptible — don't block input waiting for anim.
- Don't use motion as the only status channel (pair with chrome/text).
- Respect Reduce Motion: prefer fades over large translates; tighten bounce.
- Avoid sustained oscillation near ~0.2 Hz (discomfort).

SwiftUI spring default API: `spring(duration: 0.5, bounce: 0.0)` — perceptual duration ≈ settle pace; bounce 0 = critically damped.

Sources
- https://developer.apple.com/design/human-interface-guidelines/motion
- https://developer.apple.com/documentation/swiftui/animation/spring(duration:bounce:blendduration:)
- WWDC23 springs: https://developer.apple.com/videos/play/wwdc2023/10158/

## 2. State-machine patterns (Lottie / product)

### 2.1 dotLottie FSM model (map → Rive SM)

Parts: Inputs (numeric/string/bool/events) → Interactions → States (one live) → Transitions (guards).
Special events: onComplete, pointer enter/exit/down/up/move, string events from app.

Loading pattern (frame segments from Lottie docs example):

| State | Segment (frames @ typical 60fps ≈) | Loop | Trigger in |
|---|---|---|---|
| idle | [0, 1] hold | no | — |
| loading | [0, 60] ≈ 1.0 s cycle | yes, speed 1.5 | StartLoad |
| success | [60, 90] ≈ 0.5 s | no → onComplete → idle | LoadSuccess |
| error | [90, 120] ≈ 0.5 s | no | LoadError → Retry → idle |

Multi-step loading variant in same doc: success [90,120], error [120,150].

Character pattern: idle/walk/run loop; jump/attack one-shots return via onComplete.

Best practices from docs (encode):
- Keep state count focused (< ~10 preferred for maintainability — we already have a richer enum; keep visual SM lean by letting Kotlin arbitrate).
- Descriptive event names; stop SM on unmount.
- Don't post events every frame (throttle); continuous signals ≠ state spam.
- Theme/color bind separate from SM (maps to our color identity bind).

Sources
- https://docs.lottiefiles.com/en/format/dotlottie/interactivity
- https://docs.lottiefiles.com/en/format/dotlottie/interactivity/state-machines
- https://docs.lottiefiles.com/en/format/dotlottie/interactivity/state-machines/specification

### 2.2 VoiceOrbs 7-state contract (AI presence)

Shared contract across orb implementations: `idle | connecting | listening | thinking | speaking | error | disabled`, plus audio-reactive `levelRef` (0..1 amplitude every frame without re-render).

| VoiceOrbs | Our AvatarState / signals |
|---|---|
| idle | idle |
| connecting | ≈ loading (lifecycle) |
| listening | listening |
| thinking | thinking |
| speaking | speaking + mouthOpen ← level |
| error | error (+ flash) |
| disabled | ≈ sleeping / degraded chrome |

Sources
- https://github.com/amunozdev/voiceorbs
- Gallery: https://voiceorbs.vercel.app

### 2.3 Orb vs avatar motion vocabulary (industry)

Typical orb behaviors called out in AI+Rive guidance: idle soft pulse; listening expand glow; thinking rotational / particle / inward motion; speaking waveform / bounce reactive to audio.

Source: https://dev.to/uianimation/how-to-design-an-ai-assistant-ui-using-rive-orbs-avatars-42ci

Product refs (feel, not specs):
- ChatGPT voice blob pulse/morph/shrink: https://60fps.design/shots/chatgpt-voice-blob-gradient-interaction
- Gemini directional thinking motion: https://design.google/library/gemini-ai-visual-design
- Avatar pattern taxonomy: https://www.shapeof.ai/patterns/avatar
- Brilliant Koji (mouthless speech via amplitude binding): https://rive.app/blog/brilliant-builds-its-math-and-coding-tutor-character-with-rive
- Rive AI Orb Mascot (idle/typing/success/error data-bound): https://rive.app/community/files/28088-53050-ai-orb-mascot/
- Rive ViewModel pattern (lookX/Y, isTalking, emotion): https://dev.to/mascotengine/rive-data-binding-and-viewmodels-for-interactive-characters-kcl

### 2.4 LottieFiles browse hubs (steal timing by scrubbing)

| Need | Hub / example |
|---|---|
| Voice / listening | https://lottiefiles.com/free-animations/voice · https://lottiefiles.com/free-animation/voice-assistant-ai-chatbot-TU0uS5jXMP |
| Thinking | https://lottiefiles.com/free-animation/robot-think-dDsY6lDNzq |
| Success flashes | https://lottiefiles.com/free-animations/success · https://lottiefiles.com/free-animation/check-success-Orf3xxrkiV |
| Error | https://lottiefiles.com/free-animations/error · https://lottiefiles.com/free-animation/error-g5zfsy4ORW |
| Talking character pack | https://lottiefiles.com/marketplace/talking-character |
| Audio→progress technique | https://lottiefiles.com/blog/working-with-lottie-animations/how-to-animate-lottie-in-response-to-audio |
| Mascot bots | https://lottiefiles.com/free-animation/gb-mascot-bot-GtLUj3m5Qt |

When scrubbing: note anticipation, overshoot, settle, loop period, whether silhouette morphs. Encode into SPEC; do not vendor Lottie JSON into the Rive pipeline.

## 3. Encoded defaults for OUR mascot SPEC

Values below = recommended SPEC seeds: Astra draft × M3 buckets × industry patterns. Adjust only with a cite.

### 3.1 Global

| Item | Value | Why / source |
|---|---|---|
| Fallback cross-blend | 160 ms + Standard 0.2, 0, 0, 1 | Existing AvatarDirector contract (vocab); ≈ M3 Short4 |
| Max root travel | 14 artboard units (256 space) | Astra B spec |
| Max plate rotation | ±10° | Astra B |
| Identity colour | body + halo bind; plate #F2F3F2; eye #111111 | Astra + Lottie theming separation |
| Reduce Motion | crossfade glyphs / skip hop&shake travel; keep state chrome | Apple HIG |

### 3.2 Continuous signals

| Signal | Spec | Source |
|---|---|---|
| lookX | glyph ±7 u at ±1 | Astra |
| lookY | glyph ±5 u at ±1 (−1 up / +1 down — verify adapter) | Astra |
| Plate gaze lag | optional smaller plate shift (≤ half glyph) | industry "eyes lead head" |
| mouthOpen 0→1 | attack τ 45 ms, release τ 90 ms (exp smooth toward target) | Astra; mirrors audio envelope practice |
| Mouth geometry (Seed speaking) | width 8+4a, height 14a, opacity smoothstep(0,0.08,a) | Astra A |
| Dragged o-mouth | height 6+8a, width 8+4a | Astra |
| blink | close 55 / hold 25 / open 90 = 170 ms total | Astra; ≈ M3 Short3–4 |
| Auto-blink interval | 2.5–4.5 s random | Astra + common character idle |
| hovered | ±2° plate, 240 ms, once on enter | Astra; ≈ Short4 + Standard |
| Speech pulse throttle | do not post state events per frame; drive mouthOpen only | Lottie perf guidance |

### 3.3 Idle life (three independent layers)

| Layer | Spec | Source |
|---|---|---|
| Breathing | period 4.6 s; highlight opacity ±0.025; root y ±1; linear/sine phase | Astra; period ≈ 0.22 Hz — keep amplitude tiny (Apple warns ~0.2 Hz) |
| Blink | see §3.2 | Astra |
| Glance/tilt | every 4–7 s; move 250 / hold 400–900 / return 300 ms | Astra; return ≈ M3 Medium2 |
| Sleeping breathe | period 6.8 s; suppress blink/glance | Astra |
| Loading pulse | period 1.4 s; highlight ±0.035 | Astra |

### 3.4 Sustained pose deltas (motion, not silhouette)

| Key | Motion / plate | Easing seed | Source |
|---|---|---|---|
| idle | neutral plate | — | vocab |
| listening | plate y −3; eye enlarge; 40 ms anticipation inside 200 ms total | Standard decelerate 0,0,0,1 | Astra + M3 enter pairing (shortened) |
| thinking | plate −6°; gaze (−0.35,−0.35); sway ±2° / 3.2 s | Standard | Astra |
| waitingInput | widen 160 ms; bounce root y ±2 / period 1.2 s | Standard | Astra |
| speaking | mouth envelope; optional plate nod ≤2° @ ~1.6 Hz × envelope | Linear on amp | Astra B/C + VoiceOrbs level |
| error | plate y +3 / −5°; frown | Standard | Astra |
| sleeping | 600 ms lid+dim | Emphasized decelerate | Astra + M3 |
| loading / failed / degraded | chrome minimal; no big travel | Linear pulse only | vocab + Lottie loading pattern |

### 3.5 Designed transitions (fill SPEC table)

| from → to | duration_ms | cubic-bezier | Notes | Bucket |
|---|---|---|---|---|
| idle → listening | 200 | 0, 0, 0, 1 | 40 ms anticipation then lean | Short4 + std decelerate |
| listening → thinking | 300 | 0.2, 0, 0, 1 | knit to dash / up-side | Medium2 + standard |
| thinking → speaking | 200 | 0.2, 0, 0, 1 | eyes center, mouth wakes | Short4 |
| speaking → idle | 240 | 0.2, 0, 0, 1 | envelope release + 1u exhale | ≈ Medium1 |
| any → waitingInput | 160 | 0, 0, 0, 1 | then hold bounce loop | Short4 |
| error → idle | 300 | 0.2, 0, 0, 1 | recover | Medium2 |
| any → sleeping | 600 | 0.05, 0.7, 0.1, 1 | slow lid — not a blink | Long4 + emph decelerate |
| sleeping → idle | 600 | 0.05, 0.7, 0.1, 1 | slow open | Long4 |
| dragged enter | 80 | 0.2, 0, 0, 1 | snap | Short2 |
| dragged exit | 350 | spring preferred (fast spatial) or 0.05, 0.7, 0.1, 1 | one overshoot settle | Medium3 |
| unlisted pairs | 160 | 0.2, 0, 0, 1 | director default | Short4 |

Sources: Astra variant specs (`/workspace/mascot-lock/astra-delivery/mascot-directions/variant-*-spec.md`) + M3 tokens §1.

### 3.6 Flashes (momentary)

**success — 800 ms total (ExtraLong2)**

| Phase | t (ms) | Action |
|---|---|---|
| Anticipation | 0–80 | dip y 2 |
| Rise | 80–300 | hop y −10 |
| Descent | 300–560 | fall |
| Settle | 560–800 | land + settle |

Glyph: upper crescent (not lower/sleep arc). Mouth: none. Return: currently live sustained (not stale snapshot). Easing: rise Standard accelerate-ish; land Emphasized decelerate 0.05, 0.7, 0.1, 1. Prefer root translate over squash (silhouette lock).

Sources: Astra flash timing; M3 ExtraLong2=800; Lottie success one-shots typically 0.5–1.0 s (scrub hubs §2.4).

**error flash — 600 ms → sustained error (Long4)**

| Phase | t (ms) | Action |
|---|---|---|
| Dip | 0–100 | y +5 |
| Shake | 100–400 | two diminishing plate shakes ±7° |
| Settle | 400–600 | into sustained error pose |

Easing: shake near-linear segments; settle Standard.
Sources: Astra; Lottie error/shake corpus §2.4; M3 Long4=600.

### 3.7 Arbitration (already in Kotlin — motion must obey)

`dragged > error > waitingInput > speaking > success > thinking > listening > idle`; sleeping suppresses all but dragged + error.

Source: AvatarState.kt + `/workspace/mascot-lock/AVATAR-VOCABULARY.md`

## 4. Accessibility / comfort checklist

| Rule | Encode | Source |
|---|---|---|
| Don't rely on motion alone for status | Host text/chrome for waitingInput/error/failed | Apple HIG + Shape of AI |
| Reduce Motion | Swap hop/shake for opacity/glyph crossfade | Apple HIG accessibility |
| Avoid large FOV motion | Keep travel caps | Apple HIG |
| ~0.2 Hz breathe | Keep amplitude ≤1–2 px / tiny opacity | Apple HIG (~0.2 Hz warning) |
| Interruptible | Flashes cancelable; don't block input | Apple HIG |
| Contrast | Plate stays light; eye dark; failed desat keeps plate legible | Astra + WCAG theme notes in Lottie theming guide |

## 5. Datasource index (canonical)

| ID | Datasource | URL / path |
|---|---|---|
| M3-apply | Material 3 applying easing & duration | https://m3.material.io/styles/motion/easing-and-duration/applying-easing-and-duration |
| MCC-motion | Material Components Android Motion.md (beziers, durations, springs) | https://github.com/material-components/material-components-android/blob/master/docs/theming/Motion.md |
| Apple-HIG-motion | Apple HIG Motion | https://developer.apple.com/design/human-interface-guidelines/motion |
| Apple-spring | SwiftUI spring(duration:bounce:) | https://developer.apple.com/documentation/swiftui/animation/spring(duration:bounce:blendduration:) |
| Lottie-interact | dotLottie interactivity + SM examples | https://docs.lottiefiles.com/en/format/dotlottie/interactivity |
| Lottie-SM | State machines | https://docs.lottiefiles.com/en/format/dotlottie/interactivity/state-machines |
| Lottie-SM-spec | SM specification | https://docs.lottiefiles.com/en/format/dotlottie/interactivity/state-machines/specification |
| Lottie-audio | Animate Lottie from audio | https://lottiefiles.com/blog/working-with-lottie-animations/how-to-animate-lottie-in-response-to-audio |
| Lottie-hubs | success/error/voice free hubs | see §2.4 |
| VoiceOrbs | 7-state + levelRef | https://github.com/amunozdev/voiceorbs · https://voiceorbs.vercel.app |
| ShapeAI-avatar | Avatar UX patterns | https://www.shapeof.ai/patterns/avatar |
| DEV-rive-orbs | Orb vs avatar + behaviors | https://dev.to/uianimation/how-to-design-an-ai-assistant-ui-using-rive-orbs-avatars-42ci |
| 60fps-chatgpt | ChatGPT voice blob | https://60fps.design/shots/chatgpt-voice-blob-gradient-interaction |
| Gemini-visual | Gemini motion system | https://design.google/library/gemini-ai-visual-design |
| Rive-Koji | Brilliant Koji | https://rive.app/blog/brilliant-builds-its-math-and-coding-tutor-character-with-rive |
| Rive-AI-orb | Community AI orb mascot | https://rive.app/community/files/28088-53050-ai-orb-mascot/ |
| Rive-VM | Data binding / lookX/Y | https://dev.to/mascotengine/rive-data-binding-and-viewmodels-for-interactive-characters-kcl |
| Vocab | Our rig vocabulary | `/workspace/mascot-lock/AVATAR-VOCABULARY.md` (other agent's workspace) |
| Astra-specs | Numeric draft A/B/C | `/workspace/mascot-lock/astra-delivery/mascot-directions/variant-*-spec.md` (other agent's workspace) |
| Implement-brief | Single-vision implement ask | `/workspace/mascot-lock/ASTRA-IMPLEMENT.md` (other agent's workspace) |

## 6. One-page SPEC seed (paste into tables)

- Default easing: Standard cubic-bezier(0.2, 0, 0, 1)
- Enter-ish: cubic-bezier(0, 0, 0, 1)
- Soft land: cubic-bezier(0.05, 0.7, 0.1, 1)
- Fallback transition: 160 ms + Standard
- Success: 800 ms keyframed hop
- Error flash: 600 ms dip+shake → sustained error
- Blink: 170 ms; auto 2.5–4.5 s
- Breathe: 4.6 s @ tiny amplitude
- Gaze: ±7 / ±5
- mouthOpen: τ_attack 45 ms / τ_release 90 ms

If a number isn't in a datasource above, don't invent — mark TBD and measure from a named Lottie scrub or Rive community file.
