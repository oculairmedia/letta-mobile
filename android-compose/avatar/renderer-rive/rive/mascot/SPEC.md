# Mascot — locked art / Fable ingestion contract

## 1. Per-state table

| Coordinate / ingestion rule | Value |
| --- | --- |
| Source | Attached one-eye low-glow sheet; named /workspace/mascot-lock reference files absent in this environment; supplied locked brief overrides older state-body morphs in ARTIST.md |
| Artboard / state machine / view model | `Mascot` / `Avatar` / `Avatar` |
| Artboard size | 500 × 500 px; +x right; +y down; angles below in degrees, convert to radians for RML |
| Body origin | (250,270); body SVG (0,0) lands here |
| Plate origin | (250,254); preserve FacePlacement (250,262), nested Plate offset (-100,-108), PlateRoot (100,100) |
| Hollow contours | Outer and inner ring contours have opposite winding; fill-rule evenodd is also supplied. Preserve both contours in one glyph; no second pupil object. |
| Asset import | Import path coordinates, not SVG viewBox fitting; 1 SVG unit = 1 artboard px before §7 root display scale |
| Glyph SVG viewBox | `-50 -50 100 100`; default glyph ink ARGB `FF111111` (SVG RGB `#111111`) |
| Body SVG viewBox | `-180 -180 360 360`; one M + exactly 8 C + Z; SVG black fill is a geometry placeholder, replaced by bound `color` |
| Body family | Every row uses `body-blob.svg` as default; replace it with §1 identity lookup once on `shape` change, never on `state`/flash/drag change |
| Plate at 44/72 dp | 120 × 120 px; radius 27 px; fill `FFF7F7F7`; border width 0 px |
| Plate at 22 dp | 152 × 152 px; radius 34 px; fill `FFF7F7F7`; border width 0 px |
| Face offset | Delta from plate origin; affects plate, eye and mouth together; gaze is additional eye-only travel |
| State plate scale | Multiplies Card dimensions only; eye and mouth sizes come from assets, not inherited Card scale |
| Tint composition | Source-over overlay on bound body fill after Shade/Gloss; no state replaces identity `color` |
| Body scale during state/flash/drag | x=1.000, y=1.000; body vertices never keyed by expression; §7 display scale is separate |
| Eye visibility | Exactly one state eye at opacity 1; all other eye objects at 0; no cross-dissolve between glyphs |

| key | glyph (svg name) | body shape (svg name) | tint ARGB | plate scale | face offset x,y | body motion (what, amplitude px, period ms, loop?) | notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| idle | glyph-idle.svg | body-blob.svg | 00000000 | 1.000 | 0,0 | y sine ±1; 4600; yes | sustained; expr=0; plate rotation 0°; mouth hidden |
| listening | glyph-listening.svg | body-blob.svg | 00000000 | 1.040 | 0,-3 | y sine ±1; 4600; yes | sustained; expr=1; solid enlarged square; plate rotation -2°; mouth hidden |
| thinking | glyph-thinking.svg | body-blob.svg | 00000000 | 1.000 | 0,0 | x sine ±2; 3200; yes | sustained; expr=3; plate rotation -6°; default gaze (-0.35,-0.35); mouth hidden |
| waitingInput | glyph-waitingInput.svg | body-blob.svg | 00000000 | 1.060 | 0,-2 | y sine ±2; 1200; yes | sustained; expr=4; ring eye; plate rotation 0°; glyph-mouth-o.svg fixed |
| speaking | glyph-speaking.svg | body-blob.svg | 00000000 | 1.000 | 0,0 | y sine ±1; 4600; yes | sustained; expr=5; solid square eye; Mouth exclusively follows mouthOpen; nod §7 |
| error | glyph-error.svg | body-blob.svg | 14000000 | 1.000 | 0,4 | y offset +5; 0; no | sustained; expr=7; diamond eye; plate rotation +5°; glyph-mouth-frown.svg |
| sleeping | glyph-sleeping.svg | body-blob.svg | 38000000 | 1.000 | 0,4 | y sine ±1; 6800; yes | sustained; expr=8; lower cup eye; plate rotation +3°; mouth hidden; ordinary blink/glance suppressed |
| loading | glyph-loading.svg | body-blob.svg | 10000000 | 1.000 | 0,0 | translation 0; 0; no | lifecycle chrome; expr=9; dot; Gloss alpha multiplier 0.8→1.0→0.8 over 1400 ms loop; no mouth |
| failed | glyph-failed.svg | body-blob.svg | 66808080 | 1.000 | 0,0 | translation 0; 0; no | lifecycle chrome; expr=10; X; no mouth; no blink/gaze; host fallback if asset cannot load |
| degraded | glyph-degraded.svg | body-blob.svg | 00000000 | 1.000 | 0,0 | translation 0; 0; no | lifecycle chrome; expr=11; bent diagonal connected eye; plate rotation +4°; no mouth; identical selected body |
| success | glyph-success.svg | body-blob.svg | 00000000 | 1.000 | 0,0 | y +2→-10→+1→0; 800; no | trigger/flash; expr=6; upper crescent; mouth hidden; §2 keyframes |
| error | glyph-error-flash.svg | body-blob.svg | 14000000 | 1.000 | 0,4 | y 0→+5; x 0→-4→+4→-2→0; 600; no | trigger/flash; expr=7; frown; §2 keyframes; distinct from sustained error row |
| dragged | glyph-dragged.svg | body-blob.svg | 00000000 | 1.000 | 0,0 | translate 0; 0; no | held boolean; expr=2; root rigid tilt ±5° cap; plate counter-tilt ±8° cap; mouthOpen drives Mouth |

