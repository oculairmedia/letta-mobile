# avatar-web frontend

The reference avatar renderer: three.js + `@pixiv/three-vrm` (the reference
VRM runtime — MToon, spring bones, expressions, look-at). Speaks
`AvatarWireProtocol` v1 (see `AvatarWireProtocol.kt`); **mechanism only** —
behavior policy (blinking, idle motion, emotion mapping) lives in the Kotlin
director, so any future native renderer can replay the same command stream.

## Run the dev harness

Any static file server works (ES modules require http, not `file://`):

```bash
python -m http.server 8543 --directory frontend
# open http://localhost:8543
```

Load a `.vrm`/`.glb` via the file picker, or drop one under `assets/`
(gitignored) and drive it programmatically:

```js
window.lettaAvatar.handleCommand(JSON.stringify(
  { type: 'loadAvatar', url: '/assets/sample.vrm', format: 'vrm1', requestId: 'dev-1' }
));
```

Free test avatars: export from [VRoid Studio](https://vroid.com/en/studio), or
pixiv's sample models in the [three-vrm repo](https://github.com/pixiv/three-vrm/tree/dev/packages/three-vrm/examples/models).

## Drop-in standard animations

The renderer retargets user-provided humanoid animation clips onto the loaded VRM
at load time. Three container formats are supported (`format` on each animation
source):

| `format` | Loader | Skeleton |
|---|---|---|
| `vrma` | `loadVrmaAnimation.js` | VRM Animation (`VRMC_vrm_animation`) |
| `fbx` | `loadMixamoAnimation.js` | Mixamo FBX (`mixamorig*` bones, take `mixamo.com`) |
| `glb` | `loadGlbAnimation.js` | Mixamo-compatible GLB, prefix-less bones (`Hips`, `Spine`, …) |

`fbx` and `glb` share one retarget core (`retargetMixamoClip.js`); the only
difference is the container and whether bones carry the `mixamorig` prefix — the
core detects the prefix (`mixamorig`, numbered `mixamorig11`, or empty) from the
`*Hips` bone. A GLB with no recognizable `Hips` bone throws a clear per-source
error and the load continues (failure-tolerant, one bad file never fails the
avatar).

**Ready Player Me animation library** (<https://github.com/readyplayerme/animation-library>)
ships exactly this prefix-less GLB shape, so its clips load as `format: "glb"`.
**License:** the RPM library is under the proprietary *Ready Player Me Animation
Library License* (Ready Player Me OÜ) — redistribution to third parties is
prohibited without written consent, and use is licensed only "with Ready Player
Me Avatars". We therefore do **not** bundle or vendor these files; the format is
supported so a user can download the clips himself and drop them into his own
animation folder (`~/.letta-mobile/avatars/animations`). Nothing RPM-authored is
committed to this repo.

## Embedding contract (JCEF / WebView hosts)

- Host → renderer: call `window.lettaAvatar.handleCommand(commandJson)`.
- Renderer → host: define `window.lettaAvatarHost.onEvent(eventJson)` before
  the page loads (events also mirror into the harness log).
- The canvas clears to transparent, so pet-mode windows composite over the
  desktop.

## Vendored libraries (pinned, no CDN, no npm at build time)

| File | Source | Version | License |
|---|---|---|---|
| `vendor/three.module.min.js` + `vendor/three.core.min.js` | [three.js](https://github.com/mrdoob/three.js) | 0.180.0 | MIT |
| `vendor/loaders/GLTFLoader.js`, `vendor/utils/BufferGeometryUtils.js` | three.js examples/jsm | 0.180.0 | MIT |
| `vendor/loaders/FBXLoader.js`, `vendor/libs/fflate.module.js`, `vendor/curves/NURBSCurve.js`, `vendor/curves/NURBSUtils.js` | three.js examples/jsm (Mixamo `.fbx` import graph) | 0.180.0 | MIT |
| `vendor/three-vrm.module.min.js` | [@pixiv/three-vrm](https://github.com/pixiv/three-vrm) | 3.4.5 | MIT |
| `vendor/three-vrm-animation.module.min.js` | [@pixiv/three-vrm-animation](https://github.com/pixiv/three-vrm) (`.vrma` import) | 3.4.5 | MIT |
| `vendor/loaders/loadVrmaAnimation.js` | our thin wrapper over three-vrm-animation | — | MIT |
| `vendor/loaders/retargetMixamoClip.js` | our shared Mixamo→VRM retarget core (rig map + prefix detection + conjugation/hips-rescale/VRM0 flip), extracted from the three-vrm Mixamo example | v3.4.5 | MIT |
| `vendor/loaders/loadMixamoAnimation.js` | thin FBX front-end over `retargetMixamoClip.js`, ported from the [three-vrm Mixamo example](https://github.com/pixiv/three-vrm/blob/v3.4.5/packages/three-vrm/examples/humanoidAnimation/loadMixamoAnimation.js) | v3.4.5 | MIT |
| `vendor/loaders/loadGlbAnimation.js` | thin GLB front-end over `retargetMixamoClip.js` for Mixamo-compatible prefix-less clips (e.g. the Ready Player Me library) — our code | — | MIT |

To upgrade: fetch the same paths from jsdelivr at the new version, update this
table, and re-run the harness smoke checks (boot → `ready` event, VRM load →
`avatarLoaded`, expression buttons visibly animate).
