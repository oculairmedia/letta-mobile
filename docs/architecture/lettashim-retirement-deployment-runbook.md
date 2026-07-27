# LettaShim Retirement Implementation and Deployment Runbook

Status: handoff instructions for the implementation agent

Last reviewed: 2026-07-27

Companion specification:
[App Server v2 and Meridian State Audit Specification](app-server-v2-audit-spec.md)

## Objective

Deploy a Meridian Iroh wrapper that has no runtime, state, network, or
deployment dependency on LettaShim.

The completed production path is:

```text
Mobile/Desktop
  -> Iroh QUIC
  -> packaged Kotlin wrapper
      -> Letta App Server v2 WS on 127.0.0.1:4500
      -> VibeSync directly for project operations
      -> separately named bounded services only where explicitly approved
      -> controller-owned health, pairing, and runtime-derived subagent state
```

Port 8291 must be closed during final validation. The wrapper must not have a
generic admin base URL, a shim fallback, or direct access to Letta backend
files.

## Non-Negotiable Constraints

1. Do not modify Letta Code directly.
2. Do not edit Letta backend files as a mutation mechanism.
3. Do not replace LettaShim with a generic REST mirror inside the wrapper.
4. Do not silently fall back from App Server v2 to another state authority.
5. Preserve Ask User and approval recovery across reconnects.
6. Preserve the Iroh secret key, UDP port, pairing store, and Node ID during
   wrapper upgrades.
7. Do not restart Letta App Server for a wrapper-only release.
8. Do not deploy from `main`; use the branch, PR, CI, and squash-merge workflow
   in `AGENTS.md`.
9. Do not call the migration complete while any production
   `shim_until_cutover` route remains.
10. Every intentionally removed capability must return a typed
    `capability_unavailable` response.

## Current Deployment Caveat

The current production wrapper is not packaged as a release artifact. It runs
`com.letta.mobile.cli.Main` from the Android `:cli` unit-test runtime classpath
captured in `/etc/meridian/iroh-wrapper-classpath.txt`.

This is transitional and must not become the shim-free deployment mechanism:

- dependency changes require classpath recapture;
- build outputs are loaded directly from a worktree;
- artifact identity and rollback are weak;
- the service shares `/etc/meridian/appserver.env`, exposing provider secrets
  the wrapper does not need;
- `LETTA_LOCAL_BACKEND_DIR` is currently present and enables a direct-storage
  read tier;
- absent an override, the wrapper defaults its generic admin base to
  `http://127.0.0.1:8291`.

`android-compose/appserver-cli:distZip` packages the App Server supervisor, not
`app-server-serve-iroh`. Do not mistake that archive for the Iroh wrapper.

## Required Implementation Order

Use separate commits for independently reviewable phases. Keep the work on one
feature branch and one PR unless the owning issue explicitly divides it.

### Phase 1: Freeze contracts and establish gates

1. Run the protocol and documentation verifiers.
2. Update `iroh-admin-ownership-matrix.json` to describe actual execution order,
   not historical shim-first behavior.
3. Add a machine-readable post-shim owner and fallback decision for every
   method.
4. Strengthen `ShimOffParityGateTest`:
   - required product methods must succeed through their final owner;
   - intentionally unavailable methods must return typed capability errors;
   - no proxy transport may be invoked;
   - a merely well-formed `success:false` response is not parity.
5. Add a static architecture test that fails when production wiring contains:
   - `127.0.0.1:8291`;
   - `LETTA_IROH_ADMIN_BASE_URL`;
   - `shim_until_cutover`;
   - `HttpSubagentRegistrySource`;
   - a production `LocalBackendAdminStore`;
   - a native-to-shim `AdminProxyClient` fallback.
6. Add route telemetry that records the selected owner without logging request
   bodies, tokens, prompts, memory, or provider credentials.

Do not proceed until the new tests fail against the old implementation for the
expected reasons.

### Phase 2: Port native-owned operations

For agent, conversation, message, model, skill, approval, cron, and reflection
operations:

1. Use the pinned App Server v2 command where semantic parity exists.
2. Define explicit Kotlin projections for upstream response shapes.
3. Remove `NativeAdmin` fallback behavior. Native timeout or protocol failure
   must remain a typed native failure.
4. Replace the global circuit breaker with per-command capability state, or
   remove it if reconnect/capability negotiation makes it unnecessary.
