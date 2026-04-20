# Release checklist

Minimal gate for cutting a letta-mobile release. Every item is either
automated (a `make` target) or a hard-to-forget manual step.

Owner: whoever is cutting the release.

---

## 1. Verify sync health on a real device (blocking)

The timeline-sync loop is the heart of the app. A single regression here
makes the product broken, and bugs have historically landed silently. Gate
the release on a live sync-drift check.

```bash
# Prereqs: a device connected via adb, the app installed & logged in, and
# the chat screen for the target conversation currently open.

make verify-sync \
    AGENT=agent-<id> \
    CONV=conv-<id> \
    ITERATIONS=6 \
    INTERVAL=10
```

The target runs `letta-cli sync-drift --watch` for 6 samples at 10s
intervals (~60s total) and **fails if any sample reports anything other
than `HEALTHY`.** `STALE` and `BROKEN` are both rejected — the release gate
is strict on purpose.

**Do not skip this step.** If the gate is known-broken at release time,
open a blocking issue before cutting.

## 2. Verify resume-stream delivery (blocking when applicable)

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

Alert rules under the same dashboard will fire warnings if any open chat
screen sees zero events for > 10 minutes (likely subscriber dead).

---

## Related

- `letta-mobile-mge5.5` — verify-sync Makefile target + gate (this doc).
- `letta-mobile-mge5.6` — StreamSubscriber telemetry + Grafana dashboard.
- `letta-mobile-mge5` (parent epic) — resume-stream subscription architecture.
- `letta-mobile-o7ob.3.5` — ANR / crash budget dashboard.

## Operational notes

- The `verify-stream` step currently requires a human-triggered send in
  the window. This is fine for a manual release gate. Fully-automated
  end-to-end coverage would need a second test identity scripted to send;
  deferred until we have CI device access.
- If `make verify-sync` fails because `adb` can't see the device, the
  target exits `2` (environmental error) rather than `1` (gate failure).
  Scripts driving the release pipeline should treat both as blockers but
  can distinguish the two for paging purposes.
