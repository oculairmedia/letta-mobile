# Working glyph proposal

`glyph-working.svg` and `glyph-working-small.svg` propose one connected vertical eye with a notch open on its right. The taller outer mass separates it from the existing low, horizontal thinking dash. Corner and edge asymmetry remains within the current soft geometric family. There is no separate pupil, catchlight, mouth, second eye or detached component.

`working` is an **asset proposal name only**. This delivery adds no `AvatarState`, expression number, VM field, Solo child ID, or arbitration priority. Fable owns any later semantic/routing contract change.

| Property | Standard | Small |
| --- | --- | --- |
| File | `../../glyph-working.svg` | `../../glyph-working-small.svg` |
| viewBox | `-50 -50 100 100` | `-50 -50 100 100` |
| Ink bounds x / y, artboard px | −22…22 / −28…28 | −26…26 / −32…32 |
| Total width × height, artboard px | 44 × 56 | 52 × 64 |
| Right-open rectangular notch clearance, artboard px | 18 × 20; x=4…22, y=−10…10 | 21 × 24; x=5…26, y=−12…12 |
| Maximum notch depth, artboard px | 23; right edge 22 to recessed edge −1 | 26; right edge 26 to recessed edge 0 |
| Width × height at 44 px, current display scale 1.0 (`44/500`) | 3.872 × 4.928 px | 4.576 × 5.632 px |
| Width × height at 44 px, proposed display scale 1.25 (`44/500 × 1.25`) | 4.84 × 6.16 px | 5.72 × 7.04 px |
| Rectangular notch at 44 px, proposed display scale 1.25 | 1.98 × 2.20 px | 2.31 × 2.64 px |
| Minimum sampled clearance to 120/r27 plate at gaze ±23/±17 | 13.99699 artboard px | 8.83030 artboard px |
| Path structure | One closed contour; one `M`, nine `C`, seven `L`, one `Z` | One closed contour; one `M`, nine `C`, seven `L`, one `Z` |
| Paint | One fill `#111111`; no stroke | One fill `#111111`; no stroke |

The clearance measurement samples each cubic at 1,001 parameter positions and checks all four extreme gaze corners using the rounded-square signed-distance function. It measures the glyph in plate-local coordinates. It **does not cover additional saccades, local scale/rotation, deformation, or runtime clipping**. Display scaling of the whole plate preserves this local containment. The small asset also fits the stricter standard plate; its proposed 22 px use has the larger 152/r34 plate.

## Proof

`working-comparison.png` places thinking and working on the same unchanged `body-blob.svg`, neutral plate, and identity colour. Each full orb is rasterized to exactly 22, 44 or 72 pixels; its adjacent plate crop is enlarged by nearest neighbour 4×. View the image at 100% to inspect those pixel sizes.

| Proof panel | Display scale | Glyph / plate profile | Status |
| --- | --- | --- | --- |
| Target 22 px | 1.25 | Small SVG, 152/r34 plate | Proposed optical-size profile; not deployed |
| Target 44 / 72 px | 1.25 | Standard SVG, 120/r27 plate | Proposed restoration of design framing |
| Current-frame 22 / 44 / 72 px | 1.0 | Standard SVG, 120/r27 plate | Current framing, with proposed working glyph substituted |

This is a static vector illustration, with raster antialiasing and simple shade/highlight overlays. It is not a capture of live Rive playback or a proof of Rive feather, deformation, animation, compositing, or Android density handling. No native Rive files were opened or modified for these assets.

| Check | Result | Evidence / limit |
| --- | --- | --- |
| SVG purity | PASS | Single path; exact viewBox; only absolute `M/L/C/Z`; no transforms, gradients, filters, text, masks or multiple paint colours |
| Native SVG parser | PASS | Existing `svgpath.py` parsed both paths and produced well-formed RML fragments in memory; no RML files emitted |
| Connected eye | PASS | One closed contour; 400×400 alpha-threshold raster flood fill found one connected component |
| Raster decoding | PASS | Both SVGs decoded with CairoSVG; comparison PNG decoded with Pillow |
| Nominal gaze containment | PASS | Numeric clearance table above; no extra saccade offsets included |
| Frozen identity | PASS | Existing body and thinking SVG files unchanged; preview uses one body and one plate/eye |
| Designer visual review | PASS | Working has a vertical notched silhouette; thinking remains a horizontal dash; notch is visible in the target 44 px static rendering |
| Five-person working/thinking confusion <10% | PENDING | No participants or independent recognition trial; do not use designer review as a substitute |
| Runtime integration / playback | NOT RUN | Proposal assets only |

## Recheck checklist

1. Parse each SVG as XML: root viewBox exact; one `path`; attributes only `d` and `fill`; fill `#111111`.
2. Require a single `M` and `Z`, commands from `M/L/C/Z` only, and one closed contour from `svgpath.parse_path`.
3. Decode both assets, flood-fill nontransparent ink, and require one component.
4. Repeat the plate-local rounded-square containment sweep before widening gaze or adding a pupil/saccade offset.
5. Inspect `working-comparison.png` at 100%; preserve the distinction between current and proposed size profiles.
6. Run the independent five-person recognition check before accepting the confusion gate.

After each file write: one eye on a plate in the proof; no limbs; unchanged identity body; no added halo; exact existing runtime keys preserved; SVG purity retained.

## Asset hashes

- `glyph-working.svg` SHA-256: `e2b2b71a8d84db7b07b411b60929373ee7d3a646c8669206a60ee2cc9481e9cb`
- `glyph-working-small.svg` SHA-256: `8f2bc123b331a7087dfa6511f38dc17e987e1981bd954ce802ed793462893f45`
