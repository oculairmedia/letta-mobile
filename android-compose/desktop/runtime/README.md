# Desktop local runtime

`package.json` / `package-lock.json` pin the Letta Code runtime bundled with the Windows desktop
client (`@letta-ai/letta-code@0.33.6`). `desktop/scripts/npm-ci.ps1` installs it with
`npm ci --omit=dev`.

## Dependency provenance notes (0.33.6)

- **Nested older copy of letta-code.** `@letta-ai/letta-code@0.33.6` depends on
  `@letta-ai/letta-agent-sdk@0.8.21`, which pins `@letta-ai/letta-code@0.33.3`. npm therefore
  installs a second copy at
  `node_modules/@letta-ai/letta-agent-sdk/node_modules/@letta-ai/letta-code` (with its own
  `react`, `@pierre/diffs`, `@shikijs/*` copies). That copy declares a `postinstall`
  (`scripts/postinstall-patches.js`), and `npm-ci.ps1` does not pass `--ignore-scripts`, so the
  script runs at build time and the bundle is larger.
- An npm `overrides` entry (both `"$@letta-ai/letta-code"` and the exact `"0.33.6"` form,
  scoped under `@letta-ai/letta-agent-sdk`, npm 11.21) was evaluated and does **not** dedupe the
  copy: the regenerated lock still contains the 0.33.3 nested copy (the dependency is circular
  through the SDK), and regenerating the lock from scratch churns integrity fields across
  hundreds of unrelated packages. The lock and `package.json` are therefore left untouched.
- `@janhapke/sharp-electron@0.35.3-electron.1` is a third-party sharp build by an individual
  maintainer and a direct dependency of letta-code 0.33.x (about 23 optional platform binaries).
- `npm-ci.ps1` deliberately keeps install scripts enabled: `node-pty` (`install`: prebuild
  selection / node-gyp fallback, `postinstall`), `sharp` (`install/check.js`) and letta-code's
  own `postinstall` (vendored-Ink patches) all run at build time, and skipping them could break
  terminal/image support on Windows in ways CI does not exercise. `@scarf/scarf` (usage
  analytics `postinstall`) and `@google/genai`/`protobufjs` also run scripts. Switching to
  `--ignore-scripts` needs a verified Windows installer run first; it was not attempted here.
- Upstream peer conflict inside letta-code 0.33.6: `react-dom@19.3.0` wants `react@^19.3.0`
  and `@pierre/diffs` wants `react@^18.3.1 || ^19`, while the nested `react` is `18.2.0`. Left
  as shipped upstream; it could matter at runtime only if those render paths are exercised.
