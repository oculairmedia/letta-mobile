# Baken mark rig: working notes for whoever picks this up cold

The animated Baken logomark (03 Basalt Planes) is a Rive file generated from Python, a sibling of
the mascot rig in `../mascot/`. This page is the operating manual: what the files are, the
edit-verify-review-ship loop, and what the file promises the host. The design intent lives in the
brand kit (2026-11-22): `motion/BAKEN_MARK_RIVE_SPEC.md` (the state table and contract),
`motion/baken-motion-lab.html` (the reference keyframes), `PALETTE.md` (01 North Sea Buoy).

The mark owns **app and connection states only**. It never animates thinking, working or any other
turn activity - the mascots own all of that (`mascot.riv`). While a mascot is thinking or working
the mark stays calm `idle`; the two never animate competitively on the same turn.

## Files

The generator is `gen_scene.py` plus the `rig/` package. It reuses the mascot rig's tooling rather
than copying it: `rig/__init__.py` lets `rig.layers`, `rig.seams` and `rig.chart` fall through to
`../mascot/rig/`, and puts `../mascot` on `sys.path` (after this directory) for `rml`, `svgpath`,
`timeline` and `rivecli`. Nothing in the mascot rig was changed.

| Path | What | Edit? |
|---|---|---|
| `gen_scene.py` | Assembly only: the artboard shell, the enums and view model, the document; `--solo`, `--probe` and `--backdrop` variants. **Entry point.** | yes |
| `rig/ids.py` | Every named Rive object id and the vocabulary (`STATES`, `THEMES`). Uniqueness checked at import. **Existing ids are frozen**: append. | add only |
| `rig/constants.py` | The design numbers as data: palette, light geometry, blend times, and every keyframe track in the lab's own percentages (`POSE`, `LIGHT`, `BOOT`, `SUCCESS`, the calm twins). Almost nothing else carries a number | yes |
| `rig/stones.py` | The drawing: the two stones from `art/*.svg`, re-based on their ground contacts, their overlays, the sheen, the bounce and seam lights | rarely |
| `rig/motion.py` | Every animation, built from the constants tables with CSS `@keyframes` semantics | yes |
| `rig/machine.py` | The `BrandMark` state machine: five layers, conditional entries | yes |
| `rig/contract.py` | The `BrandMark` view-model contract as data; `gen_scene.py` emits the view model from it, `test_contract.py` holds the built file to it | when the contract changes |
| `rig/probe.py` | The probe table (which node properties telemetry reads) and `inject()` | add probes |
| `art/anchor.svg`, `art/pebble.svg` | The brand kit's two paths, unchanged (from `svg/baken-mark-dark.svg`) | via the brand kit |
| `scene.rml` | Generated. Committed so diffs are reviewable. Never hand-edit | no |
| `build/baken_mark.riv` | Built by the CLI; copied to the two shipped paths below | no |
| `rive.yaml` | Build config. **No `push:` section**: there is no Rive cloud file for the mark | no |
| `scenarios.py` | Named driver sequences (the mascot's `Scenario` builders): every state, boot, success, hover, a theme, and the reduceMotion legs | add scenarios |
| `probe.py` | Pose telemetry: the mascot's `probe.py` functions over this rig's probed document. `--golden` re-records | rarely |
| `goldens/*.json` | Committed motion signatures, diffed by `test_probe.py` | on purpose |
| `review.py` | The review sheets in `review/`: a contact strip plus an onion skin per motion, a reduceMotion sheet, a theme grid, and `--gif` | - |
| `review/*.png`, `review/baken-mark.gif` | The committed sheets the PR shows | regenerate |
| `test_rig.py` | `python -m unittest test_rig` - no CLI: regenerate parity, references, sibling key sets, grounded geometry, closed loops, the reduceMotion rules | rarely |
| `test_contract.py` | `python -m unittest test_contract` - no CLI: the shipped `.riv` (both copies, byte-identical) is exactly `rig/contract.py`, through `native/rivdump/rivfile.py` and `check_contract.py`'s file checks; drift fails and names itself; this README's contract table matches | rarely |
| `test_probe.py` | `python -m unittest test_probe` - needs the CLI: every golden, reduceMotion holds still in every state, the calm one-shots change light only | rarely |

Tooling: Rive CLI 1.0.2 at `~/.rive/bin/rive.exe` (`RIVE_BIN` overrides), Python 3 with Pillow
for `review.py`.

## The loop

```bash
cd android-compose/avatar/renderer-rive/rive/baken-mark
R=~/.rive/bin/rive.exe
python -m unittest test_rig                  # the cheap gate (also checks scene.rml parity)
python gen_scene.py                          # writes scene.rml
$R . --verify                                # 0 errors, 0 warnings, or stop
$R . --once                                  # writes build/baken_mark.riv
cp build/baken_mark.riv ../../src/androidMain/res/raw/baken_mark.riv                # Android: R.raw.baken_mark
cp build/baken_mark.riv ../../src/jvmMain/resources/baken-mark/baken_mark.riv       # desktop: /baken-mark/baken_mark.riv
python -m unittest test_rig test_contract test_probe
python probe.py connecting                   # numbers before pixels
python review.py --gif                       # then LOOK at review/*.png
```

The scripts, by path (all run from this directory): the generator `gen_scene.py` (with
`rig/__init__.py`, the bootstrap onto the mascot's shared tools, and `rig/ids.py`, the frozen ids,
under it); the checks `test_rig.py` and `test_contract.py` (no CLI) and `test_probe.py` (the Rive
CLI); the tools `probe.py`, `scenarios.py` and `review.py`.

The build is deterministic: the same `scene.rml` and CLI give the same bytes, so both shipped
copies are always plain `rive . --once` output. `test_contract` fails if they differ from each
other or from `build/baken_mark.riv`.

A changed motion is re-goldened on purpose, with the diff in the commit:
`python probe.py <scenario> --golden` (the list is `scenarios.GOLDEN`).

## How the rig is put together

```
BakenMark (artboard 500x500, view model BrandMark, state machine BrandMark)
  Pebble  Base (335,420) > PebbleLean > PebbleEvent > PebblePose > PebbleTone
            PebbleBloom   success's warm flash             (first child draws on top)
            PebbleWarm    ambient's warming
            PebbleDim     degraded's slate-rust
            PebbleStone   the accent fill
  Anchor  Base (162.5,420) > AnchorLean > AnchorEvent > AnchorPose > AnchorTone
            SheenClip > Sheen   loading's diagonal sheen, clipped to the stone
            AnchorBounce        the pebble's warm light reflected on the right flank
            AnchorStone         the primary fill
  Seam (240,335), squeezed 28:38 > SeamPose > SeamGlow     the light inside the channel seam
```

The art is the brand kit's 200x200 drawing at x2.5, so the flush ground (`y=168`) lands at 420 and
the pivots are the spec's contact points. Every node of a stone's chain sits on its ground contact,
so every rotation pivots on the point the stone stands on: no floating, no rings, no detached dots.
The only lights are painted on a stone or glow inside the seam between them.

**One node per layer.** Placement (Base), hover (Lean), the one-shots (Event), the state loop
(Pose) and the brightness (Tone) each own a node, and no two layers key the same property. So the
layers compose instead of fighting, and a one-shot that ends hands back nothing: boot and success
end on the identity they start from, and no lower layer keys an Event node.

| Layer | Keys | Design |
|---|---|---|
| `Theme` | every colour: both fills, the overlay fills, the light stops | `theme`, 280 ms blend |
| `Pose` | both Pose nodes: x, y, rotation, scaleX/Y | one loop per state and a static `PoseCalm_<state>` twin; state changes blend 450 ms, into / out of a twin 300 ms |
| `Light` | both Tones, the dim / warm overlays, bounce, sheen, seam | one loop per state (ambient's 3.4 s runs against the stones' 5.2 s) and a static twin; 320 ms blends |
| `Event` | both Event nodes and the bloom | `boot` / `success` from `EventRest`, self-returning; with `reduceMotion`, `BootCalm` (a fade) and `SuccessCalm` (a bloom) |
| `Hover` | both Lean nodes | `hovered`: a 2 deg lean, 240 ms in, 320 ms out |

**Conditional entries.** Theme, Pose, Light and Event start in the state the view model already
asks for, rather than in a default they then blend away from: a mark that opens `connecting` is
parted from frame 0, a `light` mark is never dark for 280 ms, and a `boot` fired before the first
advance starts the entrance from nothing. A `boot` fired on a mark that is already showing goes
through `EventRest` with a 100 ms blend (the minimum transition time), so it never pops.

The layers are built with the mascot's typed builder (`rig.layers`), so the blend policy is
enforced in Python at generation: no transition under `MIN_TRANSITION_MS` (100 ms) moves a pose,
no exit-time transition hangs off a loop, every target is in its own layer.

## Contract

The host writes the `BrandMark` view model (`rig/contract.py` is the source; `test_contract.py`
holds this table to it):

| Property | Type | Meaning |
|---|---|---|
| `state` | enum | `BakenMarkState`: the sustained app / connection state (below) |
| `theme` | enum | `BakenMarkTheme`: `dark`, `light` (01 North Sea Buoy) or the one-ink `monoDark` / `monoLight` |
| `boot` | trigger | the splash entrance: the anchor seats, the pebble rolls in and nests into the seam (1320 ms) |
| `success` | trigger | the peer-lock / boot-ready settle: weighted dip, low lift-and-tuck, warm bloom (1150 ms) |
| `reduceMotion` | boolean | no loops and no hops; each state is a static parting or brightness (parity with `Avatar.reduceMotion`) |
| `hovered` | boolean | pointer over the mark (desktop): a 2 deg lean perk |

`BakenMarkState`, in order (Rive delivers an enum as its index, so the order is part of the
contract):

| Key | Host signal | Motion and light (spec table, lab keyframes) |
|---|---|---|
| `idle` | connected and healthy; also whenever a mascot is thinking or working | 5200 ms micro weight-shift, anchor -0.85 deg / pebble +1.35 deg, seated |
| `loading` | app / surface hydration, only with no mascot on screen | 1500 ms stone-cradle weight transfer and a diagonal sheen across the anchor |
| `connecting` | Iroh reconnect / QR pairing; fire `success` on lock | parted (-5.5 / +8 art px), drawing in and out over 2200 ms, warm light pulsing inside the seam |
| `degraded` | host / Iroh connection lost | pebble rolled away (+10 px, +10 deg) on its outer edge, muted slate-rust; anchor dimmed to 62 % |
| `ambient` | an unread background / ambient update | grounded and still; the pebble warms and reflects on the anchor's right flank (3400 ms) |

`BakenMarkTheme`: `dark`, `light`, `monoDark` (one dark ink, for light grounds), `monoLight` (one
pale ink, for dark grounds). In the mono themes there is no hue to warm, so every state reads
through luminance only.

**Host rules.**
- Write `theme` and `state` (and `reduceMotion`) **before the first advance**; fire `boot` then
  too if the mark is the splash. The layers enter straight into what is asked for.
- Fit is `contain` on the fixed 500x500 artboard, like the mascot.
- `connecting` and `degraded` come from the host connection state; `success` is fired once on
  peer-lock. A `success` fired during `boot` is dropped (both are Event-layer one-shots).
- `loading` is for hydration with no mascot on screen. Never put the mark in a state because a
  turn is running.

## reduceMotion

With `reduceMotion` on, `Pose` and `Light` blend (300 ms) to each state's calm twin: a single
static pose - `connecting` keeps its parting, `degraded` its rolled-away pebble and dimming,
`ambient` a steady warmth, `loading` a brighter bounce - and nothing loops. `boot` becomes a 400 ms
fade and `success` a 700 ms warm bloom: brightness only, no seat, roll, dip or lift. The hover lean
is not gated (the mascot's precedent: it is small and pointer-driven).

`test_rig.ReduceMotion` (no CLI) checks the authored twins are single static poses and the calm
one-shots key no transform; `test_probe.ReduceMotion` runs ten seconds of every state and checks
nothing moves from frame 1 on, that each plain state does move (the control), and that the five
calm pictures are all different.

## Reviewing motion

Numbers first: `python probe.py <scenario>` prints a sparkline and a motion signature per probed
property in one headless run. Telemetry frame 0 is the authored default before the first advance
(the mascot README's gotcha), so read a one-shot from frame 1; boot's `opacity 1 -> 0` between
frames 0 and 1 is that, not a pop. Then pictures: `python review.py` writes a contact strip and an
onion skin per motion (`review/<motion>.png`), the reduceMotion sheet and the theme grid. The theme
grid renders through `gen_scene.py --backdrop`, which paints the theme's background inside a
throwaway variant, because the CLI's screenshots have an opaque ground and the translucent lights
would otherwise composite on the wrong colour.

## Deviations from the spec

- `boot` is a trigger, not a `state` key: it is a one-shot entrance, and the spec's own
  description is one. `BakenMarkState` is `idle loading connecting degraded ambient`.
- Colour is a `theme` enum instead of free `primaryColor` / `accentColor` properties: the palette is
  WCAG-verified as pairs, the light stops and the warm / dim overlays have to change with it, and a
  single enum keeps all of it in the file. The mono variant is two of its keys.
- The boot entrance stops at 60 % of the lab's 2.2 s loop (1320 ms), where the lab's motion ends.

## Open items

- CI does not run these tests yet: `.github/workflows/mascot-rig.yml` runs only the mascot's
  `test_contract` / `test_rig`. Adding `baken-mark`'s two CLI-free suites there is a follow-up
  outside this directory.
- The app does not load the mark yet: wiring `state` to the host connection state is a follow-up
  (no Kotlin contract exists for it yet; when one does, add an app-side check like the mascot's
  `RiveAvatarContract.kt` check).