5. Remove the direct-disk tier from production routing.
6. Centralize runtime invalidation by changed field:
   - model;
   - context-window limit;
   - toolset/skills;
   - memory or prompt fields captured by runtime start;
   - conversation-scoped overrides.
7. Reconcile ambiguous mutations before reissuing them.

Specific known gaps:

- `message.get` and `tool_return.get` still need a native projection from
  `conversation_messages_list` or a separately approved owner.
- `model.list` must stop defaulting to the shim catalog shape.
- Skill list/install semantics must be reconciled with upstream
  enable/disable semantics.
- Approval fallback must be replaced by v2 sync/recovery.
- Conversation delete must become archive or fail closed unconditionally.

### Phase 3: Replace non-v2 admin domains

For each of the 40 `admin_rest_service` methods, choose exactly one:

1. Existing upstream App Server v2 command.
2. New upstream v2 contract, pinned after release.
3. Separately deployed bounded service with a domain-specific URL, schema,
   authorization, health check, and owner.
4. Product removal with `capability_unavailable`.

Do not keep one generic `adminBaseUrl`.

Domains requiring decisions:

- agent context;
- runs and steps;
- archives, folders, passages, and groups;
- identities;
- embedding models and providers;
- schedules and jobs;
- tools;
- blocks;
- MCP;
- goals;
- slash commands.

VibeSync project methods already call port 3099 directly. Update their matrix
fallback to `none`.

### Phase 4: Remove hidden dependencies

1. Replace `HttpSubagentRegistrySource` with runtime/controller-derived state.
2. Require a real controller for production health and remove health's shim
   branch.
3. Remove `shimRetired`; retired behavior becomes the only production behavior.
4. Remove the `--admin-base-url` option and
   `LETTA_IROH_ADMIN_BASE_URL`.
5. Remove `LETTA_LOCAL_BACKEND_DIR` and
   `LETTA_LOCAL_BACKEND_EXPERIMENTAL` from wrapper production configuration.
6. Remove legacy mobile WS routing after Iroh parity:
   - shim backend detection;
   - `WsChatBridge`;
   - shim cron protocol;
   - shim approval repository;
   - shim timeline subscription/write paths.
7. Preserve cloud or generic HTTP support only when it is a separately defined
   product connector, not an implicit LettaShim route.

### Phase 5: Package the wrapper

Create a dedicated JVM application distribution for the Iroh wrapper. Preferred
shape:

```text
android-compose/iroh-wrapper-cli/
  build.gradle.kts
  src/main/...
```

It may extract/reuse the current command implementation, but the release
artifact must not depend on Android unit-test outputs.

Required Gradle behavior:

- JVM application plugin;
- Java/JVM target compatible with Iroh, currently Java 21;
- main class that exposes `app-server-serve-iroh` or runs that server directly;
- `installDist` and `distZip`;
- start script with `--enable-native-access=ALL-UNNAMED`;
- reproducible dependency lock/version catalog use;
- artifact version containing the git commit;
- smoke test that launches `--help` from the installed distribution.

Expected build interface:

```bash
cd android-compose
JAVA_HOME=/usr/lib/jvm/jdk-26 \
ANDROID_HOME=/opt/android-sdk \
./gradlew \
  :iroh-wrapper-cli:test \
  :iroh-wrapper-cli:installDist \
  :iroh-wrapper-cli:distZip
```

If the implementing agent chooses a different module name, update this runbook,
the systemd unit template, CI, and release artifact paths in the same PR.

## Pre-Merge Verification

### Protocol and documentation

From the repository root:

```bash
node scripts/appserver/verify-v2-audit-doc.mjs

~/.nvm/versions/node/v24.18.0/bin/node \
  scripts/appserver/verify-contract-baseline.mjs \
  --package-root <installed-0.28.8-package-root>
```

If Node 24.18 is not installed, install/use that exact runtime. Do not waive the
version check for the final migration PR.

### Focused shim-retirement tests

From `android-compose`:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
ANDROID_HOME=/opt/android-sdk \
./gradlew :sharedLogic:jvmTest \
  --tests 'com.letta.mobile.data.transport.appserver.AppServerContractBaselineTest' \
  --tests 'com.letta.mobile.data.controller.node.iroh.IrohAdminOwnershipMatrixTest' \
  --tests 'com.letta.mobile.data.controller.node.iroh.ShimOffParityGateTest' \
  --tests 'com.letta.mobile.data.controller.node.iroh.NativeAdminHandlersTest' \
  --tests 'com.letta.mobile.data.controller.node.iroh.ApprovalAdminHandlersTest'
