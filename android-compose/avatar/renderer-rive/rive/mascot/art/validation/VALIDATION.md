# Human-touch art validation

Baseline: `c6bea8dd780169cf2e57d670da50ea2987b78234`. This is the design consultant's static SVG proof. Fable's Rive file was neither opened nor regenerated. Seventeen existing eye SVGs changed; all eight bodies, ten mouth assets, other SVG filenames, and SPEC §8 are preserved.

**Mechanical gates pass. Visual self-review passes. The brief's independent stranger-recognition criterion has not been run; it is not represented as a pass.** The review handoff is draft-only until that result is recorded. No known static failure remains. This pack makes the remaining human check executable without a Rive cycle.

| Check | Result | Evidence / limit |
| --- | --- | --- |
| V1 size readability | PASS — visual self-review; **PENDING — independent recognition** | [Standard sizes](sizes-standard.png): 13 expressions at exact 22/44/72 output px, light/dark, 120 px base Card, actual-raster eye crops enlarged 4× with nearest-neighbour. At 44 px the square, dash, upper crescent, diamond/frown and lower crescent remain distinguishable in this review. This is not evidence of a stranger naming them. Run [recognition card](recognition-44.png) below before marking the human criterion PASS. |
| V2 motion amplitude | PASS — measured | [Eight 500×500 motion frames](motion-amplitudes.png). All new values meet the supplied 44 dp floors; every old value is below its floor. Arrows use the same 1.25 display scale as the art. [Two signatures](signatures.png) show the specified pose endpoints; no claim to render live trails/facing. |
| V3 topology / purity | PASS — automated | [validate.py](validate.py): all 44 files decode; only path fills/strokes; exact viewBoxes; filenames unchanged; all eight body paths retain mirrored eight-cubic topology and baseline bytes. Four-vertex mouths retain right/bottom/left/top order over 41 interpolated samples. Thinking ≠ dragged and speaking ≠ idle for standard and small. The existing `svgpath.py` converter accepts every SVG in memory; no RML file is written. |
| V4 SPEC consistency | PASS — automated + table review | §9 provides old→new translation, paint, easing, phase and glyph dimensions. Changed §7 gaze, breath, ring, speaking-eye and X measurements are recomputed from the paths/multipliers. §8 matches baseline bytes. All new timing choices cite local MOTION-REFERENCES or the linked primary motion-token source; amplitude and opacity ranges are attributed to the supplied brief. |
| V5 freeze-frame character | PASS — visual self-review | [Ten sustained states](character-freeze.png). One unchanged blob, white plate, one geometric eye. Offset square corners, bent dash and uneven arc endpoints replace rigid symmetry while preserving the glyph vocabulary. No limbs, body costumes or duplicate pupils. No independent preference/recognition study is claimed. |

| Measured quantity | Result |
| --- | --- |
| Pixel conversion | 22/500×1.25=0.055; 44/500×1.25=0.11; 72/500×1.25=0.18 |
| Breath / gaze X / gaze Y @44 | 1.21 / 2.53 / 1.87 dp |
| Hop / waiting / shake / error settle / listening @44 | 5.28 / 2.09 / 2.64 / 2.64 / 1.54 dp |
| Minimum eye-ink clearance inside rounded Card | 2.875 artboard px, including stroke expansion, all expressions and extreme x/y gaze combinations |
| Minimum filled-body clearance inside artboard | 8.408 output artboard px after 1.25 display scale; all eight identities sampled through combined root/facing rotations −11°…+11°, root x ±24 and y −48/+24 |
| Preserved §8 SHA-256 | `a8a67c8f180a58a805edb9d8c464e055e40a319ef314860fe4e3e783fe8bd0bf` |

## Size and material findings

| Finding | Disposition / proof |
| --- | --- |
| Standard failed X collapsed toward a dot at 22 px | Fixed: endpoints ±18→±24, stroke 14→16. It retains four corner gaps in the revised size sheet and still clears the Card at full gaze by 2.875 px. Small X is unchanged. |
| Standard speech mouth is weak at 22 px | Existing 24×28 full-open mouth becomes 1.32×1.54 px; half-open is 1.21×0.77 px. Do not claim strong half-open readability in the currently shipped standard-only rail. [Mouth sheet](mouths.png) shows the limitation and the existing small geometry, whose half-open mouth is 1.65×1.43 px. Fable/app own the pending small-profile signal. Mouth geometry and host envelope were preserved as requested. |
| Small profile is not shipped in §8 | [Small proposal sheet](sizes-small-proposal.png) labels its 152 px Card and `-small` paths explicitly. It is a geometry check, not a runtime screenshot. Ambient translation breath/glance remain pruned in the proposed profile. |
| Hollow strokes | Standard waiting eye outer/hole Ø58/28 gives 6.38/3.08 dp at 44; minimum ink ≥1.49 dp. Small outer/hole Ø72/32 gives 3.96/1.76 dp at 22; minimum ink ≥1.02 dp. The small waiting mouth drops its hole, as before. |
| Quiet glow | [Before/patch materials](materials.png): halo 0.04→0.10, edge 0.08→0.14; sleep halo 0.02→0.06. Fixed shade/gloss, identity colour and body paths. GlassRing stays off: a 1–1.5 px line would be only 0.11–0.165 output px at 44. Static feathers use an explicitly approximate Gaussian kernel. |
| Artboard edge | Filled paths pass; blur support and the live ghost trails are outside this static guarantee. Fable must inspect error-settle lower-edge feather and success trails after ingest. |

## Independent recognition check — pending

Use `recognition-44.png` at 100% image size, with no zoom, state sheet or answer key visible. Ask a person who has not seen the glyph mapping to assign idle, thinking, success, error or sleeping to items 1–10. Each expression appears once on each background. Record first answers before showing the key. Pass the brief's criterion only if all five expressions are correctly named on both backgrounds; a miss reopens the relevant glyph/size decision before ready-for-review status. One reviewer is the brief's minimum check, not a population-level study.

| Item | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Answer key — conceal during test | sleeping | error | idle | success | thinking | thinking | idle | sleeping | error | success |
| Recorded first answer | pending | pending | pending | pending | pending | pending | pending | pending | pending | pending |

## Reproduce

From the repository root, with Python 3, numpy, Pillow and CairoSVG installed:

```sh
python android-compose/avatar/renderer-rive/rive/mascot/art/validation/validate.py
python android-compose/avatar/renderer-rive/rive/mascot/art/validation/validate.py --render
```

The baseline commit must be available locally. Images use exact pixel dimensions; CSS/browser zoom or a preview that fits the whole sheet changes their apparent size. Only the dedicated crops are enlarged. The script samples geometric bounds rather than running a Rive scene, so it cannot establish runtime animation arbitration, clipping, pixel parity or accessibility behavior.

Fable ingest order: **SPEC numbers → swap SVGs → regenerate**. Retune State/Enter/Flash translations and LookX/LookY endpoints. Preserve §8; thinking/dragged have matching six-cubic topology so the existing Thinking Solo child can key the supplied path coordinates for expr 3/2 under the existing shutter. No new Solo slot or external key is needed. Then check the runtime contract, the existing shutter/trails, and the pending small-size behavior. Beads and all Rive operations remain with the implementation owner per the user's consultant scope.
