# letta-code upgrade runbook

How to move letta-mobile and the Meridian host to a new `@letta-ai/letta-code` release. Written
from the 0.34.14 → 0.34.39 upgrade (bead letta-mobile-m0zpv, PR #2365, live 2026-11-04). Every
step below caught a real problem that time.

There is **one** letta-code pin. It is the App Server wire contract, the desktop's bundled runtime,
the Android embedded runtime (full APK) and what the host runs. `LettaCodeVersionPinsTest` fails
if the repo's copies disagree. The host is separate: it moves only in the cutover step.

## 0. Survey (read-only, before any edit)

1. **What changed upstream.** `npm view @letta-ai/letta-code versions --json` and
   `gh release view v<X> -R letta-ai/letta-code` for each release since the pin.
2. **Install the candidate to scratch** (no scripts):
   `npm install --no-audit --no-fund --ignore-scripts @letta-ai/letta-code@<new>` in `/tmp/lc-<new>`.
3. **Engines.** `node -p "require('<pkg>/package.json').engines"`. 0.34.39 raised the floor to
   `^22.23.4 || ^24.22.1 || ^26.12.1`. Not enforced at runtime, but plan the host Node (step 5)
   and note the desktop's bundled Node (`desktop/runtime/runtime-manifest.json`) as a risk if
   it is below the floor.
4. **Host runtime patches (lcp loader)**: they rewrite exact bytes of `letta.js`, the biggest risk.
   Dry-run the live loader against the new bundle; the result must match the old bundle:
   ```bash
   node -e 'const fs=require("fs");(async()=>{const m=await import("/opt/meridian/runtime-patches/current/letta-code-patch-loader.mjs");
   const r=m.patchLettaCodeSourceResultForTest(fs.readFileSync(process.argv[1],"utf8"));console.log(r.appliedPatches,r.skippedPatches)})()' /tmp/lc-<new>/node_modules/@letta-ai/letta-code/letta.js
   ```
   Any skip means a patch anchor moved: fix the loader first ([appserver-runtime-patches.md](appserver-runtime-patches.md)).
5. **Embedded (Android) patches.** The scripts in `android-compose/letta-code-runtime/patches/`
   refuse paths outside their own directory, so copy them and the bundle into one scratch dir
   before running each against the new `letta.js`.
6. **Protocol unions.** `node scripts/appserver/verify-contract-baseline.mjs --package-root <pkg> --unions-only`
   (Node 24.18.0) prints added/removed commands and messages. A removal, or a change to something the
   client uses, is a product decision. Additions only need an honest inventory status.
7. **New dependencies.** Diff `dependencies` between the old and new package.json. 0.34.39 added
   `@cursor/sdk` (+ per-platform native packages) and sharp platform copies, which tripled the
   Android runtime (step 2 below).

## 1. Repository change (one PR)

- Raise every pin `LettaCodeVersionPinsTest` enforces: `letta-code-runtime/letta-code-runtime.properties`
  (version, **npm integrity** from `npm view …@<new> dist.integrity`, bump `assetRevision`),
  `desktop/runtime/{package.json,runtime-manifest.json}`, the regenerated `package-lock.json`
  (npm, not by hand), `LettaCodeContract.VERSION`, and the README pin table. Update current-pin
  statements in AGENTS.md, CLAUDE.md and the CLI READMEs. **Leave history lines as they are**
  (for example "restart-replay re-captured at 0.34.14").
- Re-capture the contract from the real package with Node 24.18.0: matrix, inventory and
  `cli-version.txt`, then `verify-contract-baseline.mjs --package-root <pkg>` must pass. Regenerate
  `docs/reference/current-app-server-protocol.md`. Update the reviewed union counts in
  `AppServerContractBaselineTest` against the re-captured data. Never loosen them.
- Restart-replay evidence needs a live server and model. It stays at its captured version, and its
  test checks it against its own evidence file.

## 2. Android embedded runtime size (28 MiB gzip budget, not raised without approval)

Stage and measure it the way CI does:
```bash
./gradlew :app:prepareEmbeddedLettaCodeAssets -PembedLettaCodeAssets=true
tar -C app/build/generated/embedded-lettacode-assets/letta-code -czf /tmp/s.tgz . && stat -c %s /tmp/s.tgz
```
If it is over budget, extend `HOST_ONLY_NODE_MODULES` (`PrepareEmbeddedLettaCodeAssetsTask.kt`) with
**only content that cannot load under nodejs-mobile** (bionic, `process.platform=android`). Give
each glob a KDoc reason and `EmbeddedAssetExclusionsTest` coverage (the test drives the real
`copyNodeModules`). The 0.34.39 trim took it from 64.95 to 27.0 MiB:
- `@cursor/sdk` plus its platform packages and `cursor-worker.js`: only the separate worker
  imports the SDK, and its sandbox is a glibc ELF. `check-no-static-cursor-import.mjs` fails
  staging if `letta.js` ever imports it at top level.
- sharp platform copies (`linuxmusl`, ppc64/s390x/riscv64, win32 arm64/ia32, freebsd/webcontainers
  wasm wrappers) and nested `sharp-wasm32` duplicates. Keep exactly one top-level
  `@img/sharp-wasm32`, version-matched to sharp: it is Android's fallback.
- nested `node_modules/.bin/**`: 0.34.39's `.bin/rg` resolved to a 1.7 MB **macOS** ripgrep.

Before excluding a package, prove nothing on the runtime path requires it: grep `letta.js` and
its sibling entry files for imports, not just the package name.

## 3. Verification before the PR

- **Node 18 smoke** (the phone runs nodejs-mobile Node 18 + `node18-compat.cjs`). Run CI's script
  locally with Node 18.20.4 and **JDK 21 on PATH** (the App Server half runs a JVM; an older
  `java` fails with `UnsupportedClassVersionError`):
  `scripts/embedded-runtime/node18-smoke.sh <staged nodejs-project> <appserver-cli install dir>`.
  Both the headless turn and the `LocalAppServerHost` + `app-server-smoke` turn must pass.
- `:build-logic:test` (needs `ANDROID_HOME`; one test runs a nested Android build), `:appserver-cli:test`,
  the sharedLogic contract filters, the protocol-reference `--check`, the docs index and `platform-ui-gate --enforce`.
- **Desktop installer CI matters even though it is advisory**: 0.34.39's dangling `.bin/rg` link
  (into a pruned darwin package) broke the Linux AppImage's `prepareAppResources`.
  `scripts/desktop/linux-runtime.sh stage` now drops broken links. Check the Windows script if
  npm's bin layout changes.

## 4. Merge

Branch protection is strict: rebase onto main after any other merge (one leased push) and merge
only on the four required checks green on the exact head, with no open threads. When reading
check results, use the **newest run for the head**: a superseded, cancelled run leaves red
`*-pass`/`test` rollups behind.

## 5. Host cutover (after merge; warn Emmanuel and coordinate PM-vibesync first)

The App Server runs `/root/.bun/bin/letta` (a symlink to an install's `letta.js`) and finds `node`
via PATH. `/usr/local/bin/node` is **shared with other services (steel-mcp)**: never swap it.
Give the App Server its own Node instead.

1. **Install side by side**, never in place:
   `mkdir -p /root/letta-code-<new> && cd $_ && npm install --no-audit --no-fund @letta-ai/letta-code@<new>`.
2. **Service Node, if the engines floor needs it**: download the official tarball and verify it against
   `SHASUMS256.txt` into `/opt/meridian/node/v<ver>/`.
3. **Pre-flight on the exact install and Node**: `<node> <letta.js> --version`; the lcp loader
   dry-run against `/root/letta-code-<new>` (must be N/0); and the image preflight
   `LETTA_BIN=<letta.js> bash /usr/local/lib/meridian/check-image-support.sh` (it must report sharp
   at the pinned known-good version and a successful probe resize).
4. **Check who else uses the symlink**: `grep -rl /root/.bun/bin/letta /etc/systemd`
   (agents-chat-appserver also does; it was inactive and disabled in 2026-11).
5. **Wait for workers that run inside the App Server to finish, or back up their work first**
   (worktree diff, unpushed commits on a backup branch). The restart kills every turn and child,
   including the PM's own turn, and the Iroh wrapper restarts with it (a ~7 s client drop).
6. **Run detached** (so the restart cannot kill the caller), after writing the recovery plan to the
   PM checkpoint:
   ```bash
   install -m 0755 scripts/deploy/letta-code-host-cutover.sh /usr/local/sbin/
   systemd-run --unit=letta-code-cutover --collect --no-block \
     /usr/local/sbin/letta-code-host-cutover.sh <old> <new> /opt/meridian/node/v<ver>/bin
   ```
   It changes the symlink, `LETTA_CLI_PATH_REAL` and an optional service-only `PATH` line, restarts
   once, and requires `lcp-patches applied=<N> skipped=0`, the new node as the live exe, the new
   env in the live process and `:4500`. On any failure it restores all three and restarts.
7. **Verify from the live process**: read `/var/log/letta-code-cutover-<new>.log` for `RESULT: OK`,
   `readlink /proc/<pid>/exe` and `/proc/<pid>/environ`. Then relaunch any interrupted workers from
   their saved state.
8. The bead closes on Emmanuel's live confirmation (chat works normally). Phone and desktop
   pick up the new runtime with the next release.

Rollback (the script prints the exact line):
`cp -a /etc/meridian/appserver.env.pre-letta-code-<new>-<ts> /etc/meridian/appserver.env && ln -sfn /root/letta-code-<old>/node_modules/@letta-ai/letta-code/letta.js /root/.bun/bin/letta && systemctl restart meridian-appserver`.
Keep the old install and Node until the next upgrade.
