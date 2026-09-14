# Vector deformation / Astra Max

Design proposal against `2e11692`; no native Rive skinning or playback was run. This folder describes one vector-bone recipe, exact weights and reproducible geometry proofs. The existing eight identity SVGs remain the undeformed source paths.

| Contract | Value |
| --- | --- |
| Identity | Existing shape enum, eight unchanged 8-cubic bodies; no state selects a different family |
| Eye / card | One existing eye on one neutral card; rigid; no eye, pupil or mouth geometry is deformed by the body |
| Coordinate space | Local body origin (0,0); nominal radius 150 artboard px; artboard 500×500 |
| Display proof | Proposed 1.25 scale about (250,270); at44 px multiply local distances by0.11; at72 by0.18 |
| Deformation primitive | Three vector bones bound to the existing path anchors and handles |
| Grid meaning | 6×6 sampled weight field over [−150,+150]²; not an importable Rive image mesh |
| Export | No .riv, RML, scene generator or base SVG changes; SVG overlay is reference geometry only |
| External inputs | No new VM key; S/L/H/I below are internal design parameters for existing animations |
| Plate bowl | 0 at22/44/72; the ≤3% option is dropped because the card/eye need stable geometry |

Rive's image mesh is not the proposed way to warp the vector fill. Fable should bind vector path vertices to bones, or key the equivalent eight-vertex geometry using the function below. These are equivalent implementation routes for **one** specified deformation. The same resulting path/weights must drive BodyShape, BodyShade, Gloss, tint and SoftEdge; Glow uses the same deformed boundary followed by its existing1.02 halo scale. Keep all paint settings from SPEC; do not rasterize the identity body to obtain deformation.

## One force deformation stage

The new body deformation **replaces** the existing force `squash(INFLATE_NODE, …)` result during success/error/drag. Set that inherited force node scale to1 when applying these weighted paths/bones. Do not multiply the existing1.10 landing squash by another1.10 deformer. Preserve root hop/translation, face expression, facing mechanism and trail ownership. Decorators and breath inflate pause while the force stage owns the body. When released, return the force stage to identity before sustained inflate resumes.

| Parameter | Old implementation | Proposed value | Meaning / owner |
| --- | --- | --- | --- |
| Success primary S |1.06 crouch; .93 ascent;1 apex; .96 fall;1.10 land | same | Existing success envelope, now applied once inside bone transforms |
| Success local L |0 |+.008 crouch;−.008 ascent;0 apex;−.006 fall;+.012 land | Adds a crown/base distribution to the same envelope |
| Drag | X1.08, Y.92; area .9936× | S=1/.92=1.086956521739; L=−.008; H in[−.015,+.015] | Vertical compression with horizontal spread; not upward stretching |
| Error flash | X1.07/.97/1.04/1, reciprocal Y | same S; L=0,H=0 | Preserve existing motion; no extra local layer during the shake |
| Ordinary breath | I1→1.06→1;6500ms | I1→1.03→1;4600ms | Intentional radial inflation; area reaches1.0609× rather than1.1236× |
| Ordinary breath phases |3575/2925ms |2530/2070ms |55% inhale /45% exhale; root0→−11→0 unchanged |
| Sleep breath | I.985→1.045→.985;9000ms | I.985→1.015→.985;6800ms | Area relative to neutral .970225→1.030225→.970225 |
| Sleep phases |4950/4050ms |3740/3060ms |55%/45%; root0→−11→0; separate sleeping tint unchanged |
| Plate bowl | none |0 | Keep card and every face layer rigid |
| Quantified stretch bound | uniform scale values only | anchor displacement≤18px (12% of radius150) | Bound measured on all identities; not a bound on differential strain |

`S` = primary X scale; `L` = local crown/base X delta; `H` = shear coefficient; `I` = independent radial inflate. All are animation implementation parameters, not external contract names. During force poses `I=1`. At22 px, use the existing reduced decorative profile: no breath translation or local L/H variation; semantic force beats may retain their primary S shape cue. This pack's 44/72 proofs use the full profile.

## Exact deformation math

