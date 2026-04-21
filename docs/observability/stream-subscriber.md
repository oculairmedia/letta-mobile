# StreamSubscriber observability

This document covers the telemetry emitted by `TimelineSyncLoop.runStreamSubscriber()`
and the Grafana dashboard + alert rules that consume it.

Tracking: `letta-mobile-mge5.6`.

## Telemetry contract

All events are emitted under tag `TimelineSync`.

| Name                             | When                                                                 | Attributes                                                        |
|----------------------------------|----------------------------------------------------------------------|-------------------------------------------------------------------|
| `streamSubscriber.opened`        | SSE connection established; server acknowledged with 200 + stream    | `conversationId`                                                  |
| `streamSubscriber.eventReceived` | Every SSE frame parsed (1 per `data:` line)                          | `conversationId`, `messageType`, `runId`                          |
| `streamSubscriber.ingested`      | A frame produced a novel timeline append (fires after `eventReceived`) | `conversationId`, `serverId`, `messageType`                       |
| `streamSubscriber.eventDeduped`  | A frame was recognised as already-seen and dropped                    | `conversationId`, `reason` (`otidSeen` \| `approvalAlreadyDecided`), plus `otid` / `approvalRequestId` |
| `streamSubscriber.closed`        | Stream ended cleanly (run finished)                                   | `conversationId`, `durationMs` (since opened), `eventsReceived`   |
| `streamSubscriber.idle404`       | Server returned `No active runs found` — idle backoff path            | `conversationId`, `backoffMs`, `via` (absent for typed exception, `apiException` for the 400-JSON variant) |
| `streamSubscriber.networkError`  | Transient HTTP/connection failure (not an idle 404), logged at ERROR with the throwable | `conversationId`, `errorClass`, `errorMessage`                    |

Notes:

- There is **no** `streamSubscriber.dormant` event. The subscriber does not
  gate on `subscriptionCount` — it runs for the full process lifetime while
  `ChatPushService` keeps the JVM alive. This is intentional (see comment
  on `runStreamSubscriber` in `TimelineSyncLoop.kt`).
- Events are delivered to every registered sink: Logcat (always), the
  in-app inspector (dev builds), and, if wired, a remote exporter.

## In-app inspector

Open the dev menu → Telemetry. Filter by tag `TimelineSync` and name prefix
`streamSubscriber` to see the subscriber's raw event stream. This is the
shortest path to diagnosing a live device.

## Remote export

Telemetry events are consumed by whichever sink the app ships with. For
self-hosted setups, the recommended pipeline is:

```
app → Telemetry.emit() → log line
    → Logcat
    → logcat→Loki shipper (if installed)
    → Loki → Grafana
```

All queries below assume a Loki datasource named `Loki` holding lines
matching `{app="com.letta.mobile"}` and extracting the JSON-ish attributes
from the `Telemetry` log formatter (tag/name/attrs rendered as JSON).

If you are on a different export stack, translate the LogQL queries into
your equivalent — the event names are stable.

## Dashboard

The dashboard JSON is committed at
`docs/observability/grafana-stream-subscriber.json`. Import it via
Grafana → Dashboards → New → Import → paste JSON.

Panels:

1. **Resume-stream delivery rate** — `eventReceived` per minute, stacked
   by conversationId. Flat zero means subscriber is dead across the board.
2. **Run duration histogram** — `closed.durationMs` distribution. Very
   short runs (< 500ms) cluster means agents are no-op'ing; very long
   (> 30s) means model stalls.
3. **Average backoff (idle fingerprint)** — running mean of `idle404.backoffMs`.
   High mean = sustained idle (expected during quiet hours). Sudden drop
   to `STREAM_BACKOFF_START_MS` = traffic returning.
4. **Activity density ratio** — `rate(eventReceived) / rate(idle404)`.
   Values near 1.0 = busy conversation. Values near 0.0 = idle. A ratio
   of exactly 0 sustained while we know traffic was posted = subscriber
   broken.
5. **Dedupe rate** — `eventDeduped` per minute. Expected to be small but
   non-zero (echoes of our own sends land as duplicates after reconcile).
   A spike indicates a hydrate/stream race.
6. **Network-error rate** — `networkError` per minute. Baseline near zero;
   anything sustained is a real connectivity problem.

## Alert rules

Encoded in `docs/observability/grafana-stream-subscriber-alerts.yaml`.

- **Subscriber silence**: no `eventReceived` AND no `opened` AND no `idle404`
  for a given `conversationId` for > 10 minutes while the chat screen is
  reported open. Fires `warn`.
- **Run-with-no-events**: `closed.eventsReceived == 0` for any run. Counts
  the mechanism broken if the run was > 1s (short runs are not suspicious
  — they can legitimately finish before we attach).
- **Sustained network error**: > 20 `networkError` events per 5-minute
  window. Fires `warn` (probably a server/proxy outage).

Thresholds are conservative — tune once we have a week of baseline data.

## Validation

To confirm the dashboard actually works against a live app:

1. Install a debug build on a device.
2. Open the chat screen for a conversation.
3. In Grafana, filter panels by that `conversationId`.
4. Have another client send a message into the conversation. Within a few
   seconds you should see a spike in `eventReceived` and `ingested`, and
   a `closed` tick once the run finishes. The in-app inspector should show
   the same events.
5. Leave the screen open for 2 minutes without any traffic; you should see
   regular `idle404` ticks at the exponentially increasing backoff cadence,
   capped at `STREAM_BACKOFF_MAX_MS`.

If step 4 shows nothing but step 5 shows idle ticks, the subscriber is
connecting but the server's resume-stream multiplexing is broken (see the
`make verify-stream` release gate for the same failure mode).
