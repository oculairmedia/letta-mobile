# Desktop local runtime

`package.json` / `package-lock.json` pin the Letta Code runtime bundled with the Windows desktop
client (`@letta-ai/letta-code@0.34.8`). `desktop/scripts/npm-ci.ps1` installs it with
`npm ci --omit=dev`. letta-code 0.34 requires Node >= 22.19; the bundled Node is 24.

## Dependency provenance notes (0.34.8)

- letta-code ships an `npm-shrinkwrap.json`, so its whole tree is nested under
  `node_modules/@letta-ai/letta-code/node_modules`. The lock was generated with
  `npm install --package-lock-only` on npm 11 (the toolchain of the bundled Node 24).
- **Nested older copies of letta-code.** `@letta-ai/letta-code@0.34.8` depends on
  `@letta-ai/letta-agent-sdk@0.8.24`, which pins `@letta-ai/letta-code@0.33.7`, and that SDK in
  turn nests `@letta-ai/letta-agent-sdk@0.8.21` with `@letta-ai/letta-code@0.33.3`. Both nested
  copies declare a `postinstall` (`scripts/postinstall-patches.js`), which runs at install time.
  Their paths exceed the 260-character limit WiX enforces in the MSI, so
  `desktop/scripts/stage-letta-code-runtime.ps1` removes the SDK's nested `node_modules` (and an
  orphan `letta-code/node_modules/@letta-ai/letta-code` tree with no `package.json`) when it
  stages the runtime. The SDK, which letta-code loads for Workflow runs, resolves
  `@letta-ai/letta-code` with `require.resolve` and so uses the bundled 0.34.8 runtime; its own
  dependencies (`@letta-ai/letta-client`, `ajv`) sit one level up. The longest staged path is
  then about 250 characters under the CI packaging root.
- An npm `overrides` entry was evaluated for 0.33.6 and does **not** dedupe these copies (the
  dependency is circular through the SDK, and the shrinkwrap wins); the lock is left as resolved.
- `@janhapke/sharp-electron@0.35.3-electron.1` is a third-party sharp build by an individual
  maintainer and a direct dependency of letta-code 0.34.x (about 23 optional platform binaries).
- `npm-ci.ps1` deliberately keeps install scripts enabled: `node-pty` (`install`: prebuild
  selection / node-gyp fallback, `postinstall`), `sharp` (`install/check.js`) and letta-code's
  own `postinstall` (vendored-Ink patches) all run at build time, and skipping them could break
  terminal/image support on Windows in ways CI does not exercise. `@scarf/scarf` (usage
  analytics `postinstall`) and `@google/genai`/`protobufjs` also run scripts. Switching to
  `--ignore-scripts` needs a verified Windows installer run first; it was not attempted here.
- Upstream peer conflict inside letta-code: `react-dom@19.3.0` wants `react@^19.3.0` and
  `@pierre/diffs` wants `react@^18.3.1 || ^19`, while letta-code's own `react` is `18.3.1` (the
  nested copies keep `18.2.0`). A plain `npm install` of the tarball therefore fails with
  ERESOLVE; installing through the shrinkwrap (`npm install @letta-ai/letta-code@0.34.8` or this
  lock) works. Left as shipped upstream.
