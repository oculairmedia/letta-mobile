# Canvas P7 handoff — finish the board editor to spec

Written for an implementor with no context on this session. Everything you need is
here or in the beads named below. Where this document is silent, ask; do not invent.

## 0. State of the world

- Repo `oculairmedia/letta-mobile`, Gradle root `android-compose/`.
- Worktree `C:/lm-canvas-p6`, branch `feat/canvas-workspace-p6-blocks`, tip `c62fcc977`,
  PR [#1599](https://github.com/oculairmedia/letta-mobile/pull/1599), **stacked on**
  `feat/canvas-workspace-p5-desktop-shell` (PR #1597, worktree `C:/lm-canvas-p4`).
- The stack is 14+ commits behind `main`. Merging main into P5 conflicts in 32 files
  (main holds P0–P3.1 squash-merged). That reconciliation belongs to #1597; do not
  attempt it from P6. The mascot rail is on main and will appear once P5 is rebased.
- Beads: epic `letta-mobile-4i2z9`. Open P7 beads (all with acceptance criteria):
  `.8` colour picker (implemented, needs a green re-run then close),
  `.9` real text tool (implemented, same), `.10` width/opacity/dash/radius
  (fold into master control), `.11` keyboard, `.12` duplicate, `.13` background
  patterns (superseded by the pattern-control bead below), `.14` zoom to fit,
  `.15` multi-select incl. notes, `.16` table block, plus the three filed at handoff:
  `.17` **background pattern control (type/scale/colour)**, `.18` **master colour/property
  control for the selection**, `.19` **snap lines/arrows to shapes**. `bd list -l p7`.

### What is built (P6 commits `136a8a5b2..c62fcc977`)

All UI in `android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/canvas/`
(Android and desktop render the same code; the cardinal rule is feature logic in
commonMain, platform modules only bind):

| File | Role |
|---|---|
| `CanvasWorkspace.kt` | The board: DrawBox layer, notes layer, chrome, wiring, session load/save, re-import gate |
| `CanvasNotesLayer.kt` | Block-document cards on the board in world coords: drag, resize, active state, plain text variant |
| `CanvasBlockEditor.kt` | Cascade editor per document, commit-based persistence, hoisted toolbar, per-doc `CanvasTextStyle` via theme |
| `CanvasFormattingBar.kt` | Foot bar: all Cascade block kinds + inline styles, `NoteToolbar` |
| `CanvasSelectionBar.kt` | Top bar: stroke/fill/outline/order/delete for drawn selection; note/text bar (size, family, align, text colour, card colour, open, delete) |
| `CanvasControlsBar.kt` | Left tool rail (select, pan, pen, line, arrow, rect, ellipse, triangle, eraser, text, note, stroke swatch, undo/redo) |
| `CanvasColorPicker.kt` | `ColorSwatchPicker` → `CanvasColorPicker` (presets, recent, HSL, hex), `RecentColors`, palettes, `toHex`/`parseHexColor` |
| `CanvasChrome.kt` | Title pill, actions pill (zoom, history, share, overflow with samples/export/clear/background), status line |
| `CanvasNoteEditorPanel.kt` | Note opened large, as a panel inside the board (not a platform dialog) |
| `WheelZoomRegions.kt` | Lets the desktop font-scale host skip Ctrl+wheel over the board |
| `CanvasControlsBridge.kt` | DrawBox intents → controller (unchanged from P5) |

Model in `sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/canvas/`:
`CanvasSceneDocument(id, json, frame, color, style)`, `CanvasDocumentFrame`,
`CanvasTextStyle`, `CanvasOp.SetDocumentOp(…, frame?, color?, style?)` /
`RemoveDocumentOp`, projector LWW per document with keep-on-null semantics
(`CanvasOpProjector.writeDocument`), `CanvasOpProjector.drawingsEqual`,
`CanvasSession.setDocument/moveDocument/recolorDocument/restyleDocument/removeDocument`.

### Invariants you must keep

1. **Never re-import the DrawBox scene on a local write.** `CanvasWorkspace` keeps
   `lastDrawing` and only calls `controller.importPath` when
   `CanvasOpProjector.drawingsEqual` says the drawing changed. `importPath` resets the
   camera; this was the "canvas jumps on every move" bug.
2. **Deselect handler lives on the DrawBox layer, not the board Box.** A board-wide
   initial-pass press handler swallowed clicks on the foot bar.
3. **One editor writes a document.** When a note is open large, the card renders
   `CanvasBlockPreview`.
4. **Underscore keys never reach DrawBox** (`stripMetadataForDrawBox`); documents live
   in `_documents` beside `elements`.
5. **Structural edits from the bar persist immediately** (`NoteToolbar.onStructuralChange`);
   typing persists on a 750 ms tick only when JSON changed.

### Gate (run before every push; CI also runs it)

```bash
cd C:/lm-canvas-p6/android-compose
./gradlew.bat --no-daemon --console=plain \
  :sharedLogic:jvmTest --tests '*CanvasDocumentBlocksTest*' --tests '*CanvasSessionTest*' --tests '*CanvasMultiplayerSyncTest*' \
  :sharedUI:compileAndroidMain :desktop:compileKotlin \
  :desktop:test --tests '*CanvasWorkspaceUiTest*' --tests '*CanvasBlockEditorToolbarTest*'
```
Redirect the whole output to a file and grep it afterwards; piping into a `head`-style
filter kills Gradle mid-run. Results land in `*/build/test-results/`.

Run the app: `./gradlew.bat --no-daemon :desktop:run > ../desktop-runN.log` (background;
stop the previous `java.exe` whose command line contains `lm-canvas-p6` first, or the
log file stays locked). The dev app is a plain `java.exe` window, title "Home".

**Last known gate at `c62fcc977`:** sharedLogic blocks 9/9, toolbar test 1/1, both
compiles green, `CanvasWorkspaceUiTest` 8/9. The single failure was a duplicated
"Note color" content description in the test after the note bar gained a colour
swatch; the bar swatch was relabelled "Note card color" and **not re-run**. First
task: run the gate, fix if anything remains, close `.8` and `.9`.

## 1. Work to do, in order

### T1. Re-run gate, close `.8` / `.9` (30 min)
Success: gate green; `bd close letta-mobile-4i2z9.8` and `.9` with a note.

### T2. Master colour/property control for the selection (`.18`, folds `.10`)
The owner's model is Concepts: one control that always targets the selected element.
- One popover (`CanvasPropertyControl`) opened from one swatch/button in
  `CanvasSelectionBar` and from the rail's stroke swatch. Targets, in priority: DrawBox
  selection → active note/text → current tool defaults.
- Sections that apply to the target only: stroke colour, fill (+none), outline,
  stroke width, opacity, dash, corner radius (rect); for notes/text: card colour,
  text colour, size, family, alignment. Embed `CanvasColorPicker`; delete every other
  colour popover so only one exists.
- DrawBox intents already exist for all drawing props (`SetStrokeWidth`,
  `SetSelectedStrokeWidth`, `SetOpacity`, `SetStrokeStyle`/`SetSelectedStrokeStyle`,
  `SetCornerRadius`/`SetSelectedCornerRadius`); route through `CanvasControlsBridge`
  (add intents there, keep its tests). Note/text props go through
  `session.recolorDocument/restyleDocument`.
- Selection bar shrinks to: control, front/back, duplicate (T5), delete.
- Tests: desktop UI test selects a rectangle and changes fill/width/opacity, asserting
  `controller.state.value`; activates a note and changes size/colour, asserting
  `session.documents()`.

### T3. Background pattern control: type, scale, colour (`.17`, supersedes `.13`)
- Board settings in the overflow "Background" section: pattern none/grid/dots/lines,
  spacing (world units, chips or slider), pattern colour via the shared picker, plus the
  existing board colour.
- Persist on the scene root beside `bgColor` (extend `SetBackgroundOp` or add
  `set_background_pattern`; projector LWW like `_bgLamport`), sync, reload.
- Render via `DrawBoxController.setBackgroundPattern(painter, tint)`; the painter must
  respect `state.viewport.scale` and offset so the grid moves with the board.
- Tests: sharedLogic persistence; desktop UI test switches pattern and spacing.

### T4. Snap lines and arrows to shapes and notes (`.19`)
- Snap radius ~12 screen px to edge midpoints/centre of DrawBox shapes and to note/text
  frames. DrawBox has `Element.Shape.startBinding/endBinding` and
  `Intent.FinalizeArrowBindings`; check what the reducer does with them before adding a
  parallel mechanism. Notes need a binding by document id: when a note's frame changes
  (`moveDocument`), update bound arrow endpoints through the session as element ops.
- Alt holds snapping off. Show a snap indicator.
- Tests: anchor maths unit test; desktop UI test drags a note, asserts the bound arrow
  end moved.

### T5–T8. `.11` keyboard, `.12` duplicate, `.14` zoom to fit, `.15` multi-select incl. notes
Criteria on the beads. `.16` table block is a custom Cascade block (bigger; last).

## 2. Delivery contract

- Commit and push per coherent unit to `feat/canvas-workspace-p6-blocks` (or a P7
  branch stacked on it if #1599 has merged). Never accumulate an uncommitted diff.
- Verify each unit with the gate before the next. UI tests must assert rendered state
  or session/controller state, never "the call returned".
- Update the PR body per unit (the body is in the PR; keep the Verified section honest).
- `bd note` the bead you are on with a bootstrap line when you stop; `bd dolt push`.
- Final message: branch, PR URL, SHAs, exact commands with real output, complete vs
  incomplete/unverified.

## 3. The "Canvas Mechanics — Implementation Brief" (chat-as-element)

The owner also pasted a separate brief (canvas as root surface, chat timeline as one
hosted element, CHAT_FOCUS/CANVAS layout states, chat bubble presence, culling bound to
the element region). It is a **different track** from this board editor and is out of
scope for the P7 beads above. Two holes the owner named must be resolved before anyone
starts it: the canvas element inventory (a product call) and the real Hermes
interaction details. Do not start it from this branch; file it as its own epic.