```

### Full repository gates

```bash
cd android-compose
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
ANDROID_HOME=/opt/android-sdk \
./gradlew \
  :sharedLogic:allTests \
  :desktop:test \
  :cli:testDebugUnitTest \
  :cli:assembleDebug

cd ..
bash scripts/tests/ci-policy-scripts-test.sh
bash scripts/ci/agents-policy-check.sh --diff-base origin/main
bash scripts/iroh_device_gate.sh --self-check
```

Also run every new wrapper-distribution test introduced in Phase 5.

### Required PR evidence

The PR description must include:

- final owner for every former shim domain;
- count of remaining `shim_until_cutover` rows, required to be zero;
- removed environment variables and service dependencies;
- exact artifact SHA-256;
- shim-off integration output;
- Android device-gate output;
- wrapper and App Server PIDs before and after staging;
- rollback artifact/version;
- any intentionally unavailable UI capability.

Merge only after all required CI checks pass.

## Artifact Preparation

Build from the squash-merged commit on a clean `main` worktree:

```bash
git checkout main
git pull --ff-only
git status --short
git rev-parse HEAD
```

`git status --short` must be empty. Record the commit as `DEPLOY_SHA`.

Build the wrapper distribution:

```bash
cd android-compose
JAVA_HOME=/usr/lib/jvm/jdk-26 \
ANDROID_HOME=/opt/android-sdk \
./gradlew \
  :iroh-wrapper-cli:test \
  :iroh-wrapper-cli:distZip