| Exact `shape` key | Identity SVG | V0..V7 ordering / usage |
| --- | --- | --- |
| circle | body-circle.svg | top, NE, right, SE, bottom, SW, left, NW |
| blob | body-blob.svg | same ordering; default frozen look |
| roundedSquare | body-squircle.svg | same ordering; asset name does not rename enum key |
| pill | body-pill.svg | same ordering; width 240, height 300 |
| triangle | body-triangle.svg | same ordering; rounded radial identity, not an expression |
| hexagon | body-hexagon.svg | same ordering; rounded radial identity |
| cloud | body-cloud.svg | same ordering; shallow contour variation, no feet/ears/appendages |
| drop | body-drop.svg | same ordering; rounded crown, no sharp point |

| Body import mechanics | Exact operation |
| --- | --- |
| Path topology | V0 is SVG M; V1..V7 are first seven C endpoints; eighth C endpoint equals V0; do not create a ninth vertex |
| Tangents for vertex i | outgoing = C[i].control1 - V[i]; incoming = V[i] - C[i-1].control2 (wrap at 0); equal to within 0.0002 px rounding |
| Rive mirrored vertex | x,y=V[i]; rotation=atan2(outgoing.y,outgoing.x); distance=hypot(outgoing.x,outgoing.y) |
| Existing geometry IDs | Body V0..V7 = `0:300`..`0:307`; Halo V0..V7 = `0:320`..`0:327`; BodyShape `0:90`; Halo `0:91` |
| Identity morph | If animated, interpolate corresponding coordinates and tangent vectors for 240 ms, cubic-bezier 0.22 1 0.36 1; recompute rotation/distance from vector; never interpolate angle across ±π directly |
| State changes | Disable existing state-keyed body vertex deltas and nonuniform body scales; use §2/§3 motion only; selected body remains unchanged |

