# Release checklist

Minimal gate for cutting a letta-mobile release. The preferred entrypoint is
`make verify-release`, which runs the currently wired gates in prereq order,
skips gates whose prerequisites are unavailable, and writes a local report to
`reports/verify-release-<timestamp>.md`.

Owner: whoever is cutting the release.

---

## 1. Verify sync health on a real device (advisory until `letta-mobile-r6j3` closes)

The orchestrated version of this gate runs automatically when `verify-release`
has the device/bootstrap prerequisites (`DEVICE`, `APK`, `BASE_URL`, `API_KEY`,
`AGENT`, `CONV`). The standalone target remains useful for focused debugging.

The timeline-sync loop is the heart of the app. A single regression here
makes the product broken, and bugs have historically landed silently. Gate
the release on a live sync-drift check.

```bash
# Prereqs: a device connected via adb, the app installed & logged in, and
# the chat screen for the target conversation currently open.
# For debug-only automated auth bootstrap with a disposable non-production
# identity, see docs/RELEASE-AUTOMATION.md.

make verify-sync \
    AGENT=agent-<id> \
    CONV=conv-<id> \
    ITERATIONS=6 \
    INTERVAL=10
```

The target runs `letta-cli sync-drift --watch` for 6 samples at 10s
intervals (~60s total) and **fails the standalone command if any sample reports
anything other than `HEALTHY`.** In this gate, `HEALTHY` means the device is within a
bounded heartbeat lag window: under 30 seconds of drift and fewer than 5
messages outstanding relative to the persisted `processed::<conversation>`
cursor. `STALE` and `BROKEN` are both rejected.

In `make verify-release`, this gate is currently classified as **advisory**
until `letta-mobile-r6j3` closes because the internal cursor source is still
debug-build-only (`letta-mobile-8f1v`) and the initial post-bootstrap warm-up
window can produce a first-sample flake (`letta-mobile-r6j3.1`).

## 2. Verify resume-stream delivery (advisory until `letta-mobile-r6j3` closes)

`verify-release` auto-triggers this gate by starting a background streaming
conversation request during the watch window when `LETTA_TOKEN` / `API_KEY` is
available. Run the standalone target when you want to inspect the raw SSE
output yourself.

Confirms the server-side push path is intact end-to-end. Independent of
the client: no device needed.

```bash
# In one shell:
make verify-stream CONV=conv-<id> STREAM_TIMEOUT=60

# In another shell, during the STREAM_TIMEOUT window: send a message to
# the target conversation (via the app, Matrix bridge, letta-cli, or any
# other client). Multiple sends are fine.
```

The target asserts that at least one SSE event was received from the
resume-stream endpoint. Zero events fails the build.

In `make verify-release`, this gate is currently **advisory** while
`letta-mobile-50m6` tracks the remaining release-path mismatch work under the
`letta-mobile-r6j3` observability epic.

**Expected behavior:** On self-hosted Letta ≥ 0.16.7 where we have verified
the resume-stream multiplexing semantics, this must pass. If it fails with
"no active runs" while a run is demonstrably active, the server has
regressed — open a blocker against the server repo before release.

## 3. Observability sanity

Open the in-app **Telemetry inspector** (dev menu → Telemetry) and confirm
the `TimelineSync` subsystem is emitting events during normal use:

- `streamSubscriber.opened` appears when a run starts.
- `streamSubscriber.eventReceived` appears per SSE frame.
- `streamSubscriber.ingested` / `streamSubscriber.deduped` track ingestion.
- `streamSubscriber.idle404` appears while idle (roughly at the backoff
  cadence: 2s → 4s → 8s → 16s → 30s).
- `streamSubscriber.closed` appears after each run finishes.

If **any** of these categories are completely missing during a 60s live
session, observability has regressed — block the release.

## 4. Build & packaging

Standard. Covered by the usual gradle flow:

```bash
cd android-compose
./gradlew app:bundleRelease app:assembleRelease
```

Verify the tag is set, the version bump lands, and the signed artifacts
are uploaded.

## 4a. Orchestrated verify-release entrypoint

```bash
make verify-release \
    DEVICE=<serial> \
    APK=/absolute/path/to/app-debug.apk \
    BASE_URL=http://192.168.50.90:8289 \
    API_KEY=<disposable-token> \
    AGENT=agent-<id> \
    CONV=conv-<id>
```

- `make verify-release VERIFY_RELEASE_ARGS=--json ...` prints structured JSON
  to stdout for CI consumers while still writing the markdown report locally.
- The orchestrator keeps the current gate policy and recent runs in
  `docs/RELEASE-GATE-LEDGER.md`.

## 5. Post-release monitoring

If Grafana is wired:

- Watch the **Resume-stream delivery rate** panel for 24h after rollout.
  A flat line at zero across multiple conversations means the subscriber
  is broken for the new build.
- Watch the **Idle 404 / events ratio** panel. A sudden shift toward 100%
  idle indicates runs are happening but aren't being caught.
- Watch the **ANR / crash budget** panel (from the performance epic) — a
  spike within the first 2h of rollout usually means a regression slipped
  past the pre-release gate.
  Import path / source of truth:
  `docs/observability/sentry-anr-crash-budget-dashboard.json`.

Alert rules under the same dashboard will fire warnings if any open chat
screen sees zero events for > 10 minutes (likely subscriber dead).

---

## Related

- `letta-mobile-mge5.5` — verify-sync Makefile target + gate (this doc).
- `letta-mobile-mge5.6` — StreamSubscriber telemetry + Grafana dashboard.
- `letta-mobile-mge5` (parent epic) — resume-stream subscription architecture.
- `letta-mobile-o7ob.3.5` — ANR / crash budget dashboard.
- `letta-mobile-7973` — verify-release orchestrator and reporting.

## Operational notes

- The standalone `verify-stream` target still supports the original
  human-triggered workflow, but it can also self-trigger a background run when
  `STREAM_SEND_TEXT` plus `LETTA_TOKEN` are provided.
- If `make verify-sync` fails because `adb` can't see the device, the
  target exits `2` (environmental error) rather than `1` (gate failure).
  Scripts driving the release pipeline should treat both as blockers but
  can distinguish the two for paging purposes.
- `processed::<conversation>` is written by the background
  `ChannelHeartbeatSync` worker, not by the foreground chat timeline. Small
  outstanding counts immediately after bootstrap are therefore expected; the
  HEALTHY threshold intentionally allows bounded lag instead of requiring a
  literal zero-gap cursor.
