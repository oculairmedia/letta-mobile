# Single pupil — mechanical ingestion contract

Design proposal against `2e11692c21ddcc971e2acb1609c33794ed892118`; see SPEC §10 for the parent motion patch. One shared pupil assembly appears only inside the existing `idle`, `listening` and `speaking` eye glyphs. All other expressions keep their pure symbols. No `AvatarState`, enum, external key or existing Solo child ID changes are part of this asset pass.

## 1. Units and files

Import path coordinates directly: one SVG unit is one artboard pixel before display scaling. Every file uses `viewBox="-50 -50 100 100"`, one path, a single colour, no transforms, gradients, filters or masks. Clips are authored in Rive, not embedded in the SVGs.

| File | Exact geometry | Rive paint / role |
| --- | --- | --- |
| `iris-field.svg` | Centred rounded rectangle, width 34, height 30, radius 7 | `FFF7F7F7`; stationary socket field within the dark geometric eye; also the clipping shape for `PupilRoot` |
| `pupil-core.svg` | Centred circle, diameter 14, one closed four-cubic contour | `FF111111`; the single solid core |
| `squiggle-0.svg` | Open four-cubic path, phase 0, amplitude 1 | `FF111111`, stroke 8, round caps/joins |
| `squiggle-1.svg` | Same topology, phase π/2, amplitude 1 | Same stroke |
| `squiggle-2.svg` | Same topology, phase π, amplitude 1 | Same stroke |
| `squiggle-3.svg` | Same topology, phase 3π/2, amplitude 1 | Same stroke |
| `catchlight.svg` | Circle diameter 6, centre (2,-2), one closed four-cubic contour | `FFFFFFFF`, object opacity 0.90; enabled at 72 dp only |

Render the wave and core together in one eye: wave behind core, catchlight above core. The wave intersects the core throughout the specified amplitude range; it must not become a disconnected second dot or pupil. Identity `color` still binds body paint; the iris and eye remain neutral.

## 2. State lookup

`P` below means the same shared `iris-field.svg` + `pupil-core.svg` + `squiggle-0.svg` through `squiggle-3.svg`. `e = clamp(mouthOpen,0,1)` is the **existing smoothed mouth envelope**, not a second audio analysis or smoothing chain. Frequency is cycles/second; amplitude is centreline artboard pixels. Catchlight visibility also requires P to be visible and the 72 dp profile.

| key / role | Existing expr | Existing glyph SVG | Overlay files | Procedural frequency Hz | Amplitude px | Keyed fallback Hz / amplitude px |
| --- | --- | --- | --- | --- | --- | --- |
| `idle` sustained | 0 | `../glyph-idle.svg` | P | 0.40 | 1.50 | 0.40 / 1.50 |
| `listening` sustained | 1 | `../glyph-listening.svg` | P | 0.80 | 4.00 | 0.80 / 4.00 |
| `thinking` sustained | 3 | `../glyph-thinking.svg` | none | 0 | 0 | 0 / 0 |
| `waitingInput` sustained | 4 | `../glyph-waitingInput.svg` | none | 0 | 0 | 0 / 0 |
| `speaking` sustained | 5 | `../glyph-speaking.svg` | P | 1.50 + e | 1.00 + 3.00e | **2.00 / 2.50**, fixed |
| `error` sustained | 7 | `../glyph-error.svg` | none | 0 | 0 | 0 / 0 |
| `sleeping` sustained | 8 | `../glyph-sleeping.svg` | none | 0 | 0 | 0 / 0 |
| `loading` lifecycle | 9 | `../glyph-loading.svg` | none | 0 | 0 | 0 / 0 |
| `failed` lifecycle | 10 | `../glyph-failed.svg` | none | 0 | 0 | 0 / 0 |
| `degraded` lifecycle | 11 | `../glyph-degraded.svg` | none | 0 | 0 | 0 / 0 |
| `success` flash | 6 | `../glyph-success.svg` | none | 0 | 0 | 0 / 0 |
| `error` flash | 7 | `../glyph-error-flash.svg` | none | 0 | 0 | 0 / 0 |
| `dragged` held | 2 | `../glyph-dragged.svg` | none | 0 | 0 | 0 / 0 |
| `working` proposed art only | **unassigned** | `../glyph-working.svg` | none | 0 | 0 | 0 / 0 |