| Bone / symbol | Bind origin | X coefficient | Raw Y coefficient | Raw global bone origin |
| --- | --- | --- | --- | --- |
| crown |(0,−150) |S−L |1/S |(−150H,−150/S) |
| middle |(0,0) |S |1/S |(0,0) |
| base |(0,+150) |S+L |1/S |(+150H,+150/S) |

At anchor `(x,y)`, compute `wc=clamp(−y/150,0,1)`, `wb=clamp(y/150,0,1)`, `wm=1−wc−wb`. Thus `wc+wm+wb=1` and every weight lies in[0,1]. Each bone scales X by S+δ (δ=−L/0/+L) and Y by1/S, with its global origin translated as in the table. H translates the crown/base in opposite X directions; it is a shear-like displacement field without an assumed native skew property. Origins in the table account for the rest bone pivot; do not apply a second pivot translation.

For each of the anchor's three points **p=(anchor,in-handle,out-handle)**, use the anchor's same frozen rest weights:

`a=wc*(S−L)+wm*S+wb*(S+L)`

`tx=H*(−150*wc+150*wb)`

`raw(p)=(a*p.x + tx, p.y/S)`

Do not independently sample weights at the handles: doing so breaks the mirrored-tangent body contract. Applying the same affine matrix to all three points preserves their mirrored relation and yields one closed path with exactly8 cubic segments. Every identity uses its existing vertex order, including its distinct start vertex.

Local band scaling slightly changes total planar area. Compute the raw cubic path area with the exact polynomial boundary integral `A=1/2∮(x dy−y dx)`; set `q=Arest/Araw`, then `final(p)=I*(raw(p).x, q*raw(p).y)`. The supplied `area()` and `deform()` are the mechanical reference. This preserves **2D area**, not 3D volume. Recompute q from the selected identity at every sampled pose; do not copy blob q to the other families. A force pose has final area Arest. Deliberate inflate has final area `I²*Arest`.

For a bone implementation, apply q once as the shared output Y scale of this deformation stage. For keyed eight-vertex paths, precompute final anchors and handles per key and evaluate/normalize each in-between pose if exact area is required. Plain coordinate interpolation may have a small intermediate area residual; this proof tests parameter interpolation followed by q, and does not certify unnormalized runtime path interpolation.

The raw crown/base variation is the actual local deformation: at land, upper points use X≈1.088 and lower points X≈1.112 while the middle uses1.10. A single uniform X/Y transform cannot reproduce those three scales. No new lobe, accessory, limb or silhouette family is introduced.

## Success keyframes / 800ms plus120ms return

The brief's contradictory “tall anticipation” table is resolved in favour of the implemented crouch→ascent→land envelope. Crouch widens; ascent narrows; landing widens. Root Y values remain the existing −48px hop sequence.

| time % | design ms | root Y px | old S | new S | L | H | I | easing to next deformation key |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
|0 |0 |0 |1 |1 |0 |0 |1 |cubic-bezier .3 0 .8 .15 |
|10 |80 |+6 |1.06 |1.06 |+.008 |0 |1 |cubic-bezier 0 0 0 1 |
|22 |176 |root hop interpolation |.93 |.93 |−.008 |0 |1 |cubic-bezier .22 1 .36 1 |
|37.5 |300 |−48 |1 |1 |0 |0 |1 |cubic-bezier .3 0 .8 .15 |
|60 |480 |root hop interpolation |.96 |.96 |−.006 |0 |1 |cubic-bezier .4 0 1 1 |
|70 |560 |+4 |1.10 |1.10 |+.012 |0 |1 |cubic-bezier .22 1 .36 1 |
|87.5 |700 |−1 |interpolated |1.02 |+.003 |0 |1 |cubic-bezier .2 0 0 1 |
|100 |800 |0 |1 |1 |0 |0 |1 |return120ms, cubic-bezier .22 1 .36 1 |

The old landing scale uses an elastic easing; the proposed deformation uses the explicit700ms settle key and bounded Soft out. This keeps deformation progress in[0,1] and makes the12% displacement gate meaningful. Do not add an uncapped elastic overshoot after this stage. Root hop, its existing curves, facing spin and trails retain Fable ownership. Milliseconds above are design keys: quantize once to the current60fps timeline using `round(ms*60/1000)`;80/176ms become83.333/183.333ms. Do not round each interval separately.

