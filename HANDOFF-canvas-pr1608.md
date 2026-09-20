# Handoff — PR #1608, canvas / pen / undo

**Branch:** `feat/canvas-note-typing` · **PR:** https://github.com/oculairmedia/letta-mobile/pull/1608
**Head at handoff:** `20d1cfa82` · everything is committed and pushed, working tree clean.

---

## Start here

```bash
cd android-compose
./gradlew.bat --no-configuration-cache :desktop:run \
  -PriveBridge=C:/letta-mobile-rive/android-compose/desktop/rive_desktop_bridge.dll
```

The Rive bridge DLL is **not** in this repo — it is built out of tree and lives in the
`letta-mobile-rive` worktree. Without `-PriveBridge` the staging task deletes any previously
staged DLL and every agent silently falls back to a gradient orb, which looks like the mascot
renderer being broken.

**Never run a Gradle build while the app is running.** The build replaces the classes the running
JVM loads lazily, and it dies with `ClassNotFoundException` on a class that was fine a second ago.
That killed three review sessions and is not a defect.

---

## Open work, in the order I would take it

### 1. An erased element comes back on release — `letta-mobile-ys5zi`

The most user-visible bug left, and there is a **failing test waiting for you**:

`desktop/src/test/kotlin/com/letta/mobile/desktop/canvas/CanvasEraseUiTest.kt`
→ `anErasedElementDoesNotComeBackWhenTheStrokeIsReleased` is `@Ignore`d **because it fails**.
Remove the `@Ignore` and you have a red test in about a minute.

What is already known:

- Deleting a **selection** does *not* resurrect — the sibling test in the same file passes. So the
  cause is on the erase path specifically, not the export-and-write both gestures share.
- Deleting via `Intent.DeleteElement` does not resurrect either (`CanvasDeleteElementUiTest`).
- The user's words: *"they erase then the elements reappear on release; delete through the button
  is the same."*

Where to look: DrawBox has an erase **session** (`BeginErase` / `EndErase`, plus an
`erasingSessionDirty` state flag) that plain deletion does not; `CanvasWorkspace` also runs
`eraseNotesAt` from a `Final`-pass `pointerInput` while erasing; and the 500 ms autosave can fire
mid-session. My suspicion — untested — is an export capturing a scene mid-erase, or the session
collector re-importing a scene that still holds the element.

**A warning from experience on this PR:** when I first "reproduced" this I wrote an assertion that
matched the `"_removed"` tombstone the scene deliberately keeps, and concluded the delete was
broken when it was not. Assert on the *projected elements*, not on the raw scene JSON. There is a
helper doing exactly that in `CanvasDeleteElementUiTest.hasElement`.

### 2. Quantify the disposed-layer race (the canvas-open flash)

The app survives a Compose render race rather than fixing it: a scene layer is disposed between a
frame being scheduled and drawn, Compose throws `RootNodeOwner is already disposed`, and
`isRecoverableRenderError` in `desktop/.../Main.kt` drops that frame instead of exiting.

It is now **counted**: every drop prints `RENDER: dropped frame #N (+Xms)`. Nobody has read those
numbers yet. Open and close a canvas five or six times and look:

- isolated drops hundreds of ms apart → one lost frame each (~8–17 ms), a blink;
- a burst a few ms apart → the scene is failing to recover for the whole burst, and the survival
  net is hiding something that repeats.

The headless repro (`:desktop:runCanvasCrashRepro`, or `-PreproNewCanvas=<rounds>` to drive the
real shell) comes back **clean over 40 rounds**, so it needs live pointer input to fire.

### 3. The Compose 1.10 → 1.12 question — untested, and underneath several oddities

The colour picker (`codes.side:colorpicker:1.2.0`) forced Compose 1.12; the tray update
(`composenativetray` 2.1.6) then required it. Everything else in the app targets 1.10/1.11,
including Jewel and Nucleus, which own the window chrome.