`working` is not an added state key in this implementation. Its symbol is reserved for Fable's separate contract work. Overlay visibility follows the resolved live `expr`, so flashes and drag override a hosted idle/listening/speaking state immediately. The mouth continues its existing independent `mouthOpen` behavior for speaking/dragged.

## 3. Wave geometry and time

For x in [-10,10], use the analytic design curve:

`y(x) = A × sin(φ + 2π(x+10)/20)`.

The delivered paths approximate it with cubic Hermite segments. Five anchors occur at `x = -10,-5,0,5,10`, in that order. For segment endpoints `(xi,yi)` and `(xi+5,yNext)`, use endpoint slopes `mi = A × (2π/20) × cos(φ + 2π(xi+10)/20)` and `mNext = A × (2π/20) × cos(φ + 2π(xi+15)/20)`:

| Cubic point | x | y |
| --- | --- | --- |
| Start | xi | yi |
| Control 1 | xi + 5/3 | yi + 5mi/3 |
| Control 2 | xi + 10/3 | yNext - 5mNext/3 |
| End | xi + 5 | yNext |

Scale all delivered **y coordinates and handle y components** by A. Keep x and the 8 px stroke width unchanged. Do not apply `nodeScaleY` to set amplitude: it would distort the core, stroke and socket. Recompute detached handle angle/distance from the scaled handle vector if the importer stores polar handles. These are open paths with five vertices and four cubics; do not close them or change point order.

Preferred procedural phase: `φ = (φ + 2πf × elapsedSeconds) mod 2π`. Use one local Path Effect `advance(seconds)` clock and invalidate with `context:markNeedsUpdate()`. Script attachment and exported runtime behavior remain unverified; the exact Android/desktop gate is in [RIVE-LEFTOVERS.md](RIVE-LEFTOVERS.md). No external `pupilPhase` property is added.

Fallback: key matching path coordinates through phases 0, π/2, π, 3π/2, then phase 0 at cycle end. Interpolate coordinates linearly; this is a quarter-period approximation, not an exact continuously phased sine. A canonical 60-frame, 60 fps source cycle has phase keys at frames **0/15/30/45/60**. Play that one local cycle at the table's frequency multiplier; periods are idle 2500 ms, listening 1250 ms, speaking 500 ms. This avoids rounding each state's quarter-period keys. Fable must preserve normalized phase when changing speed, and prove this behavior; do not restart the cycle at each visible state change.

| Event | Phase / visibility behavior |
| --- | --- |
| Hidden → visible | Start at φ=0, apply resolved state's A/f, then advance |
| Visible idle/listening/speaking change | Preserve φ; change A/f to the new row using the existing state transition; no separate clock or catchlight animation |
| Visible → hidden | Hide the whole overlay and stop its phase clock; stored hidden phase is not resumed |
| Blink | Keep phase continuous; the existing blink transform closes the whole eye assembly |
| Reduced motion enabled | Keep visible states' core/socket static; hide the wave, freeze phase, remove inner parallax; no replacement clock |
| Reduced motion disabled while visible | Restart φ=0 and resume the current state's single phase clock |

Do not add the brief's separate 8–12 Hz tremor or another microsaccade timer. Existing automatic gaze and saccades already supply fixation movement.

## 4. Hierarchy, gaze and containment

| Existing / proposed location | Ownership |
| --- | --- |
| Existing `PlateRoot` (`7:20`) → `GlyphScale` (`7:26`) → `Saccade` (`7:28`) → `Glyphs` Solo (`7:22`) | Preserve names, all twelve existing Solo child IDs `7:30`–`7:41`, tuning and automatic saccade ownership |
| Proposed `PupilOverlay`, sibling drawn above `Glyphs` under `Saccade` | **One shared overlay**, inheriting the same saccades and glyph tuning; co-key x/y and blink scaleY from the same existing `LookX`, `LookY`, `Blink` timelines as `Glyphs`; set visibility for expr 0/1/5 only |
| `PupilOverlay` → iris field + clipped `PupilRoot` | Iris is fixed within the outer eye; `PupilRoot` owns only inner parallax; core, wave and catchlight share it |
| New objects | Fable allocates IDs from the actual file; no IDs are invented here; do not create a second face VM |

