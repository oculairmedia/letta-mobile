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
| `vendor/three-vrm.module.min.js` | [@pixiv/three-vrm](https://github.com/pixiv/three-vrm) | 3.4.5 | MIT |

To upgrade: fetch the same paths from jsdelivr at the new version, update this
table, and re-run the harness smoke checks (boot → `ready` event, VRM load →
`avatarLoaded`, expression buttons visibly animate).
