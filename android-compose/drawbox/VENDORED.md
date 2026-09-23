# Vendored: DrawBox

| | |
|---|---|
| Upstream | https://github.com/akshay2211/DrawBox |
| Version | `v2.1.0` (commit `385b0a3`, "chore: release version 2.1.0") |
| Licence | Apache-2.0, see [LICENSE](LICENSE) |
| Replaces | `io.ak1:drawbox:2.1.0` and `io.ak1:drawbox-ui:0.0.1-alpha01` from Maven Central |

## Why it is vendored

The canvas (`sharedUI/.../ui/canvas`) is built on DrawBox's scene model, serialisation, rendering,
export, undo and selection chrome. Its interaction layer had gaps Letta kept working around from the
outside (hollow shapes hit only on the stroke, no pinch zoom, a fixed-colour grid, gesture code
reading state one recomposition behind, an intent flow that drops events). With the source here,
those are fixed where they live, and each fix is a candidate to contribute upstream.

## What was taken

- `DrawBox/src/{commonMain,androidMain,jvmMain,commonTest}` unchanged, as `src/`.
- From `drawbox-ui`: only `ControlsBarState`, `ControlsBarIntent` and `ControlsBarDispatch`, copied
  unchanged into `src/commonMain/.../ui/controls/ControlsBarModel.kt` in their original package.
  Letta draws its own controls (the published `drawbox-ui` Android artifact ships without its
  resources; see letta-mobile-r5f3r), so nothing else from that module is used.

Not taken: the iOS, JS, wasm and web source sets (Letta builds Android and JVM only), the sample
apps, docs, and upstream's publishing, Dokka, Spotless and lint setup.

## Letta changes

Every change to upstream code is listed here, newest last, with the bead that made it. Keep each
one small and self-contained so it can be offered back as its own upstream pull request.

_None yet: this module is byte-for-byte upstream v2.1.0 apart from the build file above._
