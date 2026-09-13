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

Inside the components, keep the input names `expr` (number, the expression index in the order
above) and `blink` (trigger), and keep the timelines the root scrubs: `LookX`/`LookY` in `Eye`,
`Open` in `Mouth`. Their *content* is yours.

## How it is built (so you know what you are editing)

- `Mascot` is the root: body paints, three `NestedArtboard` placements (two `Eye`, two `Brow`
  with the right one mirrored, one `Mouth`), and four state-machine layers - `Expression`
  (one animation per state; each forwards the expression index to the components and moves the
  body), `Breath` (loops forever), `Blink`, `Hover`.
- `Eye`, `Brow`, `Mouth` are components with their own `Expression` layer switching on `expr`.
  `Eye` also has `Blink` (from the trigger) and `AutoBlink` (random-length waits).
- Gaze and mouth are *scrubbed*: `lookX` sets the frame of `LookX` (authored as left pose -> right
  pose), `mouthOpen` sets the frame of `Open` (closed -> open). Redraw the poses, keep the ranges.
- Eyelids are a clip: the unpainted `Aperture` ellipse masks sclera and pupil; its height/offset
  is the lid position in every pose and in the blink. Replace with drawn lids if you prefer, but
  keep them colour-agnostic - the body colour changes per agent.

## The finishing touches this needs

In rough priority:

1. **Body**: a real soft body like the mood-orb reference - feathered fill, layered highlight and
   shading, a subtle rim. The current glow is stroke-based because the CLI cannot feather fills;
   the editor can. Keep the fill and glow bound to `color`.
2. **Eyes**: rim light / catchlight, a softer sclera edge, pupils with some depth.
3. **Mouth**: drawn shapes per expression (smile, frown, o, line) replacing the placeholder
   glyphs; the lifted open mouth (lips, teeth, tongue) is a decent base for `speaking`.
4. **Brows**: the lifted brow is fine as a base; taper and weight to taste; check every pose
   still clears the eyes (they do now - keep it that way).
5. **Motion**: the twelve state animations are one-frame poses with a few simple loops. Add
   anticipation and settle, especially `success` (hop) and `error` (dip + shake).
6. **Twelve distinct states**: `loading`, `failed`, `degraded` are the weakest today.

## Attribution

The brow and the open mouth were lifted from erdemediz's "Expression Grid" on the Rive community
(CC BY 4.0). If they survive your pass, the app's credits need the attribution; if you redraw
them, it can go.

## Checking your work without the app

```
rive . --verify
rive . --screenshot=out.png --data=state=success --data=lookX=-1 --data=mouthOpen=0.8 --advance=30
```

The app's desktop demo (`:desktop:runRiveSpike`, see `native/desktop/README.md`) renders the
built file through the same native runtime the product uses.
