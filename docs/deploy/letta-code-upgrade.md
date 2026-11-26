# letta-code upgrade runbook

How to move letta-mobile and the Meridian host to a new `@letta-ai/letta-code` release. There is
**one** letta-code pin: the App Server wire contract, the desktop's bundled runtime, the Android
embedded runtime (full APK) and what the host runs. Every file that names it is listed in
`android-compose/letta-code-runtime/letta-code-version-sites.json`; `LettaCodeVersionPinsTest`
reads that list and fails if any site disagrees. The host moves only in the cutover step.

The mechanical work is one command (Node `v24.18.0`, the contract matrix's Node):

```bash
node scripts/appserver/letta-code-upgrade.mjs survey <new>   # read-only; exit 1 = not ready
node scripts/appserver/letta-code-upgrade.mjs apply  <new>   # rewrites the repo, never the host
```

`survey` installs `<new>` to scratch without lifecycle scripts (or uses `--package-root <dir>`) and
reports engines, the dependency diff against the pin, the npm integrity, every added and removed
command/message union member, a dry run of the repo runtime-patch loader and of the host loader
(`--host-loader`, default `/opt/meridian/runtime-patches/current`, read only) naming each skipped
patch, and the Android embedded `letta.js` patch pipeline in staging order. `--json` for machines.

`apply` rewrites every listed version site, bumps `assetRevision` and the npm integrity, regenerates
`desktop/runtime/package-lock.json` with npm, re-captures the contract matrix, inventory, CLI probe
fixtures and union counts from the installed package, regenerates
`docs/reference/current-app-server-protocol.md` and runs `verify-contract-baseline.mjs`. History
lines (for example "restart-replay re-captured at 0.34.14") are not version sites and stay as they are.

## What the command cannot do

1. **Read the release notes** (`gh release view v<X> -R letta-ai/letta-code` for each release since the
   pin) for behaviour the unions do not show: turn cancellation, resume, task notification text,
   anything our wrapper parses.
2. **Fix skipped runtime patches.** A skip means an anchor moved. For each one, first check whether
   the new release already does what the patch did (delete the patch and its test, with the evidence);
   otherwise re-anchor it so it applies exactly once and fails open on drift, keeping its behavioural
   test green on both the old and the new bundle (the host runs the new loader on the old bundle
   before the cutover). See [appserver-runtime-patches.md](appserver-runtime-patches.md). The same
   goes for a failed embedded patch (`android-compose/letta-code-runtime/patches/`).
3. **Classify new union members.** `apply` captures them; give each an honest status in the
   inventory's `capabilities` (`AppServerContractBaselineTest` fails until you do) and decide whether
   the client must handle it. A removed member stops `apply`: that is a product decision.
4. **Android embedded size (28 MiB gzip budget, not raised without approval).** From `android-compose/`:
   ```bash
   ./gradlew :app:prepareEmbeddedLettaCodeAssets -PembedLettaCodeAssets=true
   tar -C app/build/generated/embedded-lettacode-assets/letta-code -czf /tmp/s.tgz . && stat -c %s /tmp/s.tgz
   ```
   If it is over, extend `HOST_ONLY_NODE_MODULES` (`PrepareEmbeddedLettaCodeAssetsTask.kt`) with
   **only content that cannot load under nodejs-mobile** (bionic, `process.platform=android`), each glob
   with a KDoc reason and `EmbeddedAssetExclusionsTest` coverage. Before excluding a package, prove
   nothing on the runtime path requires it: grep `letta.js` and its sibling entry files for imports.
   Keep exactly one top-level `@img/sharp-wasm32`, version-matched to sharp (Android's fallback).
5. **Node 18 smoke** (the phone runs nodejs-mobile Node 18 + `node18-compat.cjs`), with Node 18.20.4
   and JDK 21 on PATH: `scripts/embedded-runtime/node18-smoke.sh <staged nodejs-project> <appserver-cli installDist dir>`.
   Both the headless turn and the `LocalAppServerHost` + `app-server-smoke` turns must pass.
6. **Gradle gates:** `LettaCodeVersionPinsTest`, `AppServerContractBaselineTest`, `:build-logic:test`
   (needs `ANDROID_HOME`), and the desktop installer CI, which is advisory but has caught a dangling
   npm `.bin` link before (`scripts/desktop/linux-runtime.sh stage` drops broken links; check the
   Windows script if npm's bin layout changes).
7. **Restart-replay evidence** needs a live server and model; it stays at its captured version.

## Merge

Branch protection is strict: rebase onto main after any other merge and merge only on the required
checks green on the exact head, with no open threads. Read the **newest run for the head**.

## Host cutover (after merge; warn Emmanuel and coordinate PM-vibesync first)

The App Server runs `/root/.bun/bin/letta` (a symlink to an install's `letta.js`) and finds `node`
via PATH. `/usr/local/bin/node` is **shared with other services (steel-mcp)**: never swap it.

1. **Stage the merged runtime-patch loader first** ([appserver-runtime-patches.md](appserver-runtime-patches.md))
   and confirm `lcp-patches applied=<N> skipped=0` on the old bundle. `<N>` is the loader's patch count
   (36 since letta-code 0.34.70); `survey` prints it as the repo loader's `applied=`.
2. **Install side by side** (this leaves the shell in the install directory; the commands below use absolute paths):
   `mkdir -p /root/letta-code-<new> && cd $_ && npm install --no-audit --no-fund @letta-ai/letta-code@<new>`.
3. **Service Node, if the engines floor needs it**: the official tarball, verified against `SHASUMS256.txt`, into `/opt/meridian/node/v<ver>/`.
4. **Pre-flight on the exact install**, with `<letta.js>` = `/root/letta-code-<new>/node_modules/@letta-ai/letta-code/letta.js`:
   `~/.nvm/versions/node/v24.18.0/bin/node <checkout>/scripts/appserver/letta-code-upgrade.mjs survey <new> --package-root /root/letta-code-<new>`
   must report READY; `<service node> <letta.js> --version`; and
   `LETTA_BIN=<letta.js> bash /usr/local/lib/meridian/check-image-support.sh` (sharp at the pinned version, a successful probe resize).
5. **Check who else uses the symlink**: `grep -rl /root/.bun/bin/letta /etc/systemd`.
6. **Wait for workers inside the App Server to finish, or back up their work.** The restart kills every
   turn and child, including the PM's own turn, and the Iroh wrapper restarts with it.
7. **Run detached**, after writing the recovery plan to the PM checkpoint:
   ```bash
   install -m 0755 <checkout>/scripts/deploy/letta-code-host-cutover.sh /usr/local/sbin/
   systemd-run --unit=letta-code-cutover --collect --no-block \
     env EXPECTED_PATCHES=36 /usr/local/sbin/letta-code-host-cutover.sh <old> <new> /opt/meridian/node/v<ver>/bin
   ```
   Always pass `EXPECTED_PATCHES` (36 for the 0.34.70 loader): the script's default, 28, predates the
   current loader and would roll back a healthy cutover. It takes a lock, refuses to start unless the staged package reports `<new>`, changes the symlink,
   `LETTA_CLI_PATH_REAL` and an optional service-only `PATH` line, restarts once, and requires a new
   boot line `lcp-patches applied=<N> skipped=0`, the new node and env in the live process and the
   `:4500` listener in the service's cgroup. On any failure it restores all three and restarts.
8. **Verify from the live process**: `/var/log/letta-code-cutover-<new>.log` says `RESULT: OK`;
   `readlink /proc/<pid>/exe`, `/proc/<pid>/environ`. The bead closes on Emmanuel's live confirmation.

Rollback (the script prints the exact line):
`cp -a /etc/meridian/appserver.env.pre-letta-code-<new>-<ts> /etc/meridian/appserver.env && ln -sfn /root/letta-code-<old>/node_modules/@letta-ai/letta-code/letta.js /root/.bun/bin/letta && systemctl restart meridian-appserver`.
Keep the old install and Node until the next upgrade.