| Glyph / role | Existing Plate object / ID | `expr` | Mechanical action |
| --- | --- | --- | --- |
| glyph-idle | Square / `7:30` | 0 | replace Path geometry; preserve object name/ID |
| glyph-listening | Ring / `7:31` | 1 | replace ring geometry with solid rounded square; preserve legacy object name |
| glyph-thinking; glyph-dragged | Dash / `7:32` | 3; 2 | files have identical geometry; same object |
| glyph-waitingInput | RingSmall / `7:33` | 4 | compound fill; evenodd hole |
| glyph-speaking | ArcOpen / `7:34` | 5 | replace arc by solid square; stop Open timeline from scaling this eye |
| glyph-success; glyph-degraded | Smile / `7:35` | 6; 11 | both are open M+C+C paths; key their six control/end pairs from the respective SVG at expr 6/11; retain shared PointsPath ID `0:2127` |
| glyph-error; glyph-error-flash | Diamond / `7:36` | 7 | identical path data; remove legacy nested rotation after importing already positioned path |
| glyph-sleeping | Arch / `7:37` | 8 | replace with lower cup; retain legacy name |
| glyph-loading | Dot / `7:38` | 9 | solid dot |
| glyph-failed | Cross / `7:39` | 10 | import both M subpaths as one eye symbol; no rotations in asset |
| glyph-mouth-o / glyph-mouth-closed/half/open | TellDot / `7:23` | 4 / 2,5 | reuse below-plate mouth object; expr 4 uses fixed o, expr 2/5 uses §4 ellipse morph; all other expr set opacity 0 |
| glyph-mouth-frown | FrownLine / `7:40` | 7 | preserve ID/name; reparent under PlateRoot `7:20` so eye gaze does not move the mouth |

| Back-to-front logical layer | Existing target | Exclusive owner / channel |
| --- | --- | --- |
| RootMotion | Body `0:100` and Face `0:200`, same additive root delta | state/flash root translation; drag rigid rotation; both must move together |
| Glow | Halo `0:91` | identity colour, fixed §6 opacity |
| BodyShape / BodyColor | BodyShape `0:90`, Fill `0:342` | shape identity / colour binding |
| BodyShade | Shade `0:345`, Gloss `0:349`, Tint `0:353` | fixed paint recipe; state tint; breathing Gloss only |
| PlateMotion | Face `0:200` | state base pose; local hover/nod additive, capped ±10° total |
| FacePlate | Card `7:21` | state plate scale and §7 size profile |
| EyeClip / EyeGaze | Glyphs `7:22` | blink scaleY only / gaze x,y only; never squash Card |
| EyeGlyph | one visible object `7:30`..`7:39` | `expr`; glyph swaps use shutter rule §3 |
| Mouth | TellDot `7:23` or FrownLine `7:40` | speech envelope / fixed waiting o / fixed error frown; no second eye |

| Arbitration / names | Rule |
| --- | --- |
| Behavior priority | dragged > error > waitingInput > speaking > success > thinking > listening > idle |
| Sleeping gate | suppress all behavior except dragged and error |
| Lifecycle | loading/failed/degraded remain host surface chrome; do not add behavior priorities or emit new director commands |
| Preserved external names | state, success, error, dragged, mouthOpen, lookX, lookY, blink, color, shape, hovered |
| Preserved component names | Plate inputs expr, blink; timelines LookX, LookY, Open; retain ARTIST.md object names and IDs |
| Mouth reset | Every expr keys both mouth object opacities; no state inherits the prior state's mouth |

## 2. Flash table

| key | duration_ms | keyframes (time %; body x,y; body scale x,y; plate rotation; glyph) | segment cubic-bezier x1 y1 x2 y2 | returns to |
| --- | --- | --- | --- | --- |
| success | 800 | 0%; 0,0; 1,1; 0°; glyph-success.svg / 10%; 0,2; 1,1; -2°; glyph-success.svg / 37.5%; 0,-10; 1,1; +2°; glyph-success.svg / 70%; 0,1; 1,1; -1°; glyph-success.svg / 100%; 0,0; 1,1; 0°; glyph-success.svg | 0→10: 0.4 0 1 1; 10→37.5: 0.16 1 0.3 1; 37.5→70: 0.4 0 1 1; 70→100: 0.22 1 0.36 1 | current live sustained state after §3 120 ms return; not a captured old state |
| error | 600 | 0%; 0,0; 1,1; 0°; glyph-error-flash.svg / 16.6667%; -4,5; 1,1; -7°; same / 33.3333%; +4,5; 1,1; +7°; same / 50%; -2,5; 1,1; -3°; same / 66.6667%; 0,5; 1,1; +5°; same / 100%; 0,5; 1,1; +5°; same | all non-hold segments: 0.4 0 0.2 1; 66.6667→100 hold | sustained error, same geometry and endpoint; no second transition delay |

