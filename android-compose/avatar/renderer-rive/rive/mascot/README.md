# Mascot rig: working notes for whoever picks this up cold

The Letta agent mascot is a Rive file generated from Python. This page is the operating manual:
what the files are, the edit-verify-review-ship loop, the Rive gotchas that cost days, and the
state of the rig. The design intent lives in `SPEC.md` (numbers) and `MOTION-REFERENCES.md`
(why); the illustrator's handoff is `ARTIST.md`; the codebase plan is `../../MASCOT.md`.

Beads: `letta-mobile-kh094` (this asset), `letta-mobile-1zti3` (identity picker slice),
`letta-mobile-bn0y6` (orbs everywhere), `letta-mobile-0s5bi` (the native desktop spike).

## Files

The generator is `gen_scene.py` plus the `rig/` package. `gen_scene.py` only assembles: the
artboard shell, the data section (enums, converters, view model) and the document. Everything
else lives in one module per concern, and the dependency runs one way - `ids` -> `constants` ->
`body` / `face` / `plate` -> `motion` -> `machine` -> `gen_scene`. Every module imports by name;
there are no star imports, so the definition of anything is one jump away.

| Path | What | Edit? |
|---|---|---|
| `gen_scene.py` | Assembly only: artboard shell, enums / converters / view model, the document, `--solo`. **Entry point.** | yes |
| `rig/ids.py` | Every named Rive object id, plus the state / shape / glyph vocabulary the id ranges are indexed by. Uniqueness checked at import; new ids come from `alloc(space, name)`, recorded in `ids.json`. **Existing ids are frozen.** | add only |
| `rig/constants.py` | The design numbers: waits, saccade table, designed entry pairs, turn / lean amplitudes, tints, tunables, and the unit helpers `art` / `rad` / `frames` / `beat` | yes |
| `rig/body.py` | The body: one 8-vertex path per identity, the three bones and their weights, the paint stack, and the breath / lumen / squash / bone-pose key recipes | yes |
| `rig/face.py` | The face assembly: the FacePlacement > HostTurn > Turn > Arc > Face chain that carries the plate, the TurnX/TurnY pose ranges, the trails, `spin_keys()` | yes |
| `rig/plate.py` | The Plate component: glyph per state, mouth morph, pupil overlay, tunable ranges, and the plate's own four layers (Expression, Blink, AutoBlink, Saccade) | yes |
| `rig/motion.py` | Every root animation: sustained loops (`ROW`), the 90 entries, the flashes, the nine idle beats, wander | yes |
| `rig/machine.py` | The `Avatar` state machine: ten layers, their states and transitions, and the file's hover/drag listeners | yes |
| `rig/layers.py` | Typed layer builder (`Layer` / `State` / `Exit` / `OnEnum` / `OnBool` / `OnTrigger` / `OnInput` / `Raw`). Validates targets, weights and exit-time-on-a-loop in Python instead of at runtime | rarely |
| `rml.py` | RML primitives: Rive property keys, view-model ids, easing tokens, XML builders (keyframes, animations, states, transitions). No mascot knowledge | rarely |
| `svgpath.py` | SVG path -> RML vertices (M/L/C/Z, evenodd, strokes; 8-cubic mirrored bodies, 4-vertex mouths) | rarely |
| `art/*.svg` | The locked art: `body-*.svg` (8 identities), one glyph per state, three mouths | via SPEC owner |
| `scene.rml` | Generated. Committed so diffs are reviewable. Never hand-edit. | no |
| `build/mascot.riv` | Built by the CLI; copied to `src/androidMain/res/raw/mascot.riv` (what the app loads) | no |
| `rive.yaml` | Push mapping: project 1882737 "oculair / Shared Project", file 2578084 "mascot" | no |
| `pull_editor.py` | Editor round trip: diff a `.rev` export (or converted dir) against `scene.rml` by animation / layer / node | when the artist edits |
| `sheet.py` | Contact sheets from screenshots (the review tool) | - |
| `onion.py` | Onion skins: several frames of one motion overlaid, older ones fainter (`--tint`, `--edges`), so arcs, spacing and overshoot read at a glance; `--diff` prints the per-pair % changed as a pop detector. Drives the CLI itself and caches frames | rarely |
| `timeline.py` | Read a curve without building: keyframes, easing and an ASCII plot per animation (`--list`, `--layers`) - the review tool when the editor is not available | rarely |
| `test_rig.py` | `python -m unittest test_rig`: regenerate parity, unique ids, resolvable state/animation references, the contract check, a `timeline.py` smoke | rarely |
| `SPEC.md`, `MOTION-REFERENCES.md` | Numbers and references from the design agent; §8 is the implementation map, §9 the human-touch patch (amplitudes, alphas, glyphs) the rig now follows | with them |
| `art/validation/` | The design agent's static proofs and `validate.py` (needs numpy, Pillow, CairoSVG); not part of the build | - |
| `RIVE-PLATFORM-POWER.md` | The design agent's platform brief; the audit below answers it | - |
| `ARTIST.md` | Handoff for the human art pass; the names the app depends on | keep current |