Appearing on 1.12 and never before: the tray `NoSuchMethodError`, the disposed-layer race, the
one-frame flash. **Nothing has been tested against 1.10.** One diagnostic build pinned back would
settle it. The honest caveat: the handler that reports the race did not exist before 1.12 either,
so the absence of older reports proves nothing.

### 4. CodeScene complexity gates — failing, deliberately not addressed

`TabletPen.start` (cyclomatic 16, threshold 9), `TabletPen.dispatch`, `post`, `offerToCanvas`,
`CanvasNotesLayer.resizedBy` and `commit`, plus module-level scores on `CanvasWorkspace`,
`TabletPen` and now `CanvasDocumentUndo`. This PR made `TabletPen` **worse**, not better. The PR
description says so rather than hiding it.

### 5. Unused engine capability worth having

Audited against DrawBox 2.1.0's actual surface: **element rotation** (`SetElementRotation` — the
selection chrome has eight resize handles and no rotate handle), **images**
(`InsertImage`/`insertImage` — no paste, no drop), **PNG export** (`saveBitmap` — we do JSON and
SVG), `registerFont`, and `BeginTransform`/`EndTransform` sessions.

Deliberately unused: DrawBox's own text elements (`InsertText`, `updateText`,
`RequestTextEditAt`, `setSelectionFont*`). We use block documents for text instead, which is what
makes notes editable, persistable and undoable — adopting DrawBox text would fork text handling.

---

## What landed, so you do not re-litigate it

**The PM review's ten findings** are all implemented with tests (`letta-mobile-mutgt.4` through
`.13`, all closed and pushed to Dolt). Three of them were defects in earlier commits *on this
branch*: a popup deferral that silently killed every menu in the app, chrome bounds orphaned by
recomposition (so the tool-rail fix was working by accident), and an over-broad error signature.

**Unified undo** (`CanvasHistory` + `CanvasDocumentUndo`, both in `sharedLogic/commonMain`):

- one history per board recording ORDER; drawing steps delegate to DrawBox because the elements
  are its, document steps carry inverse and redo ops;
- inverse ops are **stamped with the session clock when applied** — built with `lamport = 0` they
  were silently discarded by last-writer-wins and undo did nothing;
- the toolbar buttons answer for the board, not for DrawBox, or they grey out after note actions;
- drawing steps follow the **elements**, not the scene JSON — a background-only save recorded a
  phantom step, and recording anything discards the redo branch;
- an **unsaved** drawing change is undone first and never recorded, because a drawing step only
  exists after the 500 ms debounce and undo in that window reached past it to an older note action;
- every document change a person makes is recorded: text (per settled pause, through the editor's
  own writes), colour, style, move/resize, delete.

**The app no longer exits on a dropped frame.** `CrashReportingExceptionHandlerFactory` used to
call `exitProcess(1)` for *any* throwable. The crash was Compose's; the exit was ours.

**Pen:** per-window routing (`CanvasPenTarget`), polling as a job on the composition's scope,
declines over notes (read from the session at stroke start — the composed list is a frame behind)
and over registered chrome, declines during a temporary pan, releases with `down` cleared, and a
Rust panic can no longer abort the JVM.

---

## Gates

```bash
cd android-compose
./gradlew.bat --no-daemon :app:compileRootDebugKotlin :app:testRootDebugUnitTest \
  :desktop:test :sharedUI:jvmTest :sharedLogic:jvmTest :core:android-data:compileDebugKotlin
JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2 ./gradlew.bat --no-daemon \
  :architecture-tests:architectureTest
```

All green at `071478aba`. **The final commit `20d1cfa82` has not had a full local gate run** — it
compiles and its own tests pass, but CI is the current source of truth for it.

Adding a case to `CanvasOp` means updating `withActor`, `withStamp`, the projector dispatch **and**
`core/android-data/.../CanvasOpEntity.kt` — only the Android module fails if you miss the last one.