| Flash rule | Value |
| --- | --- |
| Success mouth | hidden for full flash and return shutter |
| Error mouth | glyph-mouth-frown.svg; §7 size profile |
| Success interruption | Higher-priority activity cancels flash and resolves to live state; never queue stale success for later |
| Error endpoint | +5 px root y is the sustained error pose, not +5 added a second time |
| Root scaling | 1,1 throughout; no body squash; landing softness comes from translation overshoot |
| Total success time | 800 ms authored flash + 120 ms return = 920 ms until fully restored |

## 3. Transition table

| from | to | duration_ms | cubic-bezier x1 y1 x2 y2 | anticipation / target |
| --- | --- | --- | --- | --- |
| idle | listening | 200 | 0.22 1 0.36 1 | first 40 ms face y=+1, then face y=-3; eye grows to listening asset |
| listening | thinking | 300 | 0.4 0 0.2 1 | first 60 ms retain gaze, then set default gaze (-0.35,-0.35); plate -6° |
| thinking | speaking | 200 | 0.22 1 0.36 1 | gaze to 0,0 by 140 ms; neutral square; Mouth can respond immediately to amplitude |
| speaking | idle | 240 | 0.22 1 0.36 1 | close mouth over first 90 ms; body y +1 at 120 ms, then 0 |
| any | success | 0 | 0 0 1 1 | §2 includes its 80 ms anticipation; trigger enters flash immediately |
| success | live sustained | 120 | 0.22 1 0.36 1 | use current resolver output; glyph shutter below |
| any | error flash | 0 | 0 0 1 1 | §2 includes 100 ms dip/shake entry |
| error flash | error | 0 | 0 0 1 1 | same diamond/frown and +5 px body y; flash override releases |
| error | idle | 300 | 0.22 1 0.36 1 | frown opacity reaches 0 by 100 ms; root y +5→0 |
| any | sleeping | 600 | 0.4 0 0.2 1 | eye scaleY 1→0 over 360 ms, swap to lower cup at 360 ms, cup scaleY 0→1 over 240 ms; tint in 600 ms |
| sleeping | live sustained | 600 | 0.4 0 0.2 1 | lower cup scaleY 1→0 in 180 ms, swap at 180 ms, target scaleY 0→1 over 420 ms; tint out in 600 ms |
| any | waitingInput | 160 | 0.16 1 0.3 1 | first 30 ms face y +1; then -2; eye ring opens, fixed o appears |
| dragged=false | dragged=true | 80 | 0.16 1 0.3 1 | squint, mouth enabled; root tilt follows clamped velocity signal below; never change silhouette |
| dragged=true | dragged=false | 350 | 0.22 1 0.36 1 | root rotation r→-0.15r at 140 ms→0 at 350 ms; plate counter-tilt releases; restore live sustained |
| unlisted sustained pair | target sustained | 160 | 0 0 0.58 1 | ease-out; interpolate numeric pose channels; use single-eye shutter for different glyphs |

| Transition channel rule | Value |
| --- | --- |
| Glyph shutter | On a glyph change, first 30% of transition scales visible eye Y 1→0; swap geometry/visibility at 30%; remaining 70% opens new eye 0→1. Plate and body do not squash. Curves use row's tuple. |
| Immediate flash entry | For 0 ms transition rows, swap glyph instantly; no dissolve and no temporary second glyph |
| Interruptions | Start from current rendered pose, not the old state's nominal pose; canceled motion loses ownership immediately |
| Root translation cap | ±14 px x and y after adding base pose + active motion, before §7 display scale |
| Rotation cap | root ±5°; combined plate ±10°; use separate channels for base pose / hover / speech / drag |
| Frame conversion | Author timing in ms. At 60 fps, frame=round(duration_ms×60/1000); retain exact segment order; no second conversion of already-ms transition duration |

## 4. Continuous signals

