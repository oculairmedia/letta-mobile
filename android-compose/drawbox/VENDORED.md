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

### 5. Gestures can read the host's live state (letta-mobile-8cik1)

Gesture callbacks read `State` through `rememberUpdatedState`, the value `DrawBox` was last composed
with, so an intent the host dispatched during a press (selecting what was pressed, say) was not
seen by that same gesture until the next frame. New optional `DrawBox(liveState = { ... })`, for
example `{ controller.state.value }`; null keeps the old behaviour.

### 6. Text inside shapes (letta-mobile-8cik1)

`Element.Shape` gains `text`, `textColor` (null = stroke colour), `fontSize`, `fontFamilyKey` and
`textAlignment` (default centre), all defaulted so existing drawings load unchanged. Rectangles,
circles and triangles hold text (`canHoldText`); it is wrapped to `textBox()` (inset rectangle,
inscribed square, lower middle of a triangle), centred in it vertically, drawn through the text
layout cache, turned with the shape, exported to SVG, and serialised on the existing text wire
fields plus a new `textColor`. `Intent.UpdateText` and the selection text intents apply to such
shapes; new `Intent.SetSelectedTextColor`. `RequestTextEditAt`, and a second tap on a selected shape,
emit `TextEditRequested` for shapes too. New `InlineShapeTextEditor`, and
`DrawBox(hiddenTextElementIds)` to hide a shape's text (not the shape) while it is edited; such a
shape renders live so the cached layer never replays its text. Tests: `ShapeTextTest`; Letta's
`CanvasShapeTextRenderUiTest` and canvas UI tests.

### 7. Aligned text is laid out at its full width (letta-mobile-8cik1)

`TextLayoutCache` measured with a maximum width only, so a short line's layout shrank to the text
and centre or right alignment happened inside that, leaving short centred text at the left of its
box (text elements as well as shapes). Text is now laid out at exactly the wrap width. Covered by
`CanvasShapeTextRenderUiTest`, which fails without it.

### 8. Images keep their proportions when resized (letta-mobile-k42fs)

Resize handles stretched an image freely. The circle's keep-it-square constraint is generalised to
a locked aspect ratio, and an image now keeps the ratio of its pixels (`intrinsicSize`), so one
stretched before snaps back to its true shape on its next resize. Edge handles set the dragged
dimension and centre the other on the anchor; corner handles grow to encompass the drag. Circles
behave as before (ratio 1). Tests: `GeometryTest` (image corner, edge, restore).

### 9. A cancelled capture is not a failed save (letta-mobile-nq2w1)

The bitmap capture coroutine caught `Throwable`, so cancelling it reported a failed
`Intent.SaveBitmap`. `CancellationException` is now rethrown.

## Repository policies

Letta's first-party checks do not apply here, so the code stays diffable against upstream: the
architecture test's package and `commonMain` import rules skip this module, and the detekt
guardrail skips its files. Build, tests and compiler warnings still apply.
