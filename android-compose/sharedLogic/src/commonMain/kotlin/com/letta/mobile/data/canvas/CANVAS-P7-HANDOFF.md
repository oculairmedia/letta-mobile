# Canvas P7 handoff — board editor delivered; what is left

Written for an implementor with no context on this session. Everything you need is
here or in the beads named below. Where this document is silent, ask; do not invent.

## 0. State of the world (2026-09-17, end of the P7 session)

- Repo `oculairmedia/letta-mobile`, Gradle root `android-compose/`.
- Worktree `C:/lm-canvas-p6`, branch `feat/canvas-workspace-p7-editor`, targets `main`,
  PR [#1601](https://github.com/oculairmedia/letta-mobile/pull/1601). It replaced #1599:
  the old P6 branch carried P0–P5 as raw commits and conflicted once P5 (#1597) merged, so
  the eight P6 commits were cherry-picked onto main and P7 built on top. #1599 can be closed.
- Beads: epic `letta-mobile-4i2z9`. **Every P7 bead (.8–.19) is closed**; `.13` was superseded
  by `.17` and `.10` folded into `.18`. `bd list -l p7` shows nothing open.
- A separate desktop tab-strip request landed as `letta-mobile-kyzfs` on
  `feat/desktop-tabs-kyzfs` (worktree `C:/lm-tabs`), PR
  [#1602](https://github.com/oculairmedia/letta-mobile/pull/1602).

### What P7 added (all in `sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/canvas/`)

| File | Role |
|---|---|
| `CanvasPropertyControl.kt` | The one master colour/property popover: targets the DrawBox selection, else the active note/text, else the tool. Embeds `CanvasColorPicker`; outline, width, opacity, dash, corner radius; text size/family/alignment. Opened from the selection bar and the rail swatch. |
| `CanvasControlsBridge.kt` | `buildProperties`/`dispatchProperty` beside the DrawBox bar bridge; `CanvasPropertyIntent` (width, opacity, dash, radius) because DrawBox's `ControlsBarIntent` is sealed. |
| `CanvasBackgroundPainter.kt` | Tile painter for DrawBox `setBackgroundPattern`; DrawBox repeats it under the viewport transform, so the grid pans and zooms. |
| `CanvasSnapLayer.kt` | Connector snapping: anchors (edge midpoints + centre of shapes and note frames), ring indicator, `snap`/`follow`. |
| `CanvasKeys.kt` | Key → action map (Delete/Backspace, Esc, Ctrl/Cmd+Z, +Shift+Z, +Y, +D). |
| `CanvasViewportFit.kt` | Fit-to-content maths (union of bounds, padded, clamped 5%..400%). |
| `CanvasTableBlock.kt` | Cascade custom block `letta.table`: type, descriptor, editable grid renderer, preview renderer, registry. |

Model additions in `sharedLogic/.../data/canvas/`: `CanvasBackgroundPattern` (+ `set_background_pattern`
op, `_bgPattern*` on the scene root, LWW), `CanvasArrowBinding`/`CanvasEndBinding` (+ `set_arrow_binding`,
`_arrowBindings` on the scene root, LWW per connector), `CanvasSnap` (anchor maths),
`CanvasSession.setBackgroundPattern/bindArrow/moveDocuments` (batch: one `set_document` per note,
one revision). The Room op entity and `withActor` know every op.

### Invariants you must keep

- **Never re-import the DrawBox scene on a local write.** `CanvasWorkspace` keeps `lastDrawing`
  and only calls `controller.importPath` when `CanvasOpProjector.drawingsEqual` says the drawing
  changed. `importPath` resets the camera. This is why bound arrow ends follow a moved note
  through `Intent.SetElementPoints` (then the normal export), not through session element ops.
- Deselect handler lives on the DrawBox layer (Initial pass); the Final-pass handler on the same
  layer tracks Alt and the pointer for snapping and snaps the connector a release just finished.
- One editor writes a document: a note open large renders `CanvasBlockPreview` on the card.
- Underscore keys never reach DrawBox (`stripMetadataForDrawBox`); pattern, bindings and
  documents all live under `_` keys on the scene root.
- Structural edits from the bar persist immediately; typing persists on a 750 ms tick.
- Recent colours are per workspace (`LocalRecentColors`); a process-global object fails the
  detekt guardrail `NoProcessGlobalMutableState` in CI.
- The DrawBox marquee rect, `MoveSelected` deltas and note-card drag deltas are all world units.
- Keys are handled where they bubble to (the board Box takes focus on a press), so a note
  editor keeps every key it consumes.
- DrawBox hit-tests an unfilled shape on its outline: to select one programmatically, aim at
  an edge (`selectionPointOf` in `CanvasWorkspace`).

### Gate (run before every push; CI also runs it)

```bash
cd C:/lm-canvas-p6/android-compose
./gradlew.bat --no-daemon --console=plain \
  :sharedLogic:jvmTest --tests '*Canvas*' \
  :sharedUI:jvmTest --tests '*CanvasViewportFitTest*' \
  :sharedUI:compileAndroidMain :desktop:compileKotlin \
  :desktop:test --tests '*CanvasWorkspaceUiTest*' --tests '*CanvasBlockEditorToolbarTest*'
```

Redirect the whole output to a file and grep it afterwards; piping into a `head`-style filter
kills Gradle mid-run. Results land in `*/build/test-results/`.
Last known: `CanvasWorkspaceUiTest` 14/14, sharedLogic canvas suites green, `CanvasViewportFitTest` 3/3.

**Do not run a gate while the dev app is running from the same worktree.** Rebuilding
`sharedUI`/`sharedLogic` replaces the jars the JVM lazily loads classes from, and the app dies on
the next lazily loaded class (seen as `ClassNotFoundException … CanvasNoteCard$commit$1`). Stop
the `java.exe` whose command line contains `lm-canvas-p6`, run the gate, relaunch.

### Run the app

```bash
./gradlew.bat --no-daemon :desktop:run -PriveBridge=C:/letta-mobile-rive/android-compose/desktop/rive_desktop_bridge.dll > ../desktop-runN.log 2>&1
```

Without `-PriveBridge` (or `LETTA_RIVE_BRIDGE_DLL`) the build logs "mascots fall back to orbs"
and the rail draws tiles instead of live mascots. The DLL lives in worktree `C:/letta-mobile-rive`.
The dev app is a plain `java.exe` window, title "Home".

## 1. What is left

1. **CI on #1601**: the required gates were green per push; CodeScene reports advisory findings
   (argument counts in `CanvasColorPicker`/`CanvasFormattingBar`/`CanvasSession.setDocument`,
   `CanvasOpProjector.writeDocument` complexity). The critical bumpy-road finding was the old
   selection bar, now gone; the notes-layer duplication was the three drag gestures, now one
   `Modifier.dragHandle`. Decide whether to pay the advisory items down before merge.
2. **Snap for the agent path**: bound arrow ends follow a note only where a workspace is open
   (the propagation goes through DrawBox). A headless `moveDocument` (agent tool) does not move
   bound arrows until a client with the board open sees the frame change. If that matters,
   move `CanvasSnapping.follow` into `CanvasSession.moveDocument` as element ops and accept the
   re-import cost, or teach the workspace to treat those element ops as its own.
3. **Live group follow for connectors**: a group drag (multi-select) moves notes live but bound
   arrows catch up on release; fine for now.
4. **Android smoke**: everything compiles for Android (`:sharedUI:compileAndroidMain` is in the
   gate) but the P7 UI was only exercised on desktop. Open a canvas on a device and try the
   master control, pattern, snap, keys, table.
5. **Table block on the agent side**: the agent's `set_document` accepts any Cascade JSON; a table
   is a block of type `letta.table` whose custom content is `{"rows":[["a","b"],["c","d"]]}`.
   Document this in the tool description if the agent should author tables.

## 2. Delivery contract (unchanged)

- Commit and push per coherent unit to `feat/canvas-workspace-p7-editor`. Never accumulate an
  uncommitted diff.
- Verify each unit with the gate before the next. UI tests must assert rendered state or
  session/controller state, never "the call returned".
- Update the PR body per unit (keep the Verified section honest).
- `bd note` the bead you are on with a bootstrap line when you stop; `bd dolt push`.
- Final message: branch, PR URL, SHAs, exact commands with real output, complete vs
  incomplete/unverified.

## 3. The "Canvas Mechanics — Implementation Brief" (chat-as-element)

Unchanged from the previous handoff: a different track (canvas as root surface, chat timeline as
one hosted element, CHAT_FOCUS/CANVAS layout states, chat bubble presence, culling bound to the
element region). Two holes the owner named must be resolved before anyone starts it: the canvas
element inventory (a product call) and the real Hermes interaction details. File it as its own
epic; do not start it from this branch.