| Drag phase | duration ms | S/L/H/I target | cubic-bezier | Note |
| --- | --- | --- | --- | --- |
| held enter |80 |1/.92 /−.008 /0 /1 |.16 1 .3 1 | Claim force stage; inherited breath/force scale reset1 |
| pointer response while held |100 |same S/L; H=clamp(normalized horizontal velocity,−1,1)*.015 |.2 0 0 1 | Use existing velocity signal if available; otherwise H=0; no new host key |
| held exit |350 |1 /0 /0 /1 |.22 1 .36 1 | Return geometry first; existing rotation release retains ownership |

| Breath stage | duration ms | values | cubic-bezier |
| --- | --- | --- | --- |
| ordinary inhale |2530 |I1→1.03; root Y0→−11 |.37 0 .63 1 |
| ordinary exhale |2070 |I1.03→1; root Y−11→0 |.37 0 .63 1 |
| sleeping inhale |3740 |I.985→1.015; root Y0→−11 |.37 0 .63 1 |
| sleeping exhale |3060 |I1.015→.985; root Y−11→0 |.37 0 .63 1 |

The periods and amplitudes above are the user's design targets plus this pack's local distribution choices. They are not research-derived physiological constants. “Area preserving” is supported by the computed geometry; the cited animation principles in the parent brief do not prescribe these coefficients.

## Reproduction and limits

Run `python art/mesh/validate_deformation.py` from the mascot project to regenerate the overlay, numbered PNG and tables. Run with `--check` to verify exact bytes without rewriting. Python uses numpy, Pillow and CairoSVG. No Rive executable is called; existing `svgpath.body_vertices()` validates the original geometry.

`deformation-extremes.png` shows land, vertically compressed drag and inhale at500×500 plus native44/72px thumbnails, then the numbered lattice and all eight identities under the same land pose. The neutral card/idle eye is held constant to isolate body deformation; these are not full state-expression previews. Grey contours show the selected identity's rest outline at the same root position. The source SVG overlay uses only paths, fills/strokes, one colour, a centred viewBox and no text/gradients/filters/masks/transforms; the index numbers are in this table and the PNG.

The preview paint is a raster approximation for shape judgement. It does not validate Rive feathering, actual bone binding, image meshes, composed host/state lean, facing foreshortening, root rotation, trails, blending or the app's display scale. The body margin proof is neutral-facing only. Fable must check composed runtime bounds after import. No claim of blind human recognition is made.

Endpoint poses include all named extremes; success interpolation is checked at41 parameter samples per segment. That covers bounded easing progress samples, not every real-valued point in a continuum. Self-intersection checking uses sampled boundaries; the exact area calculation is analytic for the cubic curves. Generated metrics and all8 base-file hashes follow.
<!-- BEGIN GENERATED METRICS -->

### Numbered lattice / exact row-major mapping

`index = row*6 + column`. Local x/y in artboard px; origin is the unchanged body origin. Coordinates are a sampling field, not 36 imported body vertices. Rounded printed weights are for review; the formula and Python double precision values are authoritative.

