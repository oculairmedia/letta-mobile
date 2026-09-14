# Motion pipeline: how Disney's paper tools fit the rig, and how to collapse the loop

Status: plan, 2026-09-14. Companion to README.md (the loop as it is today) and MASCOT.md.
The archival source is the Disney practice summarised in the technical report the product owner
provided: Graham's action analysis (1935-37), Thomas & Johnston's codification (1981), Stanchfield's
lectures, timing charts, the exposure sheet, pose-to-pose, the pencil test and the sweatbox.

## 0. Two facts established today

1. **Pose telemetry works.** A `DataBindContext` with `direction="true" twoWay="true"` on a node
   property writes that property back into a view-model number every frame, and
   `rive <dir> --data-dump=- --data-dump-every=N` prints it as JSON lines. One headless run (about
   one second) returns the real, mixed, post-state-machine value of any node property for every
   frame of a scenario. No rendering, no screenshots. This is the pencil test as numbers.
2. **It found a bug on its first run.** The success hop keys a 48 px rise on Body and Face
   together (SPEC 2). At runtime Face rises (peak -47.9 px, frame 20) and Body does not (it shows
   the breath, -0.2 px): the Flash layer's Body keys lose to the Expression layer's. A screenshot
   at frame 18 agrees. Filed as a bead; every one-shot that keys a node a sustained loop also keys
   is suspect until probed.

Everything below is built on (1). The loop's floor is the CLI (verify 1.9 s, a screenshot 2.2 s);
the plan spends that floor once per scenario, not once per frame, and spends it on numbers before
pixels.

## 1. Where each Disney tool lands in the package

```
rml.py            XML primitives, keys, easings
rig/ids.py        ids (frozen, unique, alloc by name)
rig/constants.py  vocabulary, design numbers
rig/chart.py      NEW  timing charts: extremes + breakdowns + spacing -> keys; keys -> chart
rig/body.py rig/plate.py rig/face.py       geometry
rig/motion.py     the animation library, authored with charts
rig/seams.py      NEW  the seam ledger and the blend policy
rig/layers.py     typed layers; Layer.rml() applies the blend policy
rig/machine.py    the state machine
rig/probe.py      NEW  telemetry: which node properties to expose, injected by `gen_scene.py --probe`
scenarios.py      NEW  named driver sequences (state entries, triggers, hover, beats via --solo)
gen_scene.py      assembly; --solo <anim>; --probe
tools: timeline.py (curves, layers)  xsheet.py NEW (exposure sheet)  probe.py NEW (run a
       scenario, collect telemetry, signatures)  onion.py (onion skin)  sheet.py (contact sheet)
       review.py NEW (one card per motion)  test_rig.py (invariants + goldens)
```

| Disney tool | Here | Reads | Writes |
|---|---|---|---|
| Timing chart | `rig/chart.py` | extremes, breakdowns, spacing pattern | keyframes; and the reverse, a chart drawn from any curve or from telemetry |
| Exposure sheet | `xsheet.py` | scene.rml + a scenario (+ telemetry) | frames down, levels across, K / B / - marks, deltas, seams |
| Pencil test | `probe.py` | a scenario | per-frame pose JSON, motion signature |
| Sweatbox | `review.py`, `onion.py`, `sheet.py`, the app | telemetry + screenshots | one review card per motion; the human look |
| Halving principle | `chart.py` | two keys and an ease | breakdown at the weighted midpoint, then the in-betweens |

### Timing charts (`rig/chart.py`)

A beat is authored as poses and spacing, the way the chart says it, not as beziers hoped to match:

```python
Chart(extremes=[(0, 0), (22, -48)], breakdown=(8, -40), spacing="ease-in")   # slow-in to the target
Chart(extremes=[(0, 1.0), (14, 0.93)], spacing=[0.5, 0.75, 0.875])           # explicit ticks (halving)
```

`Chart.keys()` returns the keyframe list `motion.py` already consumes, so nothing downstream
changes. The in-betweens are explicit keys at the tick positions (Graham: spacing IS the weight),
with a smoothing bezier only between ticks. The reverse, `Chart.of(keys)` / `Chart.of(telemetry)`,
samples a curve back into ticks so `timeline.py --chart` and the review card draw the classic
margin chart: `[1] (5) - 7 - [9]`, ticks along a line. Uniform ticks are the tell for floating.

### The exposure sheet (`xsheet.py`)

```
python xsheet.py enter-listening              # a scenario from scenarios.py
python xsheet.py --animation IdleBounce       # one animation, via --solo

frame  driver          Body.y   Face.y   Face.rot  Plate.expr  Placement.sx  Joystick.x
  0    state=listening [K] 0    [K] 0    [K] 0.0   idle        [K] 1.00      -0.15
  1                     -  0    -  +1    -  +0.5
  ...
  6    blink flip       (B) +3  (B) -14  (B) -2.0  listening               ...
 18                     [K] 0   [K] -14  [K] -2.0
 ---- seam: Expression -> StateListening, 0 ms, Body.y jumps 0 -> -0.2 !!
```

Frames down, one column per probed node property (the levels), the host events and callbacks in
the driver column (the blink firing, the glyph flip at BLINK_FLIP), a K on an authored extreme,
a B on an interior key, `-` on an in-between with its delta, and a seam line wherever a level
changes animation with a hard cut across a non-zero delta. Values come from telemetry when a probe
run exists for the scenario, else from the authored keys; both are shown when they disagree
(that is how the success-hop bug reads on the sheet: authored -48, actual -0.2).

### Seams and the blend policy (`rig/seams.py`)

