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

### 5. A cancelled capture is not a failed save (letta-mobile-nq2w1)

The bitmap capture coroutine caught `Throwable`, so cancelling it reported a failed
`Intent.SaveBitmap`. `CancellationException` is now rethrown.

### 6. Review fixes (letta-mobile-8cik1)

Each small and separately offerable upstream:

- `UseCase.addElement` gives a new element `max(zIndex) + 1`, not the list size, which after a
  bring-to-front or a delete could put it under an existing element. `topmostHit` breaks zIndex
  ties toward the later element, the one drawn on top.
- New `Intent.SelectIds` / `DrawBoxController.selectIds`: select by id, for hosts that know what to
  select (a point can land on a connector ending on the element).
- `DrawBoxController.importPath` keeps the host's stroke colour, width, opacity, background pattern
  and `selectInsideHollowShapes` (it read them after `reset()`, so got defaults). `onIntent` and
  `importPath` carry `State.invokeBitmap`, a body property `copy()` drops, so `saveBitmap()` right
  after an intent captures instead of doing nothing.
- The tool-memory reducer restores a saved `null` fill (stroke-only) instead of treating it as
  "nothing saved".
- Colours serialise with `roundToInt` (truncation darkened some channels by one step per save);
  the JSON reader ignores unknown fields from newer writers.
- SVG export: the viewBox is the union of `bounds()` (circles, bent connectors, text), turned by each
  element's rotation and padded by half the stroke, with the maximum starting at negative infinity
  (it started at `Float.MIN_VALUE`, a positive number). Paths keep their dash style and rotation;
  shapes use `points.last()` and the renderer's pivot.
- Renderer: the eraser ring is drawn at the radius the eraser hits at any zoom; a translucent
  pressure stroke's layer is bounded by the stroke, not the canvas size (which clipped it after a pan);
  with an image cache, a cache miss draws the placeholder instead of decoding on every frame.
- `InlineTextEditor` turns with a rotated text element.
- Android paste reads at most 50 MB and 100 megapixels and passes the original bytes through (the
  PNG re-encode decoded at full size and dropped EXIF orientation). The JVM drop target reports the
  drop in canvas-local pixels, reads files off the AWT thread, takes image sizes from headers and
  caps files at 20 and 50 MB each.

Tests: `SvgExporterTest`, `SerializationTest`, `GeometryTest`, `DrawBoxControllerTest`.

### Candidates not done yet

- Text inside shapes as part of the shape element (Letta layers a separate text document over each
  shape today).
- Gesture classification reads `State` through `rememberUpdatedState`, one recomposition behind the
  controller; intents dispatched during the same press are not seen until the next frame.

## Repository policies

Letta's first-party checks do not apply here, so the code stays diffable against upstream: the
architecture test's package and `commonMain` import rules skip this module, and the detekt
guardrail skips its files. Build, tests and compiler warnings still apply.
