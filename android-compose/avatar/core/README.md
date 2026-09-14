# :avatar:core

Renderer-independent avatar runtime core (commonMain only). This is the ONLY
avatar module the app is allowed to depend on — renderer adapters (rive-android
on Android, the native Rive bridge on desktop) implement these contracts and
are wired in at the platform edge.

## Design rules

- **Open format first**: glTF 2.0 / GLB is the canonical runtime asset; VRM 1.0
  for humanoids; VRM 0.x is importable but normalized at import. No proprietary
  editor, hosted avatar service, or single-vendor runtime in the core.
- **Renderer abstraction second**: everything a renderer needs flows through
  [`AvatarRuntime`](src/commonMain/kotlin/com/letta/mobile/avatar/core/AvatarRuntime.kt)
  and the normalized manifest — renderer internals never leak upward.
- **Platform optimization third.**

## What lives here

| Piece | Purpose |
|---|---|
| `AvatarRuntime` + `AvatarRuntimeState` | The control surface renderers implement and the app consumes. |
| `AvatarModel`, `AvatarCapabilities`, `AvatarLicense`, `AvatarAssetSource` | Catalog-facing data model. |
| `AvatarExpression`, `AvatarViseme`, `AvatarLookTarget`, `AvatarGesture` | Normalized command vocabulary (VRM 1.0 key space; VRM 0.x mapped at import). |
| `AvatarManifest` + `AvatarManifestCodec` | The `avatar.manifest.json` schema (v1) and tolerant JSON codec. |
| `GlbContainer` | Pure-Kotlin GLB 2.0 container reader (JSON/BIN chunk extraction). |
| `AvatarFormatDetector` | Sniffs `.vrm`/`.glb`/`.gltf` bytes → format, extensions, capabilities, normalized bones/expressions/visemes/animations. |
| `toManifest` / `toModel` | The single detection → manifest → catalog-entry normalization path. |
| `AvatarImportPolicy` | License gate: unknown/denied redistribution never enters the shared catalog. |
| `HeadlessAvatarRuntime` | Renderer-less reference implementation: full state machine, clamped weights, subclass hooks (`loadCapabilities`, `onPlayGesture`, …) for adapters. |

## Sibling modules

- `:avatar:renderer-rive` — the mascot: the shared Rive contract + runtime
  (commonMain), rive-android surface (androidMain), the native desktop bridge
  (`native/desktop`), and the rig sources under `rive/mascot`. See
  `avatar/renderer-rive/MASCOT.md`.
- `:sharedUI` `ui/mascot` — `MascotAvatar`, `MascotEntry`, presence → director
  mapping, the identity registry and the `MascotHost` seam platforms fill.

The 3D VRM/glTF route (three-vrm web renderer, import pipeline, catalog) was
removed in favour of the mascot; the catalog/manifest/import-policy types in
this module remain as the model vocabulary the runtime contract uses.
