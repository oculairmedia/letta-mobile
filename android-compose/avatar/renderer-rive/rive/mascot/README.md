# Mascot rig: working notes for whoever picks this up cold

The Letta agent mascot is a Rive file generated from Python. This page is the operating manual:
what the files are, the edit-verify-review-ship loop, the Rive gotchas that cost days, and the
state of the rig. The design intent lives in `SPEC.md` (numbers) and `MOTION-REFERENCES.md`
(why); the illustrator's handoff is `ARTIST.md`; the codebase plan is `../../MASCOT.md`.

Beads: `letta-mobile-kh094` (this asset), `letta-mobile-1zti3` (identity picker slice),
`letta-mobile-bn0y6` (orbs everywhere), `letta-mobile-0s5bi` (the native desktop spike).

## Files

| Path | What | Edit? |
|---|---|---|
| `gen_scene.py` | The generator. Structure, ids, timings, state machine. **Source of truth.** | yes |
| `svgpath.py` | SVG path -> RML vertices (M/L/C/Z, evenodd, strokes; 8-cubic mirrored bodies, 4-vertex mouths) | rarely |
| `art/*.svg` | The locked art: `body-*.svg` (8 identities), one glyph per state, three mouths | via SPEC owner |
| `scene.rml` | Generated. Committed so diffs are reviewable. Never hand-edit. | no |
| `build/mascot.riv` | Built by the CLI; copied to `src/androidMain/res/raw/mascot.riv` (what the app loads) | no |
| `rive.yaml` | Push mapping: project 1882737 "oculair / Shared Project", file 2578084 "mascot" | no |
| `sheet.py` | Contact sheets from screenshots (the review tool) | - |
| `SPEC.md`, `MOTION-REFERENCES.md` | Numbers and references from the design agent; SPEC §8 is the implementation map | with them |
| `RIVE-PLATFORM-POWER.md` | The design agent's platform brief; the audit below answers it | - |
| `ARTIST.md` | Handoff for the human art pass; the names the app depends on | keep current |

Tooling: Rive CLI 1.0.2 at `~/.rive/bin/rive.exe` (`rive docs`, `rive schema <Type>`), Python 3
with Pillow. The user has run `rive login`; `rive push` works from this directory.

## The loop

Every change, no exceptions - the user's standing instruction is "review your own work":

```bash
cd android-compose/avatar/renderer-rive/rive/mascot
R=~/.rive/bin/rive.exe
python gen_scene.py                # writes scene.rml
$R . --verify                      # 0 errors, 0 warnings, or stop
$R . --once                        # writes build/mascot.riv
# render what you changed, at the frames where it can go wrong
$R . --screenshot=/c/rive-spike/v2/e-5.png --data=state=listening --advance=5
python sheet.py /c/rive-spike/v2/sheet.png 6 /c/rive-spike/v2 e-1 e-3 e-5 e-8 e-12 e-20
# LOOK at the sheet (Read the png). Then ship:
cp build/mascot.riv ../../src/androidMain/res/raw/mascot.riv
python ../../native/rivdump/check_contract.py . ../../src/commonMain/kotlin/com/letta/mobile/avatar/rive/RiveAvatarContract.kt
$R push                            # new revision in the Rive workspace (see the rule below)
```

`--screenshot` starts the state machine with the given view-model data and advances N frames
at 60 fps, so `--advance=N` is "frame N of whatever the data triggered from idle". Use
`--data=state=<key>` for sustained states, `--data=state=success` / `error` for the flashes,
`--data=lookX=-1 --data=lookY=1 --data=mouthOpen=0.8` for the numbers, `--data=shape=drop`
for identity. Render a state past its entry (advance 40+) to see its loop, and frames
1/3/5/8/12/20 to see an entry.

The desktop demo renders the built file through the same native runtime the product uses:

```bash
cd android-compose
./gradlew --no-daemon -q :desktop:runRiveSpike \
  -PriveBridge=C:/rive-spike/bridge/rive_desktop_bridge.dll -PriveSelfTest=true
```

(The bridge DLL is built once per machine; see `../../native/desktop/README.md`. The self-test
cycles every state and identity and prints each step.) If the window dies on relaunch with a
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

Plate (component, input driven, ids "7:*")
  Card 120 r27 + Shadow; Glyphs Solo ("7:22") with one shape per state glyph (7:30-7:40),
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
| `Hover` | face wiggle | Rest -> Wiggle -> Held -> Rest on `hovered` (file listeners) |
| `Flash` | success/error | triggers; success 800 ms hop + spin with trails, error 600 ms shake; self-returning |
| `Drag` | `expr` dragged | boolean + dragStart/dragEnd listeners |
| `IdleVariety` | face | random 4-7 s waits, a glance |
| `Wander` | joystick | random 6-12 s waits, then glance 55 % / peek 35 % / spin 10 % |

Facing per state (`sustained_facing()`): idle -0.15, listening 0 (square-on), thinking -0.6/-0.2,
speaking 0.15, error -0.3/0.25, sleeping 0.4/0.5, degraded 0.5/-0.1, the rest ~0.

Id namespaces (all ids must be numeric `client:object`, unique, object 0 reserved): `0:*` root
objects, `3:*` root animations/nodes (60-77 shapes, 100-140 states, 160-244 misc layers,
250-439 entries), `7:*` Plate. Add new ranges above 3:440 and note them here.

## Rive gotchas (each of these was learned the hard way)

- Rotations are **radians**; `LinearAnimation.duration` is **frames**; `StateTransition.duration`
  is **ms**. `gen_scene.py` has `rad()` and `frames()`.
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

## Open items

- `-small` profile (SPEC) needs a host size signal the contract does not carry.
- Drag tilt needs pointer velocity the file cannot see (0 degrees now).
- The idle glance moves the plate, not the glyph (the root cannot key a nested node); the
  glyph-only glance belongs in the editor pass.
- Wander is not gated during `speaking`/`sleeping`; decide if it should be.
- Kotlin identity slice (`1zti3`): picker, persistence, chat-header hero.
