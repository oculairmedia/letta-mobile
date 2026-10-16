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

On the `Avatar` view model of the `Mascot` artboard, state machine `Avatar` - the list is
`rig/contract.py`, and `test_contract.py` holds this table to it (the tunables are the review
bench's, not the app's; `hovered` and `dragged` are written by the app and by the file's own
pointer listeners):

| Property | Type | Meaning |
|---|---|---|
| `state` | enum `AvatarState` | the **sustained** expression; keys `idle listening thinking waitingInput speaking error sleeping loading failed degraded working dreaming` |
| `mouthOpen` | number 0..1 | speech amplitude, updated every frame while speaking |
| `lookX` | number -1..1 | gaze; 0 is straight ahead |
| `lookY` | number -1..1 | gaze; 0 is straight ahead |
| `blink` | trigger | one blink (the eyes also blink on their own) |
| `shape` | enum `MascotShape` | the identity's body, one of eight |
| `color` | color | the agent's identity colour; body and glow must stay bound to it |
| `hovered` | boolean | pointer over the mascot (app and file listeners) |
| `success` | trigger | a task completed: the `Flash` layer plays the happy flash and returns on its own |
| `error` | trigger | something failed: the sad flash; `state` then settles to `error` |
| `dragged` | boolean | held while the pet is dragged (app and file listeners) |
| `tunePlate` | number 0..1 | bench: plate scale |
| `tuneGlyph` | number 0..1 | bench: glyph scale |
| `tuneMouth` | number 0..1 | bench: mouth scale |
| `tuneMouthY` | number 0..1 | bench: mouth height |
| `tuneScale` | number 0..1 | bench: whole-entity scale |
| `turnX` | number -1..1 | head turn toward what the eyes look at |
| `turnY` | number -1..1 | vertical head turn |
| `shapeRotation` | number 0..360 | the identity's body turn, degrees |
| `reduceMotion` | boolean | calm everything: reactions and flashes become a blink or a glyph change |
| `petting` | boolean | held while petted: content glyph, side sway, purr |
| `listenLevel` | number 0..1 | listening intensity: the face lifts and leans in |
| `moveX` | number -1..1 | travel / drag velocity: the body leans about its feet |
| `moveY` | number -1..1 | vertical travel: up stretches, down squashes |
| `impact` | number 0..1 | how hard the next `land` is |
| `trickId` | number 0..3 | which trick the next `trick` plays |
| `poke` | trigger | squish, startled glyph, rebound |
| `dizzy` | trigger | dizzy glyph, wobble |
| `sent` | trigger | "got it" dip and lift |
| `toolStart` | trigger | small hop, glance down |
| `toolDone` | trigger | nod |
| `land` | trigger | arrival squash scaled by `impact` |
| `wake` | trigger | startle stretch |
| `yawn` | trigger | yawn: closed eye, round mouth |
| `trick` | trigger | trick `trickId`: spin-flip, jump-twirl, peekaboo, melt-and-reform |

Inside the `Plate` component, keep the input names `expr` (number, the expression index:
`idle listening dragged thinking waitingInput speaking success error sleeping loading failed
degraded working dreaming content startled dizzy yawning trick` = 0..18; the last five are
plate-only, shown by the `Pet` / `React` layers) and `blink` (trigger), and keep the timelines
the root scrubs: `LookX`, `LookY`, `Open`. Their *content* is yours.

## Placeholder glyphs waiting for art (letta-mobile-te3n1.7)

These ship as clearly marked placeholders derived from existing glyphs (each SVG says so in a
comment). Replace the SVG, keep the file name, the `-50..50` viewBox and one `#111111` path,
regenerate, done - no rig change needed:

| File | Shown for | Placeholder is |
|---|---|---|
| `glyph-dreaming.svg` | `state` dreaming | a copy of sleeping |
| `glyph-thinkingOpen.svg` | `state` thinking, at rest (the squint beat swaps in `glyph-thinking.svg`) | idle at 55 % height |
| `glyph-content.svg` | `petting` | success scaled 90 % x 60 % |
| `glyph-startled.svg` | `poke`, `wake` | idle at 115 % |
| `glyph-dizzy.svg` | `dizzy` | loading at 140 % (wants a spiral) |
| `glyph-trick.svg` | `trick` | a copy of success |

`glyph-working.svg` is real art (z4b83) and is now wired to `working`; `yawning` reuses the
sleeping glyph with `MouthO`. No `-small` variants yet (the rig has no small profile).

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
  every state row has a facing (idle −0.15, thinking −0.6 away, listening and working 0
  square-on, sleeping and dreaming down-and-away…), so a state change is a turn blended over the
  transition; the `Wander` layer waits 9.3–24 s then plays a side glance, a peek, or rarely a
  spin (held while `working`, asleep or under `reduceMotion`); the `success` flash spins
  (0 → +1 → −1 → 0) with `Trail1`/`Trail2`, two ghost plates lagging 2 and 4 frames.
- Root layers: `Shape` (identity), `Expression` (sustained states: root y/x motion on Body and
  Face together, plate rotation and offset on Face, tint, glyph index; **every state change
  goes through an `Enter_<from>_<to>` one-shot** that fires the plate blink at frame 0 and
  flips the glyph at frame 6 while the eye is shut, turning the facing to the new state's;
  the SPEC §3 pairs carry designed body/face motion, the rest travel the held pose to the next.
  Each loop has a `Calm_<state>` twin - the same pose, held - that `reduceMotion` blends to.
  Inside `Plate`, glyph switches are instant cuts - the shutter is what hides them), `Flash` (`success` 800 ms / `error` 600 ms triggers per SPEC §2, self-returning),
  `Drag` (boolean + the file's dragStart/dragEnd listeners), `IdleVariety` (four waits of
  6.1–14 s, then one of nine beats), `Breath` (gloss opacity), `Blink`, `Hover` (perk-up, then an
  attentive hold), and te3n1.5's `Pet` (side sway), `PetFace` (content glyph) and `React` (one
  one-shot per reaction trigger, keyed on the `Squash` node at the feet and `Peek` in the face).
- `Plate` is the one component: `Card` (120 px, radius 27) with a soft `Shadow`, a `Glyphs` Solo
  holding one shape per state glyph (`Idle`, `Listening`, `Thinking`, `WaitingInput`, `Speaking`,
  `Success`, `Error`, `Sleeping`, `Loading`, `Failed`, `Degraded`, `Dragged`, then `Working`,
  `Dreaming`, `ThinkingOpen`, `Content`, `Startled`, `Dizzy`, `Trick` - the SVGs by name;
  the Solo's active child is keyed per `expr`), and three mouths below the plate: `Mouth` (morphs closed/half/open on the
  `Open` timeline from the three mouth SVGs), `MouthO` (waitingInput), `FrownLine` (error).
  `LookX`/`LookY` move the glyph ±23/±17 px; `Blink` squashes the `Glyphs` node (55/25/90 ms);
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