| exact key | range / kind | target and numeric response | duration_ms | cubic-bezier x1 y1 x2 y2 |
| --- | --- | --- | --- | --- |
| lookX | -1..1 | Glyphs x = 7×input px at 44/72 dp; 4×input at 22 dp; plate travel 0 | 0 (scrub) | 0 0 1 1 |
| lookY | -1..1 | Glyphs y = 5×input px at 44/72 dp; 3×input at 22 dp; plate travel 0; -1 up | 0 (scrub) | 0 0 1 1 |
| mouthOpen | 0..1 | clamp; Open timeline scrubs ellipse keyframes below on TellDot only; speaking and dragged only | 0 (scrub) | 0 0 1 1 |
| blink | trigger | squash eye only (Glyphs scaleY), not wink/two-eye logic; close 55 ms, hold 25 ms, open 90 ms | 170 | close 0.4 0 1 1; open 0.16 1 0.3 1 |
| hovered | boolean | once on false→true: face rotation 0° at 0 ms, +2° at 60, -2° at 150, 0° at 240; no repeat while held | 240 | 0.4 0 0.2 1 per segment |
| color | ARGB colour | Fill and SoftEdge/Halo RGB bind to color; Shade/Gloss/Tint are neutral overlays; plate FFF7F7F7, eye/mouth FF111111 | 0 | 0 0 1 1 |

| mouthOpen sample | mouth SVG | normal geometry (px) | 22 dp geometry (px) | speaking opacity | dragged opacity |
| --- | --- | --- | --- | --- | --- |
| 0 | glyph-mouth-closed.svg | ellipse rx=10, ry=0 | glyph-mouth-closed-small.svg; rx=12, ry=0 | 0 | evaluate mouth at effective a=0.5 instead |
| 0.5 | glyph-mouth-half.svg | ellipse rx=11, ry=7 | glyph-mouth-half-small.svg; rx=15, ry=13 | 1 | 1 |
| 1 | glyph-mouth-open.svg | ellipse rx=12, ry=14 | glyph-mouth-open-small.svg; rx=18, ry=18 | 1 | 1 |

| Mouth implementation detail | Value |
| --- | --- |
| Interpolation | Closed/half/open have identical M+C+C+C+C+Z topology and point order. Linear interpolation of all coordinates at a=0,0.5,1; no scaling of eye or Card. |
| Envelope | e += (target-e)×(1-exp(-dt/tau)); dt in seconds; tau=0.045 attack, 0.090 release; e clamps 0..1 |
| Speaking opacity | 0 at a=0; smoothstep(0,0.08,a) above 0; closed asset is intentionally zero-area |
| Dragged mouth | effective a=max(0.5,e); keeps a small round/open mouth when amplitude is absent; use same Open geometry |
| Waiting mouth | glyph-mouth-o.svg; fixed outer radius 12, inner radius 5; at 22 dp use solid round glyph-mouth-o-small.svg (hole dropped) |
| Error mouth | glyph-mouth-frown.svg; single upper arc under plate; -small uses 20 px stroke |
| Mouth anchor | relative to Plate origin (0,+82) at 44/72 dp; (0,+114) at 22 dp; fixed, not affected by gaze |
| Allowed mouth expr | 2 dragged; 4 waitingInput; 5 speaking; 7 error. All other expr: both mouth objects opacity 0. |
| Relative luminance | For each sRGB channel c in 0..1: linear c = c/12.92 if c≤0.04045, otherwise ((c+0.055)/1.055)^2.4. Y=0.2126R+0.7152G+0.0722B. |
| Mouth ink on dark identity | When identity relative luminance Y<0.18, mouth ink becomes FFF7F7F7; eye stays FF111111 on white plate. Mouth SVG is still single-colour geometry. |
| Blink arbitration | App trigger and auto timer reset the same eye shutter; suppress re-entry for 170 ms. No automatic blink during sleeping/failed or a transition shutter. |
| Auto-blink interval | independent uniform wait 2.5–4.5 s after last completed blink; use milliseconds 2500–4500 |
| Drag tilt | root degrees=clamp(pointerVelocityX/120,-5,5) with velocity in artboard px/s; plate counter-tilt=clamp(-1.6×root,-8,8); if velocity unavailable, use 0° (no invented external signal) |
| Speech plate nod | §7 amplitude × e × sin(2πt/625 ms); no body pulse substitutes for Mouth |
| Gaze ownership | host lookX/lookY while actively driven; otherwise idle glance adds its specified offset; thinking default gaze only when host gaze absent; never sum two full-range gaze writers |