| index | row | column | x | y | crown | middle | base |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0 | 0 | 0 | -150 | -150 | 1.000000 | 0.000000 | 0.000000 |
| 1 | 0 | 1 | -90 | -150 | 1.000000 | 0.000000 | 0.000000 |
| 2 | 0 | 2 | -30 | -150 | 1.000000 | 0.000000 | 0.000000 |
| 3 | 0 | 3 | 30 | -150 | 1.000000 | 0.000000 | 0.000000 |
| 4 | 0 | 4 | 90 | -150 | 1.000000 | 0.000000 | 0.000000 |
| 5 | 0 | 5 | 150 | -150 | 1.000000 | 0.000000 | 0.000000 |
| 6 | 1 | 0 | -150 | -90 | 0.600000 | 0.400000 | 0.000000 |
| 7 | 1 | 1 | -90 | -90 | 0.600000 | 0.400000 | 0.000000 |
| 8 | 1 | 2 | -30 | -90 | 0.600000 | 0.400000 | 0.000000 |
| 9 | 1 | 3 | 30 | -90 | 0.600000 | 0.400000 | 0.000000 |
| 10 | 1 | 4 | 90 | -90 | 0.600000 | 0.400000 | 0.000000 |
| 11 | 1 | 5 | 150 | -90 | 0.600000 | 0.400000 | 0.000000 |
| 12 | 2 | 0 | -150 | -30 | 0.200000 | 0.800000 | 0.000000 |
| 13 | 2 | 1 | -90 | -30 | 0.200000 | 0.800000 | 0.000000 |
| 14 | 2 | 2 | -30 | -30 | 0.200000 | 0.800000 | 0.000000 |
| 15 | 2 | 3 | 30 | -30 | 0.200000 | 0.800000 | 0.000000 |
| 16 | 2 | 4 | 90 | -30 | 0.200000 | 0.800000 | 0.000000 |
| 17 | 2 | 5 | 150 | -30 | 0.200000 | 0.800000 | 0.000000 |
| 18 | 3 | 0 | -150 | 30 | 0.000000 | 0.800000 | 0.200000 |
| 19 | 3 | 1 | -90 | 30 | 0.000000 | 0.800000 | 0.200000 |
| 20 | 3 | 2 | -30 | 30 | 0.000000 | 0.800000 | 0.200000 |
| 21 | 3 | 3 | 30 | 30 | 0.000000 | 0.800000 | 0.200000 |
| 22 | 3 | 4 | 90 | 30 | 0.000000 | 0.800000 | 0.200000 |
| 23 | 3 | 5 | 150 | 30 | 0.000000 | 0.800000 | 0.200000 |
| 24 | 4 | 0 | -150 | 90 | 0.000000 | 0.400000 | 0.600000 |
| 25 | 4 | 1 | -90 | 90 | 0.000000 | 0.400000 | 0.600000 |
| 26 | 4 | 2 | -30 | 90 | 0.000000 | 0.400000 | 0.600000 |
| 27 | 4 | 3 | 30 | 90 | 0.000000 | 0.400000 | 0.600000 |
| 28 | 4 | 4 | 90 | 90 | 0.000000 | 0.400000 | 0.600000 |
| 29 | 4 | 5 | 150 | 90 | 0.000000 | 0.400000 | 0.600000 |
| 30 | 5 | 0 | -150 | 150 | 0.000000 | 0.000000 | 1.000000 |
| 31 | 5 | 1 | -90 | 150 | 0.000000 | 0.000000 | 1.000000 |
| 32 | 5 | 2 | -30 | 150 | 0.000000 | 0.000000 | 1.000000 |
| 33 | 5 | 3 | 30 | 150 | 0.000000 | 0.000000 | 1.000000 |
| 34 | 5 | 4 | 90 | 150 | 0.000000 | 0.000000 | 1.000000 |
| 35 | 5 | 5 | 150 | 150 | 0.000000 | 0.000000 | 1.000000 |

### Weights of the actual eight vertices, every identity

Index is the existing SVG M/start vertex followed by its seven next cubic endpoints. Both tangent handles use exactly the owning anchor's weights, including handles outside ±150. No nearest-grid interpolation is performed.

