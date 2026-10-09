# Redeploy meridian-iroh-wrapper for the workspace relay (letta-mobile-bzvro.37)

Audience: the agent operating the **Linux host** that runs `meridian-iroh-wrapper.service`
(Iroh QUIC :4501 ⇄ App Server `ws://127.0.0.1:4500`). Every command below runs **on that
host, as root**, unless marked otherwise.

## What changes and why

The host's admin_rpc router gains 11 allowlisted methods (`memfs.list|read|history|commit_diff|
file_at_ref|enable|write`, `secret.list|apply`, `workspace.search_files|read_file`), registered by
`WorkspaceAdminHandlers` inside `AdminRpcRegistry.buildRouter` — the same router every launch path
builds. **No new flags, env vars, config or allowlist files**: the methods register automatically
whenever the wrapper has its native App Server client (it always does in production:
`--app-server-url ws://127.0.0.1:4500`). Authorization uses the existing paired-peer capabilities
(`/etc/meridian/paired-peers.json`): MemFS reads `memory.read`, MemFS writes `memory.write`,
workspace files and secrets `admin.full` (default-role paired devices get "not allowed" for those two;
grant `admin.full` with `pair.peer.set_capabilities` if wanted). Bearer-token peers keep full access as today.

Until the host runs this build, clients show "The Iroh host does not relay this yet. Update the
host (meridian-iroh-wrapper)…" in the Memory → Files tab, the file viewer and `@` file search.

## Which commit to deploy

The **merge commit of the PR on `main`** (squash merge). Once merged:

```bash
# any machine with the repo
git fetch origin && git log -1 --format='%H %s' origin/main   # confirm it is the bzvro.37 squash commit
```

Call it `<DEPLOY_SHA>` below. Do not deploy the PR branch head.

## 0. Find out which launch layout is live (host)

The service has used three layouts over time; the steps differ. Check first:

```bash
systemctl cat meridian-iroh-wrapper.service | grep -E 'ExecStart|WorkingDirectory'
PREV=$(systemctl show meridian-iroh-wrapper -p MainPID --value); echo "running pid $PREV"
```

| `ExecStart` shows | Layout | Go to |
|---|---|---|
| `/opt/meridian/iroh-wrapper/current/bin/meridian-iroh-wrapper app-server-serve-iroh …` | packaged release (repo template `scripts/deploy/meridian-iroh-wrapper.service`) | **A** |
| `/etc/meridian/run-iroh-cli.sh …` | captured classpath into the `main-dev-apk-build` worktree | **B** |
| `gradlew … :cli:run …` | Gradle-run from the `main-dev-apk-build` worktree | **B** (build step only; restart rebuilds) |

Record the commit currently deployed, for rollback:

```bash
# A:
readlink -f /opt/meridian/iroh-wrapper/current          # e.g. /opt/meridian/iroh-wrapper/releases/<old-sha>
# B:
git -C /opt/stacks/letta-mobile/.letta/worktrees/main-dev-apk-build rev-parse HEAD > /root/iroh-wrapper.pre-bzvro37.sha
cat /root/iroh-wrapper.pre-bzvro37.sha
```

## A. Packaged release layout (host)

```bash
cd /opt/stacks/letta-mobile                       # the host's checkout (any clean one works)
git fetch origin && git worktree add --detach /tmp/iroh-wrapper-build <DEPLOY_SHA>
cd /tmp/iroh-wrapper-build/android-compose
cp -f local.properties.example local.properties
JAVA_HOME=/usr/lib/jvm/jdk-26 ./gradlew --no-daemon :iroh-wrapper-cli:test :iroh-wrapper-cli:installDist
REL=/opt/meridian/iroh-wrapper/releases/$(git rev-parse --short=12 HEAD)
mkdir -p "$REL" && cp -a iroh-wrapper-cli/build/install/meridian-iroh-wrapper/. "$REL/"
"$REL/bin/meridian-iroh-wrapper" --help >/dev/null && echo "dist ok"
ln -sfn "$REL" /opt/meridian/iroh-wrapper/current
systemctl restart meridian-iroh-wrapper
cd / && git -C /opt/stacks/letta-mobile worktree remove --force /tmp/iroh-wrapper-build
```

(Build interface and layout: `docs/architecture/lettashim-retirement-deployment-runbook.md`,
"Phase 5: Package the wrapper".)

## B. `main-dev-apk-build` worktree layout (host)

Per the `iroh-wrapper-worktree-rebuild` note: stop the service first, never run a second Gradle
build in that worktree while it boots, and purge build state after switching commits (stale KSP
state crash-loops `:core:data:kspDebugKotlin`).