## 5. Idle life

| layer | value / amplitude | period_ms or interval_ms | duration_ms / phases | cubic-bezier x1 y1 x2 y2 | ownership |
| --- | --- | --- | --- | --- | --- |
| Breathing scale | body scale 1.000..1.000 (disabled deformation); root y ±1 px; Gloss opacity multiplier 0.95..1.05 | 4600 period | quarter-cycle 1150 ms, values 0,+1,0,-1,0 | 0.37 0 0.63 1 per quarter | Breath root-y/Gloss only; no eye writes |
| Sleeping breath | scale 1.000; root y ±1; Gloss multiplier 0.95..1.05 | 6800 period | quarter-cycle 1700 ms | 0.37 0 0.63 1 | overrides normal breathing period |
| Glance | normalized gaze x uniform -0.6..0.6, y -0.4..0.4; normal travel max ±4.2/±2 px; plate tilt ±2° | independent wait 4000–7000 | move 250 ms; hold uniform 400–900 ms; return 300 ms | move/return 0.22 1 0.36 1 | IdleVariety gaze/tilt only; host gaze wins |
| Blink | §4 55/25/90 ms | independent wait 2500–4500 | 170 ms total | §4 tuples | Blink eye scaleY only |
| Initial phase | breath random 0–4600 ms; glance wait independently 4000–7000; blink wait independently 2500–4500 | independent samples | 0 initialization | 0 0 1 1 | never one shared random seed phase/timeline |
| 22 dp pruning | root-y breathing amplitude 0; glance 0; tilt 0; preserve blink and Gloss breathing | same periods | same durations | same tuples | avoids subpixel drift while preserving life |

## 6. Body render

| paint / parameter | geometry / position (body-local px) | ARGB stops / opacity | compositing / binding |
| --- | --- | --- | --- |
| Light direction | upper-left; normalized direction (-0.6,-0.8) | neutral light | no fixed identity hue in light |
| SoftEdge | same body path; stroke width 8 px; feather 4 px | color RGB at effective alpha 0.08 | bound identity, behind Fill; 22 dp omit |
| Fill | exact selected identity path | bound `color`, e.g. FF79B7DF | solid identity base; SVG placeholder black must not remain |
| Shade | radial gradient center (-40,-65), end (130,120) | t=0: 00000000; t=0.55: 08000000; t=1: 40000000 | source-over neutral shade, clipped by body |
| Gloss | radial gradient center (-65,-85), end (65,45) | t=0: 52FFFFFF; t=0.45: 24FFFFFF; t=1: 00FFFFFF | source-over neutral highlight, clipped by body; idle multiplier §5 |
| Tint | same body path, above Shade/Gloss | exact per-state ARGB §1 | source-over overlay; preserve underlying color binding |
| GlassRing | width 0 px | 00FFFFFF, alpha 0 | disabled at all sizes |
| Halo | same selected path; scale 1.02; stroke width 12 px; feather 10 px | bound color RGB; opacity 0.04 normal, 0.02 sleeping, 0 failed | behind body; no particles; 22 dp opacity 0 |
| Plate shadow | Card silhouette; x=0, y=2; stroke width 4; feather 3 | 14000000 | behind Card; 22 dp omit |
| Plate fill | rounded square §1 | FFF7F7F7 | neutral; no identity binding; no bevel/extrusion |
| Eye fill/stroke | SVG authored geometry | FF111111 | one dark colour; fill-rule evenodd only for ring asset |
| Mouth fill/stroke | SVG authored geometry | FF111111; §4 dark-colour fallback FFF7F7F7 | independent of eye colour; no plate-mouth duplication |
| Palette examples | body only | blue FF79B7DF; coral FFE89488; mint FF8CCDBA; lilac FFA99CDD | identity examples, never state labels |
| Halo alpha convention | effective opacity, not two multipliers of 0.04 | 0.04 exactly | bind RGB/colour then apply one opacity factor; never multiply twice |