| identity SVG stem | vertex | x | y | crown | middle | base |
| --- | --- | --- | --- | --- | --- | --- |
| body-blob | 0 | -5.1346 | -147.6869 | 0.984579333 | 0.015420667 | 0.000000000 |
| body-blob | 1 | 96.1502 | -104.3418 | 0.695612000 | 0.304388000 | 0.000000000 |
| body-blob | 2 | 149.6158 | -3.0570 | 0.020380000 | 0.979620000 | 0.000000000 |
| body-blob | 3 | 97.9230 | 100.0006 | 0.000000000 | 0.333329333 | 0.666670667 |
| body-blob | 4 | -5.1346 | 149.7953 | 0.000000000 | 0.001364667 | 0.998635333 |
| body-blob | 5 | -108.3175 | 100.1259 | 0.000000000 | 0.332494000 | 0.667506000 |
| body-blob | 6 | -147.8664 | -3.0570 | 0.020380000 | 0.979620000 | 0.000000000 |
| body-blob | 7 | -118.3127 | -116.2350 | 0.774900000 | 0.225100000 | 0.000000000 |
| body-circle | 0 | 0.0000 | -150.0000 | 1.000000000 | 0.000000000 | 0.000000000 |
| body-circle | 1 | 106.0660 | -106.0660 | 0.707106667 | 0.292893333 | 0.000000000 |
| body-circle | 2 | 150.0000 | 0.0000 | 0.000000000 | 1.000000000 | 0.000000000 |
| body-circle | 3 | 106.0660 | 106.0660 | 0.000000000 | 0.292893333 | 0.707106667 |
| body-circle | 4 | 0.0000 | 150.0000 | 0.000000000 | 0.000000000 | 1.000000000 |
| body-circle | 5 | -106.0660 | 106.0660 | 0.000000000 | 0.292893333 | 0.707106667 |
| body-circle | 6 | -150.0000 | 0.0000 | 0.000000000 | 1.000000000 | 0.000000000 |
| body-circle | 7 | -106.0660 | -106.0660 | 0.707106667 | 0.292893333 | 0.000000000 |
| body-cloud | 0 | 0.0000 | -149.1772 | 0.994514667 | 0.005485333 | 0.000000000 |
| body-cloud | 1 | 103.4407 | -99.3536 | 0.662357333 | 0.337642667 | 0.000000000 |
| body-cloud | 2 | 149.1772 | 4.0870 | 0.000000000 | 0.972753333 | 0.027246667 |
| body-cloud | 3 | 107.5277 | 111.6148 | 0.000000000 | 0.255901333 | 0.744098667 |
| body-cloud | 4 | 0.0000 | 149.1772 | 0.000000000 | 0.005485333 | 0.994514667 |
| body-cloud | 5 | -107.5277 | 111.6148 | 0.000000000 | 0.255901333 | 0.744098667 |
| body-cloud | 6 | -149.1772 | 4.0870 | 0.000000000 | 0.972753333 | 0.027246667 |
| body-cloud | 7 | -103.4407 | -99.3536 | 0.662357333 | 0.337642667 | 0.000000000 |
| body-drop | 0 | 0.0000 | -149.7124 | 0.998082667 | 0.001917333 | 0.000000000 |
| body-drop | 1 | 110.6407 | -101.0846 | 0.673897333 | 0.326102667 | 0.000000000 |
| body-drop | 2 | 149.7124 | 9.5561 | 0.000000000 | 0.936292667 | 0.063707333 |
| body-drop | 3 | 101.0846 | 110.6407 | 0.000000000 | 0.262395333 | 0.737604667 |
| body-drop | 4 | 0.0000 | 149.7124 | 0.000000000 | 0.001917333 | 0.998082667 |
| body-drop | 5 | -101.0846 | 110.6407 | 0.000000000 | 0.262395333 | 0.737604667 |
| body-drop | 6 | -149.7124 | 9.5561 | 0.000000000 | 0.936292667 | 0.063707333 |
| body-drop | 7 | -110.6407 | -101.0846 | 0.673897333 | 0.326102667 | 0.000000000 |
| body-hexagon | 0 | 0.0000 | -142.0000 | 0.946666667 | 0.053333333 | 0.000000000 |
| body-hexagon | 1 | 103.2376 | -103.2376 | 0.688250667 | 0.311749333 | 0.000000000 |
| body-hexagon | 2 | 150.0000 | 0.0000 | 0.000000000 | 1.000000000 | 0.000000000 |
| body-hexagon | 3 | 103.2376 | 103.2376 | 0.000000000 | 0.311749333 | 0.688250667 |
| body-hexagon | 4 | 0.0000 | 142.0000 | 0.000000000 | 0.053333333 | 0.946666667 |
| body-hexagon | 5 | -103.2376 | 103.2376 | 0.000000000 | 0.311749333 | 0.688250667 |
| body-hexagon | 6 | -150.0000 | 0.0000 | 0.000000000 | 1.000000000 | 0.000000000 |
| body-hexagon | 7 | -103.2376 | -103.2376 | 0.688250667 | 0.311749333 | 0.000000000 |
| body-pill | 0 | 0.0000 | -150.0000 | 1.000000000 | 0.000000000 | 0.000000000 |
| body-pill | 1 | 84.8528 | -106.0660 | 0.707106667 | 0.292893333 | 0.000000000 |
| body-pill | 2 | 120.0000 | 0.0000 | 0.000000000 | 1.000000000 | 0.000000000 |
| body-pill | 3 | 84.8528 | 106.0660 | 0.000000000 | 0.292893333 | 0.707106667 |
| body-pill | 4 | 0.0000 | 150.0000 | 0.000000000 | 0.000000000 | 1.000000000 |
| body-pill | 5 | -84.8528 | 106.0660 | 0.000000000 | 0.292893333 | 0.707106667 |
| body-pill | 6 | -120.0000 | 0.0000 | 0.000000000 | 1.000000000 | 0.000000000 |
| body-pill | 7 | -84.8528 | -106.0660 | 0.707106667 | 0.292893333 | 0.000000000 |
| body-squircle | 0 | 0.0000 | -150.0000 | 1.000000000 | 0.000000000 | 0.000000000 |
| body-squircle | 1 | 118.0000 | -118.0000 | 0.786666667 | 0.213333333 | 0.000000000 |
| body-squircle | 2 | 150.0000 | 0.0000 | 0.000000000 | 1.000000000 | 0.000000000 |
| body-squircle | 3 | 118.0000 | 118.0000 | 0.000000000 | 0.213333333 | 0.786666667 |
| body-squircle | 4 | 0.0000 | 150.0000 | 0.000000000 | 0.000000000 | 1.000000000 |
| body-squircle | 5 | -118.0000 | 118.0000 | 0.000000000 | 0.213333333 | 0.786666667 |
| body-squircle | 6 | -150.0000 | 0.0000 | 0.000000000 | 1.000000000 | 0.000000000 |
| body-squircle | 7 | -118.0000 | -118.0000 | 0.786666667 | 0.213333333 | 0.000000000 |
| body-triangle | 0 | 0.0000 | -147.1607 | 0.981071333 | 0.018928667 | 0.000000000 |
| body-triangle | 1 | 98.2354 | -86.5896 | 0.577264000 | 0.422736000 | 0.000000000 |
| body-triangle | 2 | 147.1607 | 11.6458 | 0.000000000 | 0.922361333 | 0.077638667 |
| body-triangle | 3 | 109.8812 | 121.5270 | 0.000000000 | 0.189820000 | 0.810180000 |
| body-triangle | 4 | 0.0000 | 147.1607 | 0.000000000 | 0.018928667 | 0.981071333 |
| body-triangle | 5 | -109.8812 | 121.5270 | 0.000000000 | 0.189820000 | 0.810180000 |
| body-triangle | 6 | -147.1607 | 11.6458 | 0.000000000 | 0.922361333 | 0.077638667 |
| body-triangle | 7 | -98.2354 | -86.5896 | 0.577264000 | 0.422736000 | 0.000000000 |

