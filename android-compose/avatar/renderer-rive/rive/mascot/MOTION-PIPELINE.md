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
   together (SPEC 2). At runtime Face rose (peak -47.9 px, frame 20) and Body did not (it showed
   the breath, -0.2 px). A screenshot at frame 18 agreed. The guess at the time was a layer
   fight - the Flash layer's Body keys losing to the Expression layer's - and the guess was
   wrong: the flash's Body keys were not in the document at all, dropped by a `dict.update` in
   the generator (finding 1 below). That is the lesson twice over. Telemetry found a bug nothing
   else could see, and then the first explanation of it was still a guess until the ledger was
   asked which animations key `Body.y`.

Everything below is built on (1). The loop's floor is the CLI (verify 1.9 s, a screenshot 2.2 s);
the plan spends that floor once per scenario, not once per frame, and spends it on numbers before
pixels.

### Findings (step C, 2026-09-14; fixes 2026-09-14, step F): what the probe pass turned up

Seventeen node properties now read back per frame (`rig/probe.py`), driven by named scenarios
(`scenarios.py`) through `probe.py`, with signatures committed as `goldens/*.json` and checked by
`test_probe.py`. The probed document renders **pixel-identical** to the shipped one (diffed at
`state=speaking`, frame 40), so the instrument does not disturb what it measures.

1. **The success hop only lifted the face - FIXED (letta-mobile-uesod).** `Face.y` peaked -48.0 at
   frame 19 and `Body.y` reached -0.21, the breath. The cause was not layering: `rig/motion.py`
   built the flash's keys with `dict.update`, and `spin_keys()` also keys `BODY_NODE` (its roll),
   so updating with it REPLACED the body's property map and took the 48 px hop with it.
   `SuccessFlash` never keyed `Body.y` at all - which the ledger could have shown and the authored
   source could not, because the source reads as though it does. `merge()` now refuses to drop a
   property, and `probe.py success` reads `Body.y peak -48.000 @19`, the same frame as the face.
2. **The plate never foreshortened on a horizontal turn - FIXED (letta-mobile-72r3a).**
   `Turn.scaleX` read exactly 1.000 for the whole of `success`, `beat:WanderSpin` and every entry
   while `Turn.x` swung the full +-70 px. `TurnX` keyed `Turn.scaleX` (the squash) AND
   `Turn.scaleY` (the recede); `TurnY` keyed both too; the joystick applies both pose ranges every
   frame and the last one wins outright - no mixing - so TurnY's 1.0 flattened the squash. Each
   axis now owns whole properties (TurnX: x, rotation, scaleX; TurnY: y, scaleY) with the recede
   folded into the scale its axis carries. `Turn.scaleX` now reaches 0.588 at |Turn.x| = 70.
   **The rule this leaves behind:** two animations that key one property are never mixed, in a
   joystick or across layers. One property, one owner.
3. **The success spin cut, hard - FIXED (same bead).** `Turn.x` ran -70.0 -> -23.1 between frames
   24 and 25, `Arc.y` -0.0 -> -5.3 on the same pair: 47 px in one frame. The whip was authored as
   three beziers - BACK_IN into the first extreme, ELASTIC_OUT out of the last - and both are
   front-loaded to the point of a cut. `spin_keys()` now lays its in-betweens down as real keys
   (`rig/chart.py`, 18/32/32/18 % of the travel per quarter, linear between the ticks), and the
   success flash gives the spin 41 frames instead of 29 to cover four units of facing.
   `Turn.x max|delta|` 46.9 -> 11.2, `Arc.y` 5.30 -> 1.73.
4. **`--data` does not sequence, so scenario batching does not work.** Section 2 item 6 assumed
   `--data=... --advance=40 --data=... --advance=40` walks a conversation in one run. It does not:
   every `--data` is applied before the scene runs, whatever its position in argv, and repeated
   writes to one property collapse to the last. Measured - that argv prints telemetry
   byte-identical to a single `--data=state=listening --advance=80`. `--pointer` and `--advance`
   do sequence. **The tools no longer pretend otherwise (letta-mobile-jqp5b):** `scenarios.py`
   REFUSES a scenario that writes one property twice, so `conversation` and
   `enter-thinking-from-listening` are gone - the latter is now `enter-thinking`, which is what it
   always ran as - and `onion.py --from`, which was the same bug in the other tool, is removed.
   A driver scenario can only ever enter from idle; an entry out of any other state is read as
   `beat:Enter_<from>_<to>`, the animation alone through `--solo`, where frame 0 IS the pose the
   entry starts from.