| Rive source-of-truth / validation | Value |
| --- | --- |
| Editor file | oculair / Shared Project / mascot / 2578084; not edited by this art-assets change |
| Scope | SPEC.md + pure art SVGs only; no scene.rml/gen_scene.py/.riv changes; no CLI regeneration or rive push |
| Gate after Fable integrates | `python ../../native/rivdump/check_contract.py . ../../src/commonMain/kotlin/com/letta/mobile/avatar/rive/RiveAvatarContract.kt` from this directory |
| Local gate availability | Current environment has no rive CLI; existing check_contract.py stops at missing /root/.rive/bin/rive.exe. This is not a passing runtime contract check. |
| Static validation | Check SVG purity, all 8 body cubics/closure/handle symmetry, required files, exact state/shape keys against Kotlin, and reference IDs against scene.rml |

## 7. Sizes

| profile | surface size_dp | root display scale around (250,270) | base Card width/height px | eye assets | mouth assets / anchor | secondary plate nod amplitude | halo / shadow / edge |
| --- | --- | --- | --- | --- | --- | --- | --- |
| small | 22 rail | 1.25 | 152 | glyph-STATE-small.svg | glyph-mouth-NAME-small.svg; (0,114) | 0° | all omitted |
| standard | 44 list | 1.25 | 120 | glyph-STATE.svg | glyph-mouth-NAME.svg; (0,82) | 1° | §6 |
| standard | 72 hero | 1.25 | 120 | glyph-STATE.svg | glyph-mouth-NAME.svg; (0,82) | 2° | §6 |

| Size rule / numeric result | 22 dp | 44 dp | 72 dp |
| --- | --- | --- | --- |
| px→dp multiplier | 0.055 | 0.11 | 0.18 |
| 300 px body span | 16.5 dp | 33 dp | 54 dp |
| idle plate span | 8.36 dp | 13.2 dp | 21.6 dp |
| idle square span | 3.08 dp | 5.28 dp | 8.64 dp |
| listening square span | 3.52 dp | 6.16 dp | 10.08 dp |
| waitingInput ring outer / hole diameter | 3.96 / 1.76 dp | 6.38 / 3.30 dp | 10.44 / 5.40 dp |
| waitingInput eye radial ink width | 1.10 dp | 1.54 dp | 2.52 dp |
| speaking mouth at a=0.5 width×height | 1.65 × 1.43 dp | 2.42 × 1.54 dp | 3.96 × 2.52 dp |
| speaking mouth at a=1 width×height | 1.98 × 1.98 dp | 2.64 × 3.08 dp | 4.32 × 5.04 dp |
| closed mouth | 0 visible pixels | 0 visible pixels | 0 visible pixels |
| waitingInput mouth | solid round 1.98 dp, hole dropped | ring 2.64 dp outer | ring 4.32 dp outer |
| success/sleeping/degraded stroke | 1.10 dp | 1.32 dp | 2.16 dp |
| failed X stroke | 1.10 dp | 1.54 dp | 2.52 dp |
| loading dot diameter | 1.54 dp | 2.42 dp | 3.96 dp |
| gaze max x/y | 0.22 / 0.165 dp | 0.77 / 0.55 dp | 1.26 / 0.90 dp |