### Measured extremes

Artboard 500²; **proposed** display scale 1.25 about (250,270), replacing the reviewed implementation's 1.0. Filled boundary is checked at neutral facing, zero rotation and root x ±24 px. Root y uses the named pose. Margin excludes halo, feather, plate, facing foreshortening, base-pivot lean, composed rotations and ghost trails. Those compositions need a native runtime check. Deformation displacement is the maximum anchor displacement divided by reference radius 150, **not** a claim about the largest local differential strain.

| identity | pose | area correction q | area change % | max anchor displacement /150 % | minimum frame margin px |
| --- | --- | --- | --- | --- | --- |
| body-blob | land | 1.000251894 | +0.000000 | 10.0278 | 14.280 |
| body-blob | drag-right | 0.999817805 | -0.000000 | 10.5362 | 16.160 |
| body-blob | inflate | 1.000000000 | +6.090000 | 3.3171 | 27.327 |
| body-circle | land | 1.000000000 | +0.000000 | 10.0084 | 13.750 |
| body-circle | drag-right | 1.000000000 | +0.000000 | 9.4817 | 16.196 |
| body-circle | inflate | 1.000000000 | +6.090000 | 3.0000 | 26.875 |
| body-cloud | land | 0.999613195 | +0.000000 | 10.3483 | 13.728 |
| body-cloud | drag-right | 1.000261134 | +0.000000 | 9.1187 | 16.189 |
| body-cloud | inflate | 1.000000000 | +6.090000 | 3.0997 | 26.873 |
| body-drop | land | 0.999989438 | +0.000000 | 10.0738 | 13.630 |
| body-drop | drag-right | 1.000007126 | -0.000000 | 9.5000 | 16.129 |
| body-drop | inflate | 1.000000000 | +6.090000 | 3.0003 | 26.875 |
| body-hexagon | land | 1.000000000 | -0.000000 | 10.0000 | 13.750 |
| body-hexagon | drag-right | 1.000000000 | +0.000000 | 9.2206 | 16.196 |
| body-hexagon | inflate | 1.000000000 | +6.090000 | 3.0000 | 26.875 |
| body-pill | land | 1.000000000 | -0.000000 | 9.0909 | 54.545 |
| body-pill | drag-right | 1.000000000 | +0.000000 | 8.4667 | 56.957 |
| body-pill | inflate | 1.000000000 | +6.090000 | 3.0000 | 50.625 |
| body-squircle | land | 1.000000000 | +0.000000 | 11.1921 | 13.750 |
| body-squircle | drag-right | 1.000000000 | +0.000000 | 10.5888 | 16.196 |
| body-squircle | inflate | 1.000000000 | +6.090000 | 3.3375 | 26.875 |
| body-triangle | land | 0.999125594 | -0.000000 | 10.9455 | 13.397 |
| body-triangle | drag-right | 1.000590798 | +0.000000 | 9.5916 | 15.978 |
| body-triangle | inflate | 1.000000000 | +6.090000 | 3.2767 | 26.875 |