Preserve host gaze `H = (23 × lookX, 17 × lookY)`. Inner parallax in pupil-local pixels is `δ = clamp(0.15H, x=-1.5..1.5, y=-1..1)`; it adds to `PupilRoot` only. Do not multiply outer gaze by 1.15. Socket clipping is a backstop after these bounded motions.

Let `N = autoBias + saccade` in the same plate-local space. Apply `G = H + λN`, where λ is the **largest** value in [0,1] for which the complete outer glyph ink retains at least **2 px** clearance inside the current rounded Card. Test strokes, corners and the actual glyph/plate transforms before clipping. At full noise the guard may equal 1; near an edge it attenuates decoration first.

If H alone fails, find the largest radial κ in [0,1] satisfying the same condition for `κH`; use `G=κH` and suppress N for that frame. If even the centred glyph cannot fit, that tuning combination fails validation; translation cannot repair oversized art. The guard applies to every expression, including pure symbols, independently of overlay visibility. Clipping must not be used to claim unclipped geometry passed.

## 5. Numeric delta and size profile

The current default display scale is 1.00. SPEC §10 proposes 1.25; target multipliers on a 500 px artboard are **0.055 / 0.11 / 0.18** at 22/44/72 dp. Static target proofs must not be labelled current playback.

| Parameter | Current rig | Proposed |
| --- | --- | --- |
| Shared pupil overlay | absent | one, visible only expr 0/1/5 at 44/72 |
| Iris / core | absent | 34×30 r7 / diameter 14 |
| Wave centreline span / stroke | absent | 20 / 8 px; A/f by §2 |
| Catchlight | absent | diameter 6 at (2,-2), alpha .90; 72 only |
| Host eye travel x/y | ±23 / ±17 px | unchanged |
| Inner parallax x/y | 0 / 0 | ±1.5 / ±1 px maximum |
| Combined gaze margin | unguarded addition | 2 px minimum outer-ink clearance, λ/κ policy above |
| Separate cosmetic tremor / timer | absent | absent; proposed additional clocks dropped |
| Blink close/hold/open | 4/1/10 frames at 60 fps | unchanged: **250 ms total**, not rounded 67+17+167 ms |
| Rest lid scaleY | 1 outside blink | unchanged; no new per-state lid weights; sleeping keeps its existing symbol |

| Feature at proposed scale 1.25 | 22 dp | 44 dp | 72 dp |
| --- | --- | --- | --- |
| Entire pupil overlay | hidden; retain existing `-small` art unchanged | visible for §2 P rows | visible for §2 P rows |
| Iris field size | — | 3.74×3.30 dp | 6.12×5.40 dp |
| Core diameter | — | 1.54 dp | 2.52 dp |
| Wave stroke / maximum centreline A | — | .88 / .44 dp | 1.44 / .72 dp |
| Maximum inner parallax x/y | — | .165 / .11 dp | .27 / .18 dp |
| Catchlight diameter | hidden | hidden | 1.08 dp |

The catchlight is an explicit optical enlargement over the brief's **2.52 artboard px** suggestion: that would be .2772 dp at 44 and .4536 dp at 72, both below one logical pixel. Diameter 6 reaches 1.08 dp at 72. Its furthest point lies `sqrt(2²+(-2)²)+3 = 5.8284` px from the core centre, leaving 1.1716 px inside radius 7. It follows the core; it is not a second pupil. Physical device density still affects final rasterization.

The host currently lacks the specified small-profile signal. Fable must establish the size profile during integration; these SVGs alone cannot switch it. Until then, disable the entire overlay on the 22 dp surface rather than shrinking its detail into noise.

## 6. Evidence and acceptance

[RESEARCH-NOTES.md](RESEARCH-NOTES.md) distinguishes research findings from authored values. Research informs asymmetric blink and intentional gaze; it does not prescribe this pupil's shape, frequency, amplitude, parallax or catchlight size.

Static proofs assess geometry, topology and nominal-size readability. They do **not** establish Path Effect binding, cross-platform playback, human state recognition or a successful reduced-motion host contract. Before procedural adoption, Fable must verify phase continuity, hidden-state pausing, blink synchronization, combined gaze containment and all three size profiles in the actual Android 11.12.0 app and native desktop bridge. Use the keyed fallback when that script gate is not met; maintain the same one-eye art and state lookup.
