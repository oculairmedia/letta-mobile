# :avatar:core

Renderer-independent avatar runtime core (commonMain only). Portable avatar policy depends on this contract module. Platform hosts also
depend on `:avatar:renderer-rive` to bind rendering (see `app/build.gradle.kts`);
renderer internals stay at those platform edges.

## Design rules

- **One asset, one contract**: the mascot is a Rive file (`AvatarFormat.RIVE`);
  the core never reads it, it drives it through `AvatarRuntime`.
- **Renderer abstraction second**: everything a renderer needs flows through
  [`AvatarRuntime`](src/commonMain/kotlin/com/letta/mobile/avatar/core/AvatarRuntime.kt)
  and the normalized manifest — renderer internals never leak upward.
- **Platform optimization third.**

## What lives here

| Piece | Purpose |
|---|---|
| `AvatarRuntime` + `AvatarRuntimeState` | The control surface renderers implement and the app consumes. |
| `AvatarModel`, `AvatarCapabilities` | The asset handed to a runtime and what it can do. |
| `AvatarExpression`, `AvatarLookTarget`, `AvatarGesture` | Normalized command vocabulary the director speaks. |
| `AvatarDirector`, `AvatarState`, `PresenceSemantics` | Arbitrates IDLE / LISTENING / THINKING / SPEAKING / WAITING_INPUT / SUCCESS / ERROR / SLEEPING from app signals. |
| `GazeDirector`, `GazePlan`, `GazeWorld`, `GazeMath` | Justified attention: where the eyes and head go, and why. |
| `MascotIdentity` | Shape + colour, persisted per agent. |
| `HeadlessAvatarRuntime` | Renderer-less reference implementation: full state machine, clamped weights, subclass hooks (`loadCapabilities`, `onPlayGesture`, …) for adapters. |

Packages under `com.letta.mobile.avatar.core`, each under the structure gate's
default 50-type limit; no sub-package depends on the root, so they stay free of cycles:

| Package | Holds | Depends on |
|---|---|---|
| `.model` | Shared vocabulary: `AvatarModel`, `AvatarState`, `AvatarReaction`, the command types, `MascotIdentity`, host input values | nothing |
| `.gaze` | `GazeDirector` and the attention world/plan/math | `model` |
| `.body` | `MascotBody` spring physics, entrance, gesture classifier | nothing |
| (root) | Runtime, `AvatarDirector`, `MascotDirector`, signals, presence | `model`, `gaze` |

## Sibling modules

- `:avatar:renderer-rive` — the mascot: the shared Rive contract + runtime
  (commonMain), rive-android surface (androidMain), the native desktop bridge
  (`native/desktop`), and the rig sources under `rive/mascot`. See
  `avatar/renderer-rive/MASCOT.md`.
- `:sharedUI` `ui/mascot` — `MascotAvatar`, `MascotEntry`, presence → director
  mapping, the identity registry and the `MascotHost` seam platforms fill.

The 3D VRM/glTF route (three-vrm web renderer, import pipeline, catalog, GLB
reader, format detector, manifest, import policy, visemes) was removed in
favour of the mascot.
