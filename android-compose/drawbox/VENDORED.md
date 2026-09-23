# Vendored: DrawBox

| | |
|---|---|
| Upstream | https://github.com/akshay2211/DrawBox |
| Version | `v2.1.0` (commit `385b0a3`, "chore: release version 2.1.0") |
| Licence | Apache-2.0, see [LICENSE](LICENSE) |
| Replaces | `io.ak1:drawbox:2.1.0` and `io.ak1:drawbox-ui:0.0.1-alpha01` from Maven Central |
| Modified | yes, see [Letta changes](#letta-changes) |

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

### 1. Optionally pick hollow shapes anywhere inside (letta-mobile-8cik1)

Unfilled rectangles, circles and triangles are hit on their stroke only, so on a whiteboard a shape
could not be selected, dragged or double-clicked into from its middle. New opt-in
`State.selectInsideHollowShapes` (set by `Intent.SetSelectInsideHollowShapes`, kept across
`Intent.Reset`, never serialised) makes them hit inside like filled ones; picking still goes by
z-order, so anything drawn inside a shape wins. Default unchanged. `Element.hitTest` and
`topmostHit` take a `hollowInterior` flag; the reducer, controller and drag classifier pass the
state's value; the eraser keeps stroke-only. Tests: `GeometryTest`, `SelectionReducerTest`.

### 2. Drags start where the press went down (letta-mobile-8cik1)

`detectDragGestures` reports `onDragStart` where the touch slop was crossed, not at the press.
Starting the drag there made a moved element trail the pointer by the slop distance, cut the first
stretch off pen strokes, and anchored marquees short of where they were begun. The press position
is now recorded (Initial pass, a plain holder so nothing recomposes) and used as the drag origin.
Covered by Letta's `CanvasTouchAndShapeTextUiTest` drag tests.

### 3. Grid colour and spacing, drawn crisp (letta-mobile-8cik1)

`DrawBox` gains `gridColor: Color?` (null keeps the auto-contrast colour) and `gridSpacing` (default
the previous 50 world units). Grid lines are snapped to pixel centres: a one-pixel line on a pixel
boundary was antialiased across two pixels at half strength, which greyed and blurred it. Covered
by Letta's `CanvasGridColorUiTest`.

### 4. Event bursts are not dropped (letta-mobile-8cik1)

`DrawBoxController.events` had one buffer slot and emits with `tryEmit`, so an event arriving while
another was pending was lost (for example an SVG export requested during an autosave's JSON
export). The buffer is now 64. Test: `DrawBoxControllerTest.eventsEmittedInABurstAllArrive` (fails
with the old buffer).

### Candidates not done yet

- Text inside shapes as part of the shape element (Letta layers a separate text document over each
  shape today).
- Gesture classification reads `State` through `rememberUpdatedState`, one recomposition behind the
  controller; intents dispatched during the same press are not seen until the next frame.
