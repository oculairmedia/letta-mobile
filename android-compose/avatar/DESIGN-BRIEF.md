# Avatar Design Brief — Desktop Surfaces & Pet Mode

Prompt for the design agent. Keep in sync with the renderer/director
capabilities in `:avatar:core` and `:avatar:renderer-web` — every design must
map to a listed capability or carry a `[NEW-*]` tag so engineering can scope it.

---

You are the design agent for Letta Desktop (Kotlin Compose Desktop + Jewel, dark-first UI). Your task: iterate on design implementations for the AI agent's 3D avatar — (A) avatar surfaces inside the desktop app, (B) "pet mode" as a floating desktop companion window, and (C) the Avatar Library. Produce boards in the existing Penpot file, page "App Mockups v2" (page id 9ad9fa5c-d4f1-80b2-8008-321c1ee404a4), following its conventions: desktop boards at 1440x900, animated-state study boards at 390w, board names prefixed "Desktop · Avatar · …".

## Product context

Letta Desktop is a chat client for persistent AI agents. Left edge: a 56dp agent rail (gradient "agent orbs" with a pulsing concentric ThinkingRing while an agent works — amber #E0A33E working, green #34C759 firing, red #E5484D error). Then a sidebar (nav + conversation list), then the chat pane. There is a Work|Play mode switcher; Play mode includes roleplay rooms (multichat) — avatars are strategically central to Play mode. Existing boards to study for language: "Conversation (agent-centric)", "Play mode (Work|Play switcher)", "Roleplay room (multichat)", the ANIMATED-STATE orb studies, "Background tasks" pulse cards.

## What is already built (design within these truths)

The avatar is a real-time 3D VRM character (anime-shaded MToon, VRoid-style; also plain glTF mascots/props for non-humanoids) rendered by a web renderer (three.js + pixiv three-vrm) on a TRANSPARENT canvas — it can composite over any background, including the OS desktop. The app talks to the renderer over a command protocol; today the renderer opens in a browser window (placeholder UX), next iteration embeds it in-app (JCEF) and/or in a frameless OS window. Do not design around screenshots/static images — this is a live, animated character.

Renderer capabilities (commands that exist NOW):

- Expressions with weights: neutral, happy, angry, sad, surprised, relaxed, blink/blinkLeft/blinkRight, lookUp/Down/Left/Right + model-custom ones
- Visemes (aa/ih/ou/ee/oh) and a mouth-open level (lip sync channel)
- Look-at: world-space or screen-space gaze target; detached = natural idle gaze
- Embedded animation clips (play/loop/gesture with fade)
- Accessory toggles (show/hide named parts, e.g. glasses)
- Spring-bone physics (hair/clothes react to motion) — free, always on
- Per-model capability flags: a model may lack expressions/look-at/etc.; UI must degrade gracefully (a GLB mascot has none of the humanoid features)

Behavior layer (the "director") already drives, automatically from agent state:

- IDLE: natural blinking on a randomized schedule
- LISTENING (user talking/composing): attentive, still
- THINKING (agent reasoning/tools): relaxed inward expression
- SPEAKING (reply streaming): procedural mouth chatter (TTS amplitude later)
- ERROR: brief sad flash, then settles

Designers may spec NEW behaviors (gestures, emotes, camera framing changes like bust/full-body/headshot, micro-motions) — these are cheap to add to the protocol/director, so propose freely but tag them `[NEW-BEHAVIOR]` or `[NEW-CAMERA]`.

Current v1 UX to replace: a Face icon at the bottom of the agent rail opens the "Avatar library" (a plain modal listing imported avatars with license lines + Import/Use buttons), and "Use" opens the avatar in a separate default-browser tab. Functional, ugly, disconnected.

## Design tasks — iterate 2–3 directions each

### (A) In-app avatar surfaces

Where does the live avatar live inside the app? Explore at least: (1) a chat-header "bust" — small framed head/shoulders beside the agent name that reacts while you chat; (2) a right-side companion panel/dock (collapsible) with the avatar at waist-up + status; (3) avatar as an upgrade of the rail orb / thinking indicator; (4) Play-mode roleplay room: multiple avatars of different agents in one scene. For each: placement, sizes, framing (headshot/bust/full), how THINKING/SPEAKING/ERROR read at that size, empty/loading/failed states, behavior when the model lacks expressions (fallback to the existing orb language?), and how it coexists with the ThinkingRing color semantics (amber/green/red) so the app has ONE presence language.

### (B) Pet mode — the flagship

A frameless, transparent, always-on-top OS window with just the character standing on the desktop (think desktop mascot done tastefully).

Design: default size + resize behavior; drag affordance (grab the character? a hover halo with a grip?); hover micro-UI (mute/behavior toggle, open-chat button, close); a compact context menu (switch avatar, switch agent, size presets, click-through toggle, "sit on taskbar" alignment); SPEAKING treatment — speech bubble / subtitle chip with streamed text? notification badge when the agent finishes while unfocused; look-at following the cursor (opt-in) vs idle gaze; multi-monitor + edge snapping; how click-through mode is indicated and escaped. Also the degraded variant: non-humanoid mascot (a prop/creature GLB) doing the same job with position/scale animation only.

### (C) Avatar Library

Redesign the modal into a proper surface: card grid with live/rendered thumbnails, format + license facts displayed honestly (license name, "redistribution allowed"/"no redistribution"/"local/private only", creator, source link), import flow with the pipeline's rejection/warning states (unknown license → local-only downgrade message; structural rejection), per-agent avatar assignment (each agent can have its own avatar; show which agent uses which), and first-run empty state pointing at VRoid Studio / Blender authoring. One VRM sample ships pre-imported (MIT, pixiv).

### (D) State matrix + motion spec

One board: rows = the director activities IDLE/LISTENING/THINKING/SPEAKING/ERROR, plus the runtime lifecycle states LOADING/FAILED, plus CAPABILITY-DEGRADED `[NEW-BEHAVIOR]` (a UI condition for models lacking expressions/look-at — not a director state; the design defines what it means per surface). Columns = each surface (header bust, dock panel, pet window, library thumbnail). Specify expression + gesture + camera framing + any UI chrome per cell, with timings (hold/decay durations) — the director consumes exact seconds.

## Constraints

- Dark-first; reuse existing tokens/spacing; the avatar canvas is transparent — design the frame, not the background.
- Everything must map to the capability list above or be tagged `[NEW-BEHAVIOR]`/`[NEW-CAMERA]`/`[NEW-PROTOCOL]` so engineering can size it.
- Never assume a specific character: designs must work for any VRM (tall/short/chibi) and degrade to non-humanoid GLB.
- License display is a hard requirement wherever an avatar is chosen or shown in the library (open-ecosystem principle).
- Pet mode must never steal focus or block clicks unintentionally; attention-seeking is opt-in.

## Deliverables

Penpot boards per task (2–3 directions for A and B, 1 refined for C, 1 matrix for D), a short rationale note per direction, and a redline/spec pass on the direction you recommend. Flag every `[NEW-*]` item in a single summary list at the end so the protocol/director work can be scoped.
