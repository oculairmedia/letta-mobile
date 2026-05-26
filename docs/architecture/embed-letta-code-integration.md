# Embed Letta Code as on-device runtime - integration design

**Status:** Day 0 spike complete 2026-05-26 02:00 EDT. Highest-risk unknown
(Node 22 requirement) resolved. Full Android spike pending - 1-2 day budget.

**Decision bead:** `lcp-59c` (P1, open)
**Spike bead:** `lcp-59c.1` (P1, open)
**Filed by:** Emmanuel + Meridian, 2026-05-26 01:30am EDT.

This doc is the canonical project-side reference for the
embed-Letta-Code-via-nodejs-mobile pivot. It is grounded in a source-tree review
of `sepivip/SeekerClaw`, a shipping production app using the same pattern, and
a live Day 0 spike confirming Node-version compatibility.

## TL;DR

- Letta Code is TS/Node end-to-end and already has `letta --backend local`.
- Instead of reimplementing the local runtime in Kotlin, embed nodejs-mobile in
  the Android app and ship actual `letta.js` as the on-device runtime.
- Day 0 result: `letta.js` from `@letta-ai/letta-code` 0.26.2 boots on Node
  18.20.8. The `engines >=22.19.0` constraint is install-time only.
- Use nodejs-mobile v18.20.4 prebuilt, following the SeekerClaw bridge pattern.
- The local runtime slot must remain pluggable so LettaCode can coexist with
  future engines such as Koog.

## Proposal

Android hosts a foreground service that starts nodejs-mobile with bundled
`letta.js --backend local --input-format stream-json --output-format stream-json`.
Kotlin/Compose remains the UI shell and talks to the local runtime over the
LettaCode headless stream-json wire protocol through JNI-managed stdin/stdout
pipes. Remote REST/SSE and admin-shim WS remain unchanged; LocalLettaCode is the
default local runtime provider behind a pluggable local runtime boundary, while
explicit schemes such as `local-koog://device` can keep other engines
addressable.

## Android Shape

```text
Compose UI
  -> Local runtime registry
  -> LocalLettaCodeRuntime
  -> LocalLettaCodeService
  -> JNI bridge
  -> stdin/stdout stream-json
  -> libnode.so
  -> assets/letta-code/letta.js --backend local
```

The Android implementation follows these constraints:

- Node can only be started once per OS process.
- `node::Start()` blocks forever and must run on a dedicated background thread.
- `stdin`, `stdout`, and `stderr` must be redirected through native pipes.
- Assets are extracted from APK assets into `filesDir` before Node starts.
- `HOME` and `LETTA_LOCAL_BACKEND_DIR` point into app-private storage.
- The remote/shim transport must not be changed by the spike.

## Android Implementation Notes

- Default app builds keep native/assets packaging disabled so CI does not need
  a vendored `libnode.so` or a network npm install.
- Build with `-PembedLettaCodeNative=true -PembedLettaCodeAssets=true` after
  adding nodejs-mobile `libnode` under `android-compose/app/libnode`.
- `prepareEmbeddedLettaCodeAssets` installs `@letta-ai/letta-code@0.26.2` into
  generated APK assets when asset packaging is enabled.
- The JNI bridge starts Node once, writes user/control frames to stdin, and
  emits stdout JSON lines into the Kotlin stream-json mapper.

## Native Dependency Risks

| Dependency | Risk | Mitigation |
| --- | --- | --- |
| `node-pty` | Android has no PTY. | Stub or make tool path non-PTY. |
| `sharp` | Native libvips. | Feature flag image features if needed. |
| `@vscode/ripgrep` | Android binary availability. | Fall back to JS search. |
| `ws` | Pure JS. | No action needed. |

## Spike Gates

1. `node::Start()` boots hello.js in a foreground service.
2. Bundled `letta.js --backend local` reaches a steady state.
3. Local backend writes under app-private storage.
4. Kotlin can discover the local runtime endpoint.
5. Compose can send a message over loopback and stream a response.

Hard stop if Node cannot boot, `node-pty` cannot be stubbed, boot time exceeds
30 seconds on a real device, or APK size exceeds 150 MB.

## Roadmap Impact

If this works, `letta-mobile-hua6` is no longer the primary implementation plan
for Android. It should remain available as a future pluggable engine experiment,
not as the only local runtime slot. The Android app should own a stable local
runtime host contract that can load `LocalLettaCode` now and other engines later.