Tooling: Rive CLI 1.0.2 at `~/.rive/bin/rive.exe` (`rive docs`, `rive schema <Type>`), Python 3
with Pillow. The user has run `rive login`; `rive push` works from this directory.

## Which module to open

| The change | Where |
|---|---|
| A new idle or wander beat | the animation in `rig/motion.py`, its two ids from `alloc(3, ...)` in `rig/ids.py`, its state and weight in `_idle_layer` / `_wander_layer` in `rig/machine.py` |
| A new sustained state | the `STATES` vocabulary in `rig/ids.py` (append - the id ranges are indexed by that order), a `ROW` entry and a facing in `rig/motion.py`, a glyph in `rig/plate.py`'s `STATE_GLYPH` plus `art/glyph-<name>.svg`, then `RiveAvatarContract.kt` (`check_contract.py` enforces the match) |
| Body shape or geometry | `art/*.svg` first, then `rig/body.py` (paths, bones, weights, paints) |
| The plate: glyph, mouth, card, blink, saccade | `rig/plate.py` |
| Timings, amplitudes, waits, tints | `rig/constants.py` - almost nothing else should carry a number |
| What plays when: layers, transitions, blends | `rig/machine.py` (the plate's own layers are at the end of `rig/plate.py`) |
| The face's placement, turn or trails | `rig/face.py` |
| A new kind of XML (a property, an element) | `rml.py`, then use it from the rig |

## The loop

Every change, no exceptions - the user's standing instruction is "review your own work":

```bash
cd android-compose/avatar/renderer-rive/rive/mascot
R=~/.rive/bin/rive.exe
python -m unittest test_rig        # the cheap gate: regenerate parity, ids, references, contract
python gen_scene.py                # writes scene.rml
$R . --verify                      # 0 errors, 0 warnings, or stop
$R . --once                        # writes build/mascot.riv
# render what you changed, at the frames where it can go wrong
$R . --screenshot=/c/rive-spike/v2/e-5.png --data=state=listening --advance=5
python sheet.py /c/rive-spike/v2/sheet.png 6 /c/rive-spike/v2 e-1 e-3 e-5 e-8 e-12 e-20
# LOOK at the sheet (Read the png). Then ship:
cp build/mascot.riv ../../src/androidMain/res/raw/mascot.riv          # Android (R.raw.mascot)
cp build/mascot.riv ../../src/jvmMain/resources/mascot/mascot.riv      # desktop (classpath /mascot/mascot.riv)
python ../../native/rivdump/check_contract.py . ../../src/commonMain/kotlin/com/letta/mobile/avatar/rive/RiveAvatarContract.kt
$R push                            # new revision in the Rive workspace (see the rule below)
```

**Checks.** `python -m unittest test_rig` (about 15 s, standard library only) is the cheap gate
that does not need the CLI: it regenerates into a tempdir and compares against the committed
`scene.rml` with push-assigned ids stripped, asserts every id is unique, checks that every
transition target and `animationId` resolves, runs `check_contract.py`, and smoke-tests
`timeline.py`. Run it before `$R --verify`; a failing regenerate check means the committed file
is a hand-edit or a stale commit, not a code bug.

Two checks run before that without being asked: `rig/ids.py` raises on a duplicate id at import,
and `rig/layers.py` raises on a layer whose transition leaves its own layer, whose weight sits on
a non-random state, whose random state has fewer than two weighted ways out, or whose exit-time
transition hangs off a looping animation. A refactor that is meant to change nothing should also
survive a byte check against the committed file:

```bash
python gen_scene.py /tmp/check.rml
diff <(sed -E 's/ id="0:[0-9]+"//g' scene.rml) <(sed -E 's/ id="0:[0-9]+"//g' /tmp/check.rml)
```

**Review tools.** Three, none of which need the editor:

```bash
python timeline.py IdleBounce          # keyframes, easing and an ASCII plot per property
                                       # (--list every animation, --layers the state machine)
python onion.py --state listening --frames 10 --diff    # frames of one motion overlaid, older
                                       # fainter; --diff prints % changed per pair (a spike = a snap)
python sheet.py /c/rive-spike/v2/sheet.png 6 /c/rive-spike/v2 e-1 e-3 e-5 e-8 e-12 e-20
                                       # a contact sheet from screenshots you captured above
```

`--screenshot` starts the state machine with the given view-model data and advances N frames
at 60 fps, so `--advance=N` is "frame N of whatever the data triggered from idle". Use
`--data=state=<key>` for sustained states, `--data=success=true` / `--data=error=true` to fire the flashes, `--data=dragged=true`,
`--data=lookX=-1 --data=lookY=1 --data=mouthOpen=0.8` for the numbers, `--data=shape=drop`
for identity. Render a state past its entry (advance 40+) to see its loop, and frames
1/3/5/8/12/20 to see an entry. For a motion rather than a pose, `onion.py` captures and overlays
the frames itself; `--animation IdleBounce` plays a beat that normally hides behind a random wait
(it asks `gen_scene.py` for a `--solo` document, so `Avatar` itself is untouched).

The desktop demo renders the built file through the same native runtime the product uses:

```bash
cd android-compose
./gradlew --no-daemon -q :desktop:runRiveSpike \
  -PriveBridge=C:/rive-spike/bridge/rive_desktop_bridge.dll -PriveSelfTest=true
```

(The bridge DLL is built once per machine; see `../../native/desktop/README.md`. `-PriveSelfTest=true`
starts with the auto-cycle on.) The window is a review bench: auto-cycle switch, every state,
shape and colour, gaze from the cursor (or sliders) and mouth, the rig tunables above, and the
surround - page and frame grounds with a base colour plus draggable radial lights (edit switch,
`+ light`, drag the rings), frame shapes and sizes, and the mascot scaled inside the frame so it
can be padded or clipped. If the window dies on relaunch with a
Skiko D3D redrawer error, the previous instance was still exiting; launch again.

**Order matters: push before you commit.** `rive push` assigns ids to every object the
generator left unnamed and writes them back into `scene.rml` (that is how the next push updates
those objects in place instead of recreating them). Regenerating throws those ids away, so a
freshly generated `scene.rml` always shows a huge diff against the committed one - that is not
a change. Commit the file the push wrote. Objects the generator does name (`id="0:100"` etc.)
are stable across pushes and are what the artist's edits attach to.

**Push rule.** The CLI regenerates and pushes freely *until the artist makes their first edit in
the editor*. From then on the editor file is the source of truth and a CLI push would overwrite
it. Ask before pushing if you do not know whether that has happened.

**Pulling editor changes back - and the paywall.** The CLI (1.0.2) cannot download the cloud
file's current revision, and on the free plan the editor exports nothing either: `.rev` download
and `.riv` download are both behind a paid plan. So on this plan the editor is read-only for us:
push into it to look, never author in it, because an edit made there cannot come back and the
next `rive push` overwrites it. Author in the generator; review with `--screenshot` and
`sheet.py`; describe a change you want in editor terms (which animation, which keys, what
timing) and port it here.

If a paid plan ever lands, the loop is: File > Export > Download `.rev`, then

```bash
python pull_editor.py path/to/mascot.rev --write-report build/pull-report.md
```

which converts it with `rive create --from-rev`, strips push-assigned ids, and lists every
animation (with the keyed object/property whose keyframes moved), state-machine layer and named
node that differs from the committed `scene.rml` - the map for porting the edit into the
right `rig/` module (the table above says which). Regenerate, verify, screenshot, push: the edited ids are the generator's named
ones, so the push updates them in place.

## How the rig is put together

```
Mascot (root artboard, view-model "Avatar")
  Body ("0:100")       one 8-vertex path; vertices keyed per IDENTITY only (Shape layer, 240 ms morph)
                       paints bottom->top: Fill (bound to `color`), Shade, Gloss (opacity breathes), Tint (per state)
                       SoftEdge, Halo: faint feathered strokes of the same path behind it
  FacePlacement (250,262)
    Turn ("0:220")     keyed ONLY by the joystick's TurnX/TurnY: slides the plate +-70/+-24 px,
                       foreshortens it (scaleX 0.7 at the edges) and rotates/squeezes the Body
    Face ("0:200")     keyed by state motion (offset, rotation)
      Plate            NestedArtboard of the Plate component; inputs `expr` (NestedNumber "0:211"),
                       `blink` (NestedTrigger "0:212"); LookX/LookY/Open scrubbed by the numbers
    Trail1/2           ghost plates for the spin, opacity 0 unless keyed
  Facing ("0:221")     Joystick, x -> TurnX ("3:220"), y -> TurnY ("3:221"); property keys JX=299, JY=300

  Entity ("0:230")     wraps FacePlacement + BodyPlacement at (250,270); scaleX/Y bound to `tuneScale`
                       (0..1 -> 0.5..1.5) so the whole character scales about the body centre
  AutoLookX/Y          NestedRemapAnimations ("0:231"/"0:232") on the Plate, keyed per sustained
                       state (`GAZE` table): the default gaze life (+-6/+-4 px on the glyph wrapper),
                       additive with the host's lookX/lookY

Plate (component, input driven, ids "7:*")
  Wrapper nodes carry the bench tunables so state/look/blink keys never collide with them:
  MouthNode ("7:27", rest y +82) > mouths; GlyphScale ("7:26") > Glyphs Solo; PlateScale ("7:25") > Card, Shadow.
  Tunables are VM numbers 0..1 (0.5 = shipped) scrubbing pose ranges: tunePlate (x0.6..1.4),
  tuneGlyph (x0.4..1.6), tuneMouth (x0.5..1.5), tuneMouthY (42..122 px). Not in the app contract;
  the bench writes them and the numbers you like go back into SPEC as fixed values.
  Card 120 r27 + Shadow; Glyphs Solo ("7:22") with one shape per state glyph (7:30-7:41, dragged last),
  `activeComponentId` keyed per `expr` (KeyFrameId, hold); Mouth morph (7:23, vertex ids 7:70-73, keyed on Open at
  0/30/60), MouthO (7:43), FrownLine (7:42). Blink squashes Glyphs scaleY 3/2/5 frames.
  Layers: Expression (explicit matrix, instant cuts), Blink (trigger), AutoBlink (2.5-4.5 s).
```

Root state machine `Avatar`, layers in order:

| Layer | Drives | Design |
|---|---|---|
| `Shape` | body vertices | enum `shape`, 240 ms |
| `Expression` | body/face motion, tint, gloss pulse, `expr`, facing | one `State<X>` loop per sustained state; **every change runs `Enter_<from>_<to>`** (90 one-shots, ids 3:250-3:339, nodes 3:350-3:439): plate blink at frame 0, `expr` flipped at frame 3 under the shut eye, facing eased to the target. The SPEC §3 pairs carry designed motion and cut into the target; generic ones hold and blend in over <=120 ms. No AnyState. |
| `Breath` | gloss opacity | loop |
| `Blink` | plate blink | contract trigger |
| `Hover` | perk-up: tall stretch, face lift, tilt of interest; attentive hold (beat tempo) | Rest -> Perk -> Held -> Rest on `hovered` (file listeners); exit blends 220-260 ms |
| `Flash` | success/error | triggers; success 800 ms hop + spin with trails, error 600 ms shake; self-returning |
| `Drag` | `expr` dragged | boolean + dragStart/dragEnd listeners |
| `IdleVariety` | face, body, joystick | four waits of unequal length (6.1 / 8 / 10.7 / 14 s) picked at random, then one of nine beats by weight (glance, tilt, look-around, shift, stretch, sigh, shiver, wobble, bounce); beats blend in 160 ms / out 320 ms over Breath and the host turn. `BEAT_TEMPO` 1.4 stretches every beat |
| `Wander` | joystick | four waits of unequal length (9.3 / 12 / 17.5 / 24 s) at random, then glance 55 % / peek 35 % / spin 10 %; beats blend in 200 ms / out 320 ms |

Facing per state (`sustained_facing()`): idle -0.15, listening 0 (square-on), thinking -0.6/-0.2,
speaking 0.15, error -0.3/0.25, sleeping 0.4/0.5, degraded 0.5/-0.1, the rest ~0.

Id namespaces (all ids must be numeric `client:object`, unique, object 0 reserved): `0:*` root
objects, `3:*` root animations/nodes (60-77 shapes, 100-140 states, 160-244 misc layers,
250-439 entries), `7:*` Plate. Add new ranges above 3:440 and note them here.

## Rive gotchas (each of these was learned the hard way)

- Rotations are **radians**; `LinearAnimation.duration` is **frames**; `StateTransition.duration`
  is **ms**. `rig/constants.py` has `rad()` and `frames()`.
- Keyframes default to `hold`. Motion needs `interpolationType="cubic"` plus a nested
  `CubicEaseInterpolator`; the bezier on a key shapes the segment *leaving* it.
- First child of a node draws on top; the later paint in a shape draws on top.
- `Feather` on a Fill renders nothing through the CLI; feather strokes only. (The editor can
  feather fills - that is on the artist list.)
- A view-model-driven state machine inside a nested artboard never fires. Drive components with
  inputs (`NestedNumber` / `NestedTrigger`) keyed from the root's animations.
- `NestedRemapAnimation.time` is a 0..1 fraction, not frames.
- **AnyState transitions are evaluated before a state's own transitions**, so an AnyState
  fan-out swallows any designed pair. Worse, an AnyState transition whose condition still holds
  re-enters the current state and self-blends it - that showed up as every glyph fading in
  over 160 ms, even at load. Both machines now use explicit per-state matrices.
- A nested number keyed by two root states **interpolates during the root cross-blend**, so a
  component condition on it must be a band (`>= v-0.5 && < v+0.5`), never an exact match.
- Every pose must key every mutable property, or the last state's value leaks through.
  Unkeyed properties in a one-shot hold their previous value until the next blend - the generic
  entries rely on this deliberately.
- A colour bound to the view model only delivers after a runtime write; the desktop bridge
  creates the default instance (`createDefaultViewModelInstance`) and the surfaces call
  `applyIdentity` on load. A scene that never writes `color` draws a black body.
- Placement and keyed motion must live on different nodes (a keyed `x=0` overwrites placement) -
  hence `FacePlacement > Turn > Face`.
- The CLI compiles every `*.rml` in the directory; scratch fragments go elsewhere.
- PowerShell `>` writes UTF-16; redirect from bash.

## Reviewing motion

Screenshots are the only ground truth the CLI gives. Before claiming anything moves correctly,
render the frames where it could fail and read the sheet: a transition at 1/3/5/8/12/20, a loop
at 0/25/50/75 %, a flash at 10/37/70/100 %. Things that have slipped through before: glyphs
fading when they should cut, brows colliding with eyes, rotations in the wrong unit, a
placement overwritten to 0, glow drowning the body, the eye too small against the sheet.

Sheets from previous rounds are in `C:/rive-spike/v2/` on the Windows box (not committed).

## Platform audit (against `RIVE-PLATFORM-POWER.md` §3)

Must:

- [x] App-facing names are View Model properties on `Avatar`; no legacy inputs at the root
- [x] `state` keys match `RiveAvatarContract.stateKey` (`check_contract.py` enforces it)
- [x] `success` / `error` / `blink` are triggers; `Flash` self-returns
- [x] `color` bound to the body fill and the halo/soft-edge strokes; no identity hex
- [x] `shape` enum wired as a vertex morph (Shape layer)
- [x] `mouthOpen` / `lookX` / `lookY` scrub pose-range timelines through range-mapper converters (no keyframe hops); facing is a Joystick
- [x] `dragged` / `hovered` written by file listeners and by the app
- [x] One SM `Avatar`, ten layers
- [x] Glyphs in a `Solo` (keyed `activeComponentId`), mouths opacity-switched per SPEC §4
- [x] `check_contract.py` passes on the shipped `.riv`
- [x] Feathering is stroke-only and rendered through the Rive Renderer on both targets

Should / deliberately not:

- [x] Plate is a component with an input boundary (`expr`, `blink`). Body is not a component: its vertices are keyed by the root's Shape layer, and a nested body would need its own input plumbing for no gain.
- [ ] Nested view model for the face — **no**: a VM-driven machine in a nested artboard never fires; inputs are the working pattern.
- [x] Converters: range mappers on look/mouth. **No interpolator**: SPEC §4's host envelope owns smoothing.
- [ ] Blend states — **not used**: the scrubbed ranges are the equivalent and verify headless. Revisit if the editor pass wants additive look layers.
- [ ] Scripts / test scripts — not used; random waits are two-state alternations, which the runtime handles natively.
- [ ] Transition actions — not needed; the error flash lands on the sustained error pose by construction.
- [ ] `reduceMotion` — needs a contract property and a host write; follow-up bead.
- [x] `rive inspect . --json` is the structural check; `check_contract.py` reads it.

## Gaze and attention: what the rig does, what the host does

References, read and applied (numbers below come from them):
- Pan et al., *Realistic and Interactive Robot Gaze*, Disney Research, IROS 2020 - attention with
  curiosity + habituation (eq. 2: interest decays while attended, restores while not), eyes lead the
  head, per-actuator bandwidth (eyes fast, head slow), mutual gaze as saccades between eyes and
  nose every 0.1-0.5 s, a "read" show with the head sweeping the line.
- Trutoiu, Carter, Matthews, Hodgins, *Modeling and Animating Eye Blinks*, Disney Research 2011 -
  blinks are asymmetric (fast close, slow decelerating open), ~250-300 ms reads most natural.
- Lee, Badler, Badler, *Eyes Alive*, SIGGRAPH 2002 - saccade direction statistics (down 20 %, up
  18 %, left 17 %, right 16 %, diagonals 6-8 %), small magnitudes dominate, 50-100 ms minimum
  inter-saccade interval, blinks accompany large gaze shifts.
- Lasseter, *Principles of Traditional Animation Applied to 3D*, SIGGRAPH 1987 - anticipation,
  overshoot, arcs, overlapping action, slow-in/slow-out.

**Rig (always on, no host needed):** still resting fixation per state (`GAZE`); `Saccade` layer
in the plate hops to a new fixation on random waits with the Eyes Alive direction weights, hold
0.7-2.4 s, parked asleep; blink 4/1/10 frames with a fast close (`STD_DECEL`) and slow open
(`EMPH_DECEL`), entries flip the glyph at frame 6 while shut, no auto-blink asleep; turns are arcs
(`Arc` node lifts 7 px through the centre), roll and recede; anticipation/overshoot/elastic curves.

**Host (`GazeDirector` in `avatar/core`, ticked from `MascotEntry` / `MascotLive`; the bench
calls the same class):**
- straight ahead is the exception: own thoughts and the AWAY target park the eyes off-axis
  (|x| 0.45-0.85, a little up) most of the time; PEER looks at another live mascot on screen
  (the registry publishes every mascot tile; `GazeWorld.peers`); USER is the only centred look
- every look has a nameable target: own thoughts, you, the cursor, the input (typing), the
  timeline (reading); a per-state plan with weights, dwell and gap (`GazePlan`, lifted from the
  bench `GAZE_PLAN`)
- habituation: interest per target, -1/4 s while attended, +1/12 s otherwise; plan weights scale
  with it; a cursor only grabs attention while its interest is above 0.3
- eyes lead, head follows after ~350 ms on an under-damped spring (omega 8.5, zeta 0.72 — spike
  code, not the older README 11 / 0.5) through `turnX`/`turnY`; a head turn > 0.4 fires `blink`
- looking at you: saccade between eyes and nose every 100-500 ms; reading: uneven left-to-right
  steps, return sweep, three lines, the head sweeping slowly along the line; typing: ride the caret
- hosts publish the composer and message-list window rects (`GazeWorld.fromWindow` /
  `Modifier.mascotGazeTarget`); missing rects skip INPUT/TIMELINE. Combined look+saccade
  card clamp is letta-mobile-kkjyd (SPEC §10.4), not this director.

## SPEC section 10 (Astra Max) as built - test rig, pending the user's review

- **Pupil overlay: built, reviewed, dropped.** The assembly (`PupilOverlay` "7:29" > `PupilRoot`
  "7:96" clipped to `Iris` "7:95"; `Wave` "7:97", `Core` "7:98", `Catchlight` "7:99"; wave
  vertices 7:200-7:204) is still in the file at opacity 0 for the art pass, and `PUPIL = {}` in
  the generator means no state keys it. Built exactly to spec (keyed four-phase wave, clipped
  core/catchlight, look/saccade/blink co-keyed, +-1.5/+-1 parallax) and reviewed at hero size
  and in the app: the 8 px stroke wave read as a bar or ring welded to the core at every size,
  and even a core-only bobbing pupil lost to the pure glyph. To revive it set `PUPIL` per state.
- **Speaking has no mouth** (product call): `STATE_MOUTH` keeps the mouth for dragged,
  waitingInput and error only; the glyph and the head carry speech.
- **Deformation**: three RootBones inside `Body` (Crown 0:240 at (0,-150), Middle 0:241, Base
  0:242 at (0,+150)), a Skin with three Tendons in every body path (body, SoftEdge, Halo), a
  CubicWeight per vertex from its rest y on the default body. Success / error / dragged key the
  bones (`bone_pose`: X = S-L / S / S+L, Y = 1/S, crown/base X = -+150 H) and hold the inflate
  node at 1. Deviations: weights are per vertex index, so the other seven identities share the
  default body's (vertex order matches; positions nearly do); no area correction q (bones
  cannot); breath stays at the user's 1.06 / 6500 ms rather than the spec's 1.03 / 4600.