```

Record the artifact:

```bash
sha256sum iroh-wrapper-cli/build/distributions/*.zip
unzip -l iroh-wrapper-cli/build/distributions/*.zip
```

The archive must contain its own launcher and dependency libraries. Reject any
artifact whose classpath points into a Gradle cache or source worktree.

## Host Layout

Use immutable release directories and an atomic current symlink:

```text
/opt/meridian/iroh-wrapper/releases/<DEPLOY_SHA>/
/opt/meridian/iroh-wrapper/current -> releases/<DEPLOY_SHA>
/etc/meridian/iroh-wrapper.env
/etc/meridian/iroh-secret.key
/etc/meridian/paired-peers.json
```

The release directory is code only. Keys, pairing state, and environment files
remain outside it and survive rollback.

Recommended installation:

```bash
install -d -m 0755 "/opt/meridian/iroh-wrapper/releases/$DEPLOY_SHA"
unzip -q <artifact.zip> -d "/opt/meridian/iroh-wrapper/releases/$DEPLOY_SHA"
# Gradle distributions contain one top-level application directory. Verify it
# and point current at that directory, not at the parent extraction directory.
DIST_ROOT="$(find "/opt/meridian/iroh-wrapper/releases/$DEPLOY_SHA" \
  -mindepth 1 -maxdepth 1 -type d -print -quit)"
test -x "$DIST_ROOT/bin/meridian-iroh-wrapper"
ln -sfn "$DIST_ROOT" \
  /opt/meridian/iroh-wrapper/current
```

Before switching the symlink, record the previous target for rollback.

## Wrapper Environment

Split the wrapper from `/etc/meridian/appserver.env`. The wrapper environment
must contain only values it needs:

```text
LETTA_APP_SERVER_URL=ws://127.0.0.1:4500
LETTA_IROH_PORT=4501
LETTA_IROH_SECRET_KEY_FILE=/etc/meridian/iroh-secret.key
LETTA_IROH_PAIRING_STORE=/etc/meridian/paired-peers.json
LETTA_IROH_AUTH_TOKEN=<managed secret>
LETTA_IROH_VIBESYNC_BASE_URL=http://127.0.0.1:3099
```

Add domain-specific bounded-service URLs only for services approved by the
ownership matrix.

The wrapper environment must not contain:

```text
LETTA_IROH_ADMIN_BASE_URL
LETTA_LOCAL_BACKEND_DIR
LETTA_LOCAL_BACKEND_EXPERIMENTAL
OPENAI_API_KEY
ANTHROPIC_API_KEY
LMSTUDIO_API_KEY
```

Provider credentials belong to Letta App Server, not the Iroh wrapper.

## Systemd Unit

Install a repository-owned unit template during the implementation. The final
unit should be equivalent to:

```ini
[Unit]
Description=Meridian Iroh QUIC wrapper
After=meridian-appserver.service network-online.target
Requires=meridian-appserver.service
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=/etc/meridian/iroh-wrapper.env
ExecStartPre=/bin/bash -c 'for i in $(seq 1 60); do (echo > /dev/tcp/127.0.0.1/4500) 2>/dev/null && exit 0; sleep 1; done; exit 1'
ExecStart=/opt/meridian/iroh-wrapper/current/bin/meridian-iroh-wrapper \
  --app-server-url ws://127.0.0.1:4500 \
  --iroh-port 4501 \
  --iroh-secret-key-file /etc/meridian/iroh-secret.key \
  --pairing-store-file /etc/meridian/paired-peers.json \
  --vibesync-base-url http://127.0.0.1:3099
Restart=always
RestartSec=10
LimitNOFILE=65536
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

Do not add an App Server restart to `ExecStartPre`, `ExecStartPost`, or the
deployment script. Do not include an admin/shim base URL.

After changing a unit:

```bash
systemd-analyze verify /etc/systemd/system/meridian-iroh-wrapper.service
systemctl daemon-reload
```

## Staging Deployment

Deploy to a staging host or isolated production-equivalent instance first.

### 1. Record baseline

```bash
systemctl show meridian-iroh-wrapper.service \
  -p MainPID -p ActiveEnterTimestamp -p NRestarts
systemctl show meridian-appserver.service \
  -p MainPID -p ActiveEnterTimestamp -p NRestarts
ss -ltnp
ss -ulnp
```

Record the Iroh Node ID/ticket without exposing the auth token or secret key.

### 2. Install artifact and environment

Install the immutable release, update the `current` symlink, install the
minimal wrapper environment, and verify file permissions:

```bash
stat -c '%a %U:%G %n' \
  /etc/meridian/iroh-wrapper.env \
  /etc/meridian/iroh-secret.key \
  /etc/meridian/paired-peers.json
```

Secret-bearing files should be readable only by the service account.

### 3. Make LettaShim unavailable

Stop and disable the actual LettaShim unit/container on staging. Discover its
real deployment name; do not assume one:

```bash
systemctl list-units --type=service | rg -i 'letta|shim'
docker compose ps
```

After stopping it:

```bash
ss -ltnp | rg ':8291\b'
```

The final command must return no listener. Keep it closed for all remaining
staging tests.

### 4. Restart only the wrapper

```bash
systemctl restart meridian-iroh-wrapper.service
systemctl is-active meridian-iroh-wrapper.service
systemctl show meridian-iroh-wrapper.service \
  -p MainPID -p ActiveEnterTimestamp -p NRestarts
```

Re-read the App Server PID:

```bash
systemctl show meridian-appserver.service \
  -p MainPID -p ActiveEnterTimestamp -p NRestarts
```

The App Server PID and activation timestamp must match the baseline.

### 5. Verify listeners and logs

```bash
ss -ulnp | rg ':4501\b'
ss -ltnp | rg ':4500\b'
journalctl -u meridian-iroh-wrapper.service --since '-5 min' --no-pager
```

Reject the deployment for:

- any attempted port-8291 connection;
- fallback telemetry;
- authentication downgrade;
- repeated reconnects;
- unknown required protocol frames;
- runtime reattach failures;
- unbounded memory growth.

### 6. Run shim-off probes

Run:

```bash
scripts/iroh_probe.sh '<iroh-ticket>' \
  --agent-id '<agent-id>' \
  --conversation-id '<conversation-id>' \
  --admin-base-url http://127.0.0.1:9 \
  --scenario admin-rpc \
  --scenario no-http
```

Run the repository's two-client live-sync probe and every admin parity probe
added by the migration. Exercise at minimum:

- agent list/get/create/update/delete;
- explicit model and context-window changes followed by a new turn;
- conversation list/get/create/archive/restore;
- message hydration and pagination;
- normal streaming turn;
- Ask User answer;
- tool approval allow and deny;
- reconnect during a pending approval;
- external tool result settlement;
- cron and reflection operations;
- each retained admin screen;
- each intentionally unavailable capability;
- VibeSync project operations.

The probe must assert there were no connections to port 8291, not merely that
the request succeeded.

### 7. Run a real-device gate

Build/install the APK from the same merged commit and run:

```bash
scripts/iroh_device_gate.sh \
  --serial '<device-serial>' \
  --ticket '<iroh-ticket>' \
  --agent '<agent-id>' \
  --conversation '<conversation-id>' \
  --http-port 8291
```

The gate must report `PASS` and zero app TCP connections to port 8291.

### 8. Soak

Keep staging shim-off for a representative usage window. Monitor:

- wrapper restart count;
- App Server reconnect count;
- native capability failures;
- typed unavailable counts by method;
- heap and process RSS;
- turn latency;
- approval recovery;
- duplicate turns/messages;
- port-8291 connection attempts.

Do not proceed with unexplained fallback, direct-storage access, or authority
switches.

## Production Rollout

Use a canary before all users:

1. Confirm the staging gates are attached to the PR/release record.
2. Record production wrapper and App Server baselines.
3. Install the immutable artifact without changing the active symlink.
4. Install the minimal wrapper environment.
5. Switch the symlink atomically.
6. Stop LettaShim or block port 8291.
7. Restart only `meridian-iroh-wrapper.service`.
8. Confirm App Server PID continuity.
9. Run read-only probes.
10. Run one controlled mutation and retrieve it from the same authority.
11. Run a normal turn and approval/Ask User flow.
12. Observe the canary before broader client use.

The stable Iroh key and UDP port should preserve the existing ticket. If the
Node ID changes, stop the rollout and restore the original key before
continuing.

## Go/No-Go Checklist

Go only when every item is true:

- [ ] PR merged with all required checks green.
- [ ] Wrapper artifact built from the recorded merged SHA.
- [ ] Artifact checksum recorded.
- [ ] Wrapper has a standalone distribution and launcher.
- [ ] Ownership matrix has zero `shim_until_cutover` rows.
- [ ] Wrapper environment has no shim URL, direct-backend path, or provider key.
- [ ] Port 8291 is closed.
- [ ] Wrapper is active on UDP 4501 with its original Node ID.
- [ ] Letta App Server PID/start time did not change.
- [ ] Native and bounded-service parity probes pass.
- [ ] Ask User and approval reconnect tests pass.
- [ ] Device gate reports no HTTP leak.
- [ ] No fallback or direct-storage telemetry appears.
- [ ] Previous wrapper artifact and environment are available for rollback.

## Rollback

Rollback is wrapper-only unless the release included a separately reviewed data
or service migration.

1. Stop new canary activity.
2. Record current wrapper/App Server PIDs and logs.
3. Point `/opt/meridian/iroh-wrapper/current` to the previous immutable release.
4. Restore the previous wrapper environment.
5. Restart only `meridian-iroh-wrapper.service`.
6. Verify the original Iroh Node ID, UDP 4501 listener, and App Server PID
   continuity.
7. Run one read probe and one controlled turn.

If the previous wrapper still requires LettaShim, restoring that dependency is
an explicit rollback action, not a silent fallback. Record it in the incident
timeline and keep the retirement work open.

Never roll back by:

- resetting or editing the Letta backend files;
- replacing the Iroh secret key;
- restarting App Server without evidence it is unhealthy;
- running the wrapper from a mutable developer worktree;
- changing the conversation context manually to hide a failed deployment.

## Post-Deployment Cleanup

After the shim-free release completes its soak:

1. Remove the LettaShim service/container and deployment configuration.
2. Remove port-8291 firewall allowances and health checks.
3. Remove obsolete environment variables and secrets.
4. Delete proxy/fallback code and tests that assert old shim behavior.
5. Keep regression tests that assert the shim cannot be reached.
6. Remove the classpath-capture launcher and
   `/etc/meridian/iroh-wrapper-classpath.txt`.
7. Remove direct-backend production readers.
8. Update operator documentation and diagrams.
9. Close the corresponding `bd` issues only after production soak evidence is
   attached.

## Handoff Deliverables

The implementing agent must leave:

- the final ownership matrix;
- a packaged wrapper artifact;
- repository-owned systemd unit/template;
- minimal environment template without secrets;
- shim-off integration test and output;
- device-gate output;
- artifact checksum and merged commit;
- production deployment record;
- rollback record;
- remaining intentionally unavailable capabilities;
- follow-up `bd` issues for any non-blocking cleanup.
