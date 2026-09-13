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

The language is the "plate" sheet: one eye, one glyph on a white plate, a soft body. The rig
is generated from `SPEC.md` (numbers) and `art/*.svg` (geometry) by `gen_scene.py`; the motion
sources are cited in `MOTION-REFERENCES.md`.

- `Mascot` is the root. `Body` is **one 8-vertex path whose shape is an identity**: the `shape`
  enum picks one of the eight `art/body-*.svg` (240 ms morph on the `Shape` layer). States never
  change the silhouette or scale the body. Paints, bottom to top: the bound identity `Fill`,
  `Shade`, `Gloss` (its opacity breathes), `Tint` (colour keyed per state: darker for `sleeping`,
  grey for `failed`, overlays over the palette colour). `SoftEdge` and `Halo` are faint feathered
  strokes of the same path behind it.
- **Facing** is a `Joystick` (`Facing`, on the root): its x/y scrub the `TurnX`/`TurnY` pose
  ranges, which slide the `Turn` node (plate) ±70/±24 px across the body and foreshorten it at
  the edges. Nothing else keys `Turn`. Whoever wants the character to turn keys the joystick:
  every state row has a facing (idle −0.15, thinking −0.6 away, listening 0 square-on, sleeping
  down-and-away…), so a state change is a turn blended over the transition; the `Wander` layer
  waits 6–12 s then plays a side glance, a peek, or rarely a spin; the `success` flash spins
  (0 → +1 → −1 → 0) with `Trail1`/`Trail2`, two ghost plates lagging 2 and 4 frames.
- Root layers: `Shape` (identity), `Expression` (sustained states: root y/x motion on Body and
  Face together, plate rotation and offset on Face, tint, glyph index; **every state change
  goes through an `Enter_<from>_<to>` one-shot** that fires the plate blink at frame 0 and
  flips the glyph at frame 3 while the eye is shut, turning the facing to the new state's;
  the SPEC §3 pairs carry designed body/face motion, the rest hold and ease in on the hand-off.
  Inside `Plate`, glyph switches are instant cuts - the shutter is what hides them), `Flash` (`success` 800 ms / `error` 600 ms triggers per SPEC §2, self-returning),
  `Drag` (boolean + the file's dragStart/dragEnd listeners), `IdleVariety` (random 4–7 s waits,
  a glance), `Breath` (gloss opacity), `Blink`, `Hover` (one wiggle per enter).
- `Plate` is the one component: `Card` (120 px, radius 27) with a soft `Shadow`, a `Glyphs` Solo
  holding one shape per state glyph (`Idle`, `Listening`, `Thinking`, `WaitingInput`, `Speaking`,
  `Success`, `Error`, `Sleeping`, `Loading`, `Failed`, `Degraded` - the SVGs by name; the Solo's
  active child is keyed per `expr`), and three mouths below the plate: `Mouth` (morphs closed/half/open on the
  `Open` timeline from the three mouth SVGs), `MouthO` (waitingInput), `FrownLine` (error).
  `LookX`/`LookY` move the glyph ±7/±5 px; `Blink` squashes the `Glyphs` node (55/25/90 ms);
  `AutoBlink` waits 2.5–4.5 s.
- To change a glyph or a body: edit the SVG, regenerate. To change timing: edit SPEC.md and the
  matching table in the generator. In the editor, keep the object names and ids so the poses
  still find them.

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