| Small-size selection / verification | Exact rule |
| --- | --- |
| Profile switch | surface width ≤28 dp selects all -small glyphs and small Card/mouth anchor; >28 dp selects standard; root display scale stays 1.25 |
| STATE filename substitution | idle, listening, thinking, waitingInput, speaking, error, sleeping, loading, failed, degraded, success, error-flash, dragged; append -small before .svg |
| NAME filename substitution | closed, half, open, o, frown; append -small before .svg |
| Dead-detail removal | no glass ring; no halo/edge feather/plate shadow/plate nod/idle glance at 22 dp; waiting o hole dropped; waiting eye hole retained ≥1.76 dp |
| Mouth separation | small mouth center +114 from plate; largest waiting Card bottom +80.56, o top +96; minimum gap 15.44 px = 0.8492 dp before rotation; standard gap 6.4 px = 0.704 dp at 44 dp |
| Eye containment | largest small waiting eye radius 36 + gaze x4 fits half Card 80.56 with >40 px margin; no clipping at extreme gaze |
| State silhouette invariant | Each state uses identical selected body d; only identity changes body d. Profile scales are uniform and do not depend on state. |
| Review target | Render each state and mouthOpen=0/0.5/1 at exact 22/44/72 px on light/dark backgrounds, plus enlarged nearest-neighbour copies; these are SVG assembly checks, not Rive runtime claims |
| Contract boundary | Fable still owns conversion/integration and the runtime gate; this package supplies the path geometry, numeric poses, paint recipe and preserved object mapping |

## 8. Rive implementation notes (Fable, after conversion)

How the generator realises sections 1–7, and where it deviates. `README.md` in this directory
is the operating manual; `RIVE-PLATFORM-POWER.md` is the platform brief this was audited against.

| Mechanism | Implementation |
| --- | --- |
| App surface | View Model `Avatar` only (enum `state`, enum `shape`, colour `color`, numbers `mouthOpen`/`lookX`/`lookY`, triggers `success`/`error`/`blink`, booleans `dragged`/`hovered`). No legacy inputs at the root. |
| Plate component | Nested artboard driven by inputs `expr` (number) and `blink` (trigger) from the root's animations, because a view-model-driven machine inside a nested artboard never fires. `LookX`/`LookY`/`Open` are pose-range timelines scrubbed through `NestedRemapAnimation.time` with range-mapper converters bound to the numbers (§4 "0 ms scrub"). |
| Glyphs | A `Solo` (`Glyphs`): one keyed reference picks the drawn glyph per `expr`; no opacity stack. Mouths stay opacity-switched (§4 "allowed mouth expr"). |
| Sustained states (§1) | One looping `State<X>` animation per key on the root `Expression` layer; body motion on `Body` and `Face` together, plate rotation/offset on `Face`, tint colour, gloss opacity, `expr`, and the facing (below). |
| Transitions (§3) | Every sustained→sustained change runs an `Enter_<from>_<to>` one-shot. The five designed rows carry their anticipation/target keys; unlisted pairs use the target's default (160 ms ease-out; sleeping 600 ms standard; waitingInput 160 ms spring) and blend the hand-off. |
| Glyph shutter (§3 rule) | Realised as the plate blink (55/25/90 ms, §4) fired at the entry's frame 0, with the glyph reference flipped at frame 3 while the eye is shut — not the 30 %/70 % proportional shutter. Same read, one mechanism, and it also covers the flash returns. |
| Flashes (§2) | `Flash` layer, self-returning; success adds a facing spin with two trailing ghost plates. |
| Facing (not in SPEC; product ask) | A `Joystick` (`Facing`) scrubs `TurnX`/`TurnY`: the plate slides ±70/±24 px and foreshortens, the body rotates ±6° and squeezes. Each state has a facing; entries turn to it; a `Wander` layer glances/peeks/spins on its own every 6–12 s. |
| Idle life (§5) | `Breath` (gloss), `IdleVariety` (glance on the plate, not the glyph — the root cannot key a nested node), `AutoBlink` inside the Plate. Random waits are two-state alternations with `random` selection. |
| Body render (§6) | Fill bound to `color`; `Shade`/`Gloss`/`Tint` neutral overlays; `SoftEdge`/`Halo` are feathered *strokes* bound to `color` — the CLI cannot feather a fill; the editor pass can. |
| Sizes (§7) | Standard profile only. The `-small` profile needs a host size signal the contract does not carry. |
| Smoothing | None in-file. The host envelope in §4 owns `mouthOpen` smoothing; adding a converter interpolator would double-smooth. |
| Not used, by choice | Blend states (the scrubbed pose ranges are the equivalent and are what the CLI can verify headless); scripts; runtime events; nested view models; `reduceMotion` (needs a contract change — filed as follow-up). |