For every transition in every layer: the outgoing animation's last value and the incoming one's
first value on each (object, property) both key, the delta, and the blend duration. A hard cut
across a delta is a seam. `Layer.rml()` refuses one unless the state is marked `cut=True` with a
reason; the beat entry/exit blends (160 / 320 ms) live in one policy table instead of being retyped
per state. The ledger is also a tool: `python -m rig.seams` lists every seam worst first, and the
X-sheet marks them. Telemetry closes the gap the authored keys cannot see: cross-layer seams,
where a one-shot hands a shared node back to a lower layer.

## 2. Encodings that collapse the iteration loop

The costly step for an agent is looking. These encodings put the answer in a form that is read in
one pass, and most of them are text.

1. **Numbers before pixels.** A scenario is probed once (one CLI run) into JSON; every check runs
   on that. An image is only opened when a number is surprising. Order of the loop: regenerate,
   verify, probe, assert, and only then onion or card.
2. **Motion signatures.** Per scenario and property: peak, time-to-peak, overshoot percent, settle
   time (last frame the value is outside 2 percent of rest), max per-frame delta, and the spacing
   class (ease-in, ease-out, S, linear, from the tick distribution). Ten numbers describe a beat
   well enough to know it changed and how. Signatures are committed as goldens (`goldens/*.json`)
   and `test_rig.py` diffs them with tolerances, so a regression fails a test with no image at all.
3. **Text sparklines.** One line per property in the probe output and the X-sheet:
   `Body.y  ▁▁▂▅▇▇▅▂▁▁  peak -48 @20  settle 44`. Eight-level Unicode bars survive a diff, a log,
   and a terminal, and a snap is a vertical wall in the bar.
4. **The chart as a string.** `[0]---(8)------[22]` with tick positions scaled to the delta: an
   ease reads as clustering, a float as even spacing, a pop as a gap. Cheap to render from
   either keys or telemetry; put next to every property in the sheet.
5. **One review card per motion, deterministic layout.** Left: onion skin (edges, tinted). Right:
   the spacing bars per property and the chart strings. Bottom: the contact strip and the diff
   heatmap. The numbers are baked as text in the image so one `Read` gives everything a human or
   an agent needs to judge the beat. Cards are cached by the animation's content hash; a regenerate
   only rebuilds cards whose animation changed.
6. **Scenario batching.** One CLI run walks a whole conversation, `idle -> listening -> thinking ->
   speaking -> idle`, with `--data` and `--advance` chained and `--data-dump-every=1`; the sheet and
   signatures for five entries come out of one second, and every hand-off seam in the sequence is
   measured for real, mixing included.
7. **Authored versus actual, side by side.** The generator knows what it asked for; telemetry
   knows what happened. Every tool prints both when they disagree; that difference is the bug
   class we could not see before (the success hop).
8. **Probe documents never ship.** `--probe` injects the telemetry properties and binds into a
   throwaway document in a temp project (like `--solo`), so scene.rml and the pushed file carry no
   debug surface and the byte-identity invariant holds.

## 3. The pose-to-pose loop, end to end

1. Author: in `rig/motion.py`, a beat is extremes, breakdowns and a chart per node (pose-to-pose,
   not straight ahead). `alloc()` names its ids.
2. `python gen_scene.py /tmp/x.rml && rive . --verify` (about 2 s). The layer builder and the seam
   policy have already refused a bad target or a hard cut across a delta.
3. `python probe.py <scenario>` (about 1 s): telemetry JSON, signatures, sparklines, the chart
   strings, the seam ledger with actual values.
4. `python -m unittest test_rig`: invariants plus goldens. A changed signature must be re-golden'd
   on purpose, with the diff in the commit.
5. `python review.py <scenario>` only when the numbers do not settle the question: one card.
6. Sweatbox: `sheet.py` for a sequence, the desktop app for the feel. Human.
7. `rive push`, then commit.

## 4. Order of work (each step keeps `scene.rml` byte-identical unless it says otherwise)

| Step | Owns | Deliverable |
|---|---|---|
| A | `rig/seams.py`, `rig/layers.py` hook, `test_rig.py` | seam ledger, blend policy, test |
| B | `rig/chart.py`, `timeline.py --chart`, `xsheet.py` | charts both ways, the exposure sheet from authored keys |
| C | `rig/probe.py`, `gen_scene.py --probe`, `scenarios.py`, `probe.py`, `goldens/` | telemetry, signatures, goldens, tests; X-sheet reads telemetry |
| D | `review.py` | the review card, cached by hash |
| E | README.md | the loop rewritten around numbers-first |
| F | rig fixes the tools surface (success hop, then every one-shot on a shared node) | output-changing, pushed |

A and B can run in parallel; C after A; D after B and C; E last; F as its own PRs.

## 5. Risks and open questions

- Which properties are bindable target-to-source: x, y, rotation, scale and opacity on nodes are
  confirmed. Joystick values, bone rotations, the nested plate's inputs and gradient stops need a
  probe each; anything not bindable is read from authored keys and marked as such.
- Sampling order: the bind reads the value after the state machine advanced (the success-hop run
  matched the screenshot). Keep one cross-check in `test_rig.py`: a screenshot's centroid versus the
  probed Body.y for one frame.
- Determinism of random layers: a probe of a random-wait layer is not repeatable; beats are probed
  through `--solo`, sequences through explicit state drivers.
- The bead for the success hop is the first F item; its fix may change how one-shots on shared
  nodes are layered (a per-node "owner layer" rule the seam ledger can enforce).
