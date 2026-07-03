# :avatar:core

Renderer-independent avatar runtime core (commonMain only). This is the ONLY
avatar module the app is allowed to depend on — renderer adapters (Filament on
Android, three-vrm/WebView on desktop) implement these contracts and are wired
in at the platform edge.

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

- `:avatar:catalog` — local/offline catalog: `AvatarCatalog` over a pluggable
  `AvatarCatalogStore` (in-memory + atomic `catalog.json` file store).
- `:avatar:asset-pipeline` — JVM import pipeline + `avatar-import` CLI:
  detect → license-gate → inspect → hash → pack → manifest → catalog register.
  External glTF-Validator / glTF-Transform integration plugs in behind the
  `GltfInspector` seam.

## Planned (not yet built)

- `:avatar:renderer-filament-android` — Filament/gltfio adapter
  (subclass `HeadlessAvatarRuntime`, forward to `Animator`/morph weights).
- `:avatar:renderer-web` — WebView/JCEF + three.js + `@pixiv/three-vrm` adapter
  bridging the same commands over JS.
- Pipeline follow-ups: external-buffer GLB packing, thumbnails, external
  validator integration.