- **Working glyph**: art only; not wired (z4b83).

## Host rules (for the identity slice, 1zti3)

- Write `shape`, `color` and `state` **before the first `advance`/render**, or the first frame is
  a black body in the default pose (the colour bind only delivers after a runtime write). The
  spike does this by writing in the same effect that loads the runtime; production surfaces
  should hold the node until identity has been applied - no startup flash.
- Fit is `contain` on a fixed 500x500 artboard; the mascot is an element, not a layout, so the
  responsive `layout` fit does not apply.
- The interaction logic (hover, drag, blink, wander, entries) lives in the file. The host only
  writes the contract properties; it must not try to sequence transitions itself.

## Open items

- `-small` profile (SPEC) needs a host size signal the contract does not carry.
- Drag tilt needs pointer velocity the file cannot see (0 degrees now).
- The idle glance moves the plate, not the glyph (the root cannot key a nested node); the
  glyph-only glance belongs in the editor pass.
- Wander is not gated during `speaking`/`sleeping`; decide if it should be.
- Kotlin identity slice (`1zti3`): picker, persistence, chat-header hero.
- `WORKING` (tool execution) as a state distinct from `THINKING` - decided as a v2 gap, filed as
  its own bead; needs `glyph-working.svg` (+ `-small`) from the design side, a sustained state
  with square-on facing and Wander gated off, and the director wired to tool-call events.