| gate | result | value |
| --- | --- | --- |
| Existing body inputs unchanged | PASS | 8 of 8 hashes below |
| Same one closed, 8-cubic topology and mirrored handles | PASS | 104 endpoint cases |
| No sampled self-intersections | PASS | 2400 poses including success interpolation |
| Force-pose area preserved; deliberate inflate area reported | PASS | maximum integration residual 6.661e-14 percentage points |
| Max anchor displacement ≤12% of 150 px | PASS | 11.1921% over endpoint poses |
| Filled endpoint boundaries inside frame under stated sweep | PASS | minimum margin 12.499 px |
| 44/72 exact raster dimensions | PASS | three named extremes at both sizes in deformation-extremes.png |
| Native vector skinning, painted edge, trails and combined facing | NOT RUN | Fable implementation/runtime gate |

| unchanged body file | SHA256 |
| --- | --- |
| body-blob.svg | `95a8a24987c841ceea43e18ddd4483218fda67e631c91caeca3245268c9c195d` |
| body-circle.svg | `0d19784f0e92755a16e30091c2f67610951a7dd68c6022524906d28a7fd31de3` |
| body-cloud.svg | `cced4ca3c63f63f5f98c726aa87d8851252a77b607989ed485c5301543abb86f` |
| body-drop.svg | `e9c50c77c3e3123fc33d5924a9f5ba23b1fb5af058f8155e2902ddf9c185dfc1` |
| body-hexagon.svg | `9131ddc5e47b82a9acc6359755f14485e48dbbc59059fab568b88a3fcef9237e` |
| body-pill.svg | `a4731f395a3a62e7316744de84896d5f6cd1f039a46f2f08ef0da2acdf284316` |
| body-squircle.svg | `a74c60085f4ce7cdbb95d7993c701a0b96aa2c55de76a819bdf520c0db6c1e32` |
| body-triangle.svg | `34025623ecebdb799901ae5ba04d631a61a662a946ad683eabb8b3b69ebd2e22` |

<!-- END GENERATED METRICS -->
