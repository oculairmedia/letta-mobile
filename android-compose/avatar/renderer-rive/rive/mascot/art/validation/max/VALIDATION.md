# Astra Max validation

Design handoff against `2e11692c21ddcc971e2acb1609c33794ed892118`. **Static checks pass; human acceptance and native integration remain pending.** No Rive scene, generator, cloud file, exported binary, Kotlin contract or external state key changed. Sections 1–9 and all44 prior root SVGs are unchanged. Working is a proposed asset, not a newly implemented state.

## Required checks

| ID | Result | Evidence and limit |
| --- | --- | --- |
| V1, states at22/44/72 | STATIC PASS; HUMAN PENDING | [sizes.png](sizes.png) contains every existing expression plus proposed working on light/dark backgrounds. Proposed scale1.25 and small22 profile are labelled; current scale1.0 has its own44px row. Independent status identification has not been run. |
| V2, pupil phase visibility | STATIC PASS; PLAYBACK PENDING | [squiggle-strip.png](squiggle-strip.png): one pupil at four phases. At44px, phase0 versus.5 changes13 listening pixels and8 speaking pixels by at least8/255 in a channel; maximum changes60 and37. This is measurable raster change, not a perceptual threshold or an animation test. |
| V3, deformation | STATIC PASS; NATIVE PENDING | [deformation-extremes.png](../../mesh/deformation-extremes.png): land/drag/inflate and all8 identities. Validator checks2,400 sampled poses; no sampled crossings; maximum anchor displacement11.1921% of150px. Same eight-cubic topology and mirrored handles. |
| V4, working versus thinking | DESIGN REVIEW PASS; HUMAN PENDING | [working-comparison.png](working-comparison.png): vertical notch versus horizontal dash at actual sizes. No five-person trial was conducted; confusion below10% is unproven. |
| V5, containment | STATIC PASS WITH PROPOSED CLAMP | Pupil wave has minimum1.5px inward iris clearance over257 phases at A4 and all parallax corners. Outer glyph sweep:630 combinations, minimum2px after noise attenuation; all tested host endpoints retain their full travel. Combined native facing/lean/scaling remains untested. |
| V6, purity | PASS | Nine new ingest SVGs: exact centred viewBox, one path each, one colour per path, M/L/C/Z only; actual `svgpath.py` accepts all nine. The separate lattice diagram uses pure paths but is not an eight-cubic body input. |
| V7, mouth topology | PASS | All10 mouth SVGs are byte-identical to the baseline. Closed/half/open remain matching four-cubic paths; no second envelope. |

## What the proofs establish

The wave is visible as a small change in the pupil mass at44px; the larger crops expose its geometry. It is secondary to the speaking mouth. At22px the entire pupil overlay is absent. Thinking and sleeping have zero raster change across phases at every size. Catchlight is confined to72px, inside the single core; reduced motion hides wave, catchlight and inner parallax. These are authored optical choices, not research-derived guarantees.

The current failed X can extend2.540659px beyond its rounded plate at host look(1,1) plus a6px right saccade. The supplied [containment.png](containment.png) shows the proposed correction: retain host(23,17), reduce the noise contribution from6px to1px, and reserve2px ink clearance. The wider sweep bounds auto-bias plus saccade by ±12/±9. This clamp is specified here, not implemented in the rig.

Force deformation analytically preserves planar area, with maximum numerical residual6.661e−14 percentage points. Inhale intentionally increases area6.09%. The minimum filled-body frame margin is12.499px under the mesh validator's **neutral-facing** endpoint sweep; this excludes painted edges, plate, base-pivot lean, combined facing and trails. It is not a full runtime containment guarantee.

[motion-floors.png](motion-floors.png) records current versus proposed default framing. Local translation amplitudes do not change. Restore scale1.25 to recover the brief's44dp floors; that default and the22dp profile still require implementation. Paint in these images uses SVG gradients and raster Gaussian approximations, not the Rive feather kernel. Proof images are review material; SVG paths and numeric tables are the ingest deliverables.

## Reproduce

From the mascot project directory:

```sh
python art/validation/validate.py
python art/validation/max/validate_max.py
python art/mesh/validate_deformation.py --check
```

Use `validate_max.py --render` to regenerate its five PNGs and [metrics.json](metrics.json). It reads the existing pure SVG converter in memory, never imports `gen_scene.py`, invokes Rive, or writes RML. Dependencies: Python, numpy, Pillow, CairoSVG. The earlier validator now allows the two proposed working files without extending its active state set; its historical proof scope is unchanged.

## Remaining acceptance

For V1, show [recognition-44.png](recognition-44.png) at100% to five people unfamiliar with the design; request one of idle/thinking/success/error/sleeping for each item before revealing the key. Record each response and background separately. Key:1 sleeping,2 idle,3 success,4 thinking,5 error. No participant data is supplied.

For V4, first teach the two proposed labels, then show thinking and working individually in randomized order at44px,500ms each, four presentations per symbol per person. Five people yield40 trials; below10% confusion means at most3 incorrect answers. This is an authored acceptance protocol, not a statistical claim about a population. Also inspect22/72px before selecting the optical profile.

Fable's integration gate: apply the numbers and geometry, verify the exported contract with the existing contract checker, and exercise Path Effect or keyed fallback in the pinned Android and desktop runtimes. Check overlay visibility during shutter/flash/drag, phase invalidation, body binding and area between keys, composed bounds and paint, scale1.25, size selection, and the separately owned reduced-motion contract. The draft handoff does not claim these gates passed.
