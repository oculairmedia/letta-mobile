# Mascot: handoff for the art pass

This file is a working rig. The logic - what the app drives, how expressions switch, how the
eyes follow the cursor and the mouth follows speech - is done and tested against the app. What it
needs is an illustrator's hand on the drawing. Everything visual is yours to change; a short list
of names is not.

## Where the file is

It is already in the Rive workspace: project **oculair / Shared Project**, file **mascot**
(id 2578084, first revision "rive-cli push"). Open it there. A `.rev` of the same state is in
`build/mascot.rev` if you want a local copy.

**Once you start editing in the editor, the editor file is the source of truth.** Until then,
the repo side can still regenerate (`python gen_scene.py`) and `rive push` a new revision - the
generator's ids are stable, so pushes update objects in place. After your first edit, no more
CLI pushes: they would overwrite your work. Tell the engineers when you begin.

When you are done, export the `.riv` and replace `src/androidMain/res/raw/mascot.riv`. Then run
the contract check so a renamed input cannot slip through:

```
python ../../native/rivdump/check_contract.py . ../../src/commonMain/kotlin/com/letta/mobile/avatar/rive/RiveAvatarContract.kt
```

## What the app writes (do not rename)

On the `Avatar` view model of the `Mascot` artboard, state machine `Avatar`:

| Property | Type | Meaning |
|---|---|---|
| `state` | enum `AvatarState` | the **sustained** expression; keys `idle listening thinking waitingInput speaking error sleeping loading failed degraded` |
| `success` | trigger | a task completed: the `Flash` layer plays the happy flash and returns on its own |
| `error` | trigger | something failed: the sad flash; `state` then settles to `error` |
| `dragged` | boolean | held while the pet is dragged; the file's own drag listeners write it too |
| `mouthOpen` | number 0..1 | speech amplitude, updated every frame while speaking |
| `lookX`, `lookY` | number -1..1 | gaze; 0,0 is straight ahead |
| `blink` | trigger | one blink (the eyes also blink on their own) |
| `color` | colour | the agent's identity colour; body and glow must stay bound to it |
| `shape` | enum `MascotShape` | reserved; one body for now |
| `hovered` | boolean | written by the file's own pointer listeners, not by the app |

Inside the `Plate` component, keep the input names `expr` (number, the expression index:
`idle listening dragged thinking waitingInput speaking success error sleeping loading failed
degraded` = 0..11) and `blink` (trigger), and keep the timelines the root scrubs: `LookX`,
`LookY`, `Open`. Their *content* is yours.

## How it is built (so you know what you are editing)

The language is the "plate" sheet: one eye, one glyph on a white plate, a soft body that morphs.

- `Mascot` is the root. `Body` is **one 8-vertex path** whose vertices are keyed per sustained
  state (circle; teardrop for `listening`; blob for `degraded`; squash while `dragged`). Its
  paints, bottom to top: feathered soft edge, the bound identity fill, shade, gloss, a `Tint`
  fill whose colour is keyed per state (darker for `sleeping`, grey for `failed` - overlays, so
  the palette colour underneath still works), and a thin glass ring. `Halo` is the same path
  enlarged, as a wide feathered stroke.
- Root layers: `Expression` (sustained states: morph + tint + motion + glyph index), `Flash`
  (`success`/`error` triggers, self-returning), `Drag` (boolean + the file's dragStart/dragEnd
  listeners), `IdleVariety` (random waits, a glance), `Breath`, `Blink`, `Hover`.
- `Plate` is the one component: a white card, a `Glyphs` node with one shape per glyph
  (square, ring, dash, small ring, open arc, smile, diamond, arch, dot, cross) switched by opacity
  on `expr`, plus the `TellDot` under the plate for `waitingInput` and the `FrownLine` for
  `error`. `LookX`/`LookY` shift the glyph inside the plate (and the plate a little);
  `Open` scales the speaking arc; `Blink` squashes the whole plate to a line. `AutoBlink` runs
  on random-length waits.
- Everything in `Glyphs` is placeholder geometry for the sheet's symbols - redraw freely, keep
  the shape names and ids so the poses still find them.

## The finishing touches this needs

In rough priority:

1. **Body**: the soft body of the plate sheet - feathered fill, the light from upper-left,
   the subtle rim. The current glow is stroke-based because the CLI cannot feather fills; the
   editor can. Keep the fill and halo bound to `color`; keep the tints as overlays.
2. **Plate and glyphs**: the plate's shadow and edge; each glyph redrawn to the sheet (the
   placeholders are primitives). The glyph set is fixed by the states; its drawing is yours.
3. **Body morphs**: `listening` teardrop and `degraded` blob are numeric guesses - shape them.
   Consider a morph for `thinking` (lean) and `sleeping` (settle) too.
4. **Motion**: poses are one frame with a few simple loops. Add anticipation and settle,
   especially the `success` hop and the `error` shake, and design the transition pairs in the
   product list (`idle→listening`, `listening→thinking`, `thinking→speaking`, `→sleeping`).
5. **Not yet on the sheet**: `success` flash, `error` flash, `dragged`, `hovered`, gaze poses
   (look left / look up), and the blink - decide whether the plate squashes or the glyph winks.

## Attribution

Nothing lifted from third-party files remains in this rig. (Earlier revisions used brow and
mouth art from erdemediz's "Expression Grid", CC BY 4.0; if you bring any of it back, credit it.)

## Checking your work without the app

```
rive . --verify
rive . --screenshot=out.png --data=state=success --data=lookX=-1 --data=mouthOpen=0.8 --advance=30
```

The app's desktop demo (`:desktop:runRiveSpike`, see `native/desktop/README.md`) renders the
built file through the same native runtime the product uses.