5. **61 hand-backs across the generic entries - FIXED (letta-mobile-r4bbm).** A sustained loop or
   designed entry held Face/Body somewhere (error face y 28 / body y 24 / rot 5 deg;
   Enter_idle_listening face y -14 rot -2; Enter_listening_thinking rot -6; degraded rot 4;
   sleeping's 0.985 inflate) and the generic `Enter_<from>_<to>` that followed keyed nothing on
   those nodes, so the pose snapped to rest across a 0 ms cut. A generic entry now starts at the
   SOURCE state's held pose and eases to the TARGET's rest on the entry's own curve, both derived
   from the StateRow table the loops are built from (`sustained_rest`). `python -m rig.seams`:
   61 unexplained -> 0, pinned by `test_rig.py`. The four that were not entries are the error
   flash: three signed `hold=True` (a new signature for a DELIBERATE hand-back - the Expression
   layer below is holding that same pose), one that became an ordinary signed cut once
   `SuccessFlash` keyed `Body.y` again.
6. **A synthesised pointer drag does not reach the file's drag listener.** `--pointer=down@250,250
   --pointer=move@300,300` renders pixel-identical to a plain hover and nothing like
   `--data=dragged=true`. The `drag` scenario writes the contract boolean instead.
7. **A nested state machine's input cannot be read back.** A two-way bind on the plate's `expr`
   (`NestedNumber` 0:211, propertyKey 239) verifies, builds and is inert: it reports the authored
   `nestedValue="0"` for every frame of every scenario, including `--data=state=speaking`. Which
   glyph a state selects still has to come from the authored keys or from a pixel. Everything else
   on the risk list in section 5 - node x/y/rotation/scale, gradient opacity, and **both joystick
   axes** (keys 299/300) - reads live.

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
   `Body.y  ▁▁▂▅▇▇▅▂▁▁  peak -48 @19  settle 57`. Eight-level Unicode bars survive a diff, a log,
   and a terminal, and a snap is a vertical wall in the bar.
4. **The chart as a string.** `[0]---(8)------[22]` with tick positions scaled to the delta: an
   ease reads as clustering, a float as even spacing, a pop as a gap. Cheap to render from
   either keys or telemetry; put next to every property in the sheet.
5. **One review card per motion, deterministic layout.** Left: onion skin (edges, tinted). Right:
   the spacing bars per property and the chart strings. Bottom: the contact strip and the diff
   heatmap. The numbers are baked as text in the image so one `Read` gives everything a human or
   an agent needs to judge the beat. Cards are cached by the animation's content hash; a regenerate
   only rebuilds cards whose animation changed.
6. **Scenario batching - limited.** Measured (step C): the CLI applies every `--data` before the
   run whatever its position and collapses repeats to the last, so one run cannot walk
   `idle -> listening -> thinking`; only `--pointer` and `--advance` sequence. A conversation is
   therefore one run per state change (each about a second, still numbers not pixels), and the
   cross-state hand-off seams come from the ledger plus one probe per pair. The Harness artboard's
   Luau script (section 6) can change the view model on a frame schedule and is the way to get a
   true multi-state run; `probe.py` warns when a scenario tries to sequence `--data`.
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
- The bead for the success hop was the first F item. Its fix turned out not to be a layering
  question at all (see Findings 1), but the turn did produce the rule: **one property, one
  owner** - two animations that key the same property are never mixed, whether they are two
  joystick axes (Findings 2) or two layers. A per-node owner rule the seam ledger could enforce
  is still worth having.

## 6. The same tools inside the harness itself

The CLI path above is the agent's loop. The same instruments also belong in the live harness, in
two tiers that share the probe list, so a human at the bench and an agent at the CLI look at the
same numbers.

### Tier 1: a Harness artboard in the file (`gen_scene.py --harness`)

A second artboard, `Harness`, generated only on request (never in the shipped `.riv`): it nests
the Mascot artboard at the left, exposes the probe view model (the two-way binds from section 0),
and carries a Luau node script (`harness.luau`, a `ScriptAsset`) that every frame:

- reads the probed nodes through `NodeReadData` (position, rotation, scale, world transform) and
  the probe numbers through the view model;
- keeps a ring buffer per property and draws it as a sparkline path with the `path` / `paint`
  API, plus the timing-chart ticks (spacing of the last N frames along a line) and a trailing
  path of action (the Face's world position over the last second, the classic arc check);
- writes text readouts (state, frame, per-property value and per-frame delta) into bound text runs.

That makes `rive . --artboard=Harness --screenshot=... --data=state=listening --advance=N` a
single image that already contains the mascot, its curves, its chart and its numbers: one
screenshot per question instead of eight, and a human opening the same file in any player sees the
instrument panel live. Scripts run in the CLI, the editor and the runtimes alike, so the harness is
portable. The seam ledger's cut list can be baked in as a bound string so the panel names the
seam it is crossing when a level tears.

### Tier 2: the desktop bench (`RiveDesktopSpike`)

The bench already holds every rendered frame as a bitmap, so a live onion skin is the last N
frames composited with the same ramp `onion.py` uses: a toggle, not a tool run. Two small bridge
additions make the rest possible: load by artboard name (today the bridge takes the default
artboard) so the bench can show `Harness`, and `rive_bridge_vm_get_number(name)` so the bench reads
the probe numbers each frame and draws the X-sheet strip and sparklines in Compose beside the
character. A scenario runner (the same `scenarios.py` list) plays a sequence and records a
signature on the spot, which `probe.py` can diff against the goldens. The bench is where the
sweatbox happens; this gives the sweatbox the charts.

Order: Tier 1 after step C (it consumes the probe list), Tier 2 in parallel with D.