```bash
W=/opt/stacks/letta-mobile/.letta/worktrees/main-dev-apk-build
systemctl stop meridian-iroh-wrapper
git -C "$W" fetch origin && git -C "$W" checkout --detach <DEPLOY_SHA>
cd "$W/android-compose"
find . -path ./node_modules -prune -o -type d -name build -prune -exec rm -rf {} +
rm -rf .gradle/configuration-cache
# The inputs of :cli:run (android-compose/cli/build.gradle.kts): its debug unit-test classpath.
JAVA_HOME=/usr/lib/jvm/jdk-26 ./gradlew --no-daemon :cli:compileDebugUnitTestKotlin :cli:processDebugUnitTestJavaRes :cli:assembleDebugUnitTest
systemctl start meridian-iroh-wrapper     # a gradlew-:cli:run unit rebuilds on start anyway
```

If the unit uses `/etc/meridian/run-iroh-cli.sh` (captured classpath): this change adds **no
dependencies**, so the captured `/etc/meridian/iroh-wrapper-classpath.txt` stays valid once the
build above has re-populated the same `build/` paths. Check every entry exists before starting:

```bash
tr ':' '\n' < /etc/meridian/iroh-wrapper-classpath.txt | while read -r p; do [ -e "$p" ] || echo "MISSING $p"; done
```

Any `MISSING` line: start once via `./gradlew :cli:run --args='app-server-serve-iroh …'` and
re-capture the classpath from `/proc/<pid>/cmdline` of the running JVM, as the
`meridian-iroh-wrapper-service` note describes. Cold boot after a clean build takes several minutes.

## Verify (host, then a client)

1. Ready (host) — the repo's readiness check, then the ready markers:

   ```bash
   bash /opt/stacks/letta-mobile/scripts/deploy/verify-iroh-wrapper-ready.sh   # or the copy in the deployed checkout
   grep -E 'Node ID:|Listening on Iroh' /var/log/meridian-iroh-wrapper.log | tail -2
   ```

   The Node ID must be unchanged (`330415cc…`, key `/etc/meridian/iroh-secret.key`).

2. Relay registered (host) — expected lines at startup:

   ```bash
   grep -E 'handler.registered method=(memfs|secret|workspace)\.' /var/log/meridian-iroh-wrapper.log | tail -11
   ```

   Expected (one per method, 11 lines):
   `[INFO] Telemetry/AdminRpc: handler.registered method=memfs.list` … `method=workspace.read_file`.
   No such lines ⇒ the old build is still running.

3. End to end (client): desktop connected via `iroh://…` or the Android app → **Memory → Files**
   with an agent selected. Expected: the agent's memory files list (e.g.
   `system/human/communication_style.md`), opening one shows its content and History. Not
   expected: "This needs a direct App Server connection…" (old client) or "The Iroh host does not
   relay this yet…" (old host). On the host:

   ```bash
   grep -E 'IrohNode: workspace_relay\.(ok|failed|timeout|unsupported)|authz.denied method=(memfs|secret|workspace)' /var/log/meridian-iroh-wrapper.log | tail -5
   ```

   Expected: `[INFO] Telemetry/IrohNode: workspace_relay.ok method=memfs.list frames=<n>`.
   `authz.denied … capability=memory.read` means the paired device lacks that grant (fix with
   `pair.peer.set_capabilities` from an admin peer, not a deploy issue). The log never contains
   secret values; `grep` for a known secret value must return nothing.

4. Chat still works: send one message from a client and watch it stream (regression check).

## Rollback (host)

- **A:** `ln -sfn <PREVIOUS_RELEASE_DIR_FROM_STEP_0> /opt/meridian/iroh-wrapper/current && systemctl restart meridian-iroh-wrapper`
- **B:**
  ```bash
  W=/opt/stacks/letta-mobile/.letta/worktrees/main-dev-apk-build
  systemctl stop meridian-iroh-wrapper
  git -C "$W" checkout --detach "$(cat /root/iroh-wrapper.pre-bzvro37.sha)"
  cd "$W/android-compose" && find . -type d -name build -prune -exec rm -rf {} + && rm -rf .gradle/configuration-cache
  JAVA_HOME=/usr/lib/jvm/jdk-26 ./gradlew --no-daemon :cli:compileDebugUnitTestKotlin :cli:processDebugUnitTestJavaRes :cli:assembleDebugUnitTest
  systemctl start meridian-iroh-wrapper
  ```

Then re-run Verify step 1. Rolling back only removes the 11 methods; clients fall back to the
"Update the host" message, nothing else changes. No state or config is written by this change, so
there is nothing to migrate back.
