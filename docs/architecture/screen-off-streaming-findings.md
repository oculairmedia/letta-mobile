# Screen-Off Streaming: Architecture Findings

**Date:** 2026-05-01
**Scope:** Why incoming messages may stop arriving when the device screen is off, and what the current architecture does (and does not) do to keep SSE/WebSocket subscribers alive.
**Status:** Historical findings. Several remediation items below have since shipped (heartbeat parsing/watchdog, OkHttp ping interval, battery optimization exemption UI, and inexact alarm service recovery). Use [`screen-off-streaming-measurement.md`](screen-off-streaming-measurement.md) for the current validation protocol before making a wake-lock product decision.

---

## TL;DR

The app already does the **right structural thing** to receive messages while the screen is off: a foreground service (`ChatPushService`) keeps the process alive, and SSE subscribers (`TimelineSyncLoop.runStreamSubscriber`) are explicitly designed to run for the full lifetime of the singleton repository — *not* gated on UI subscriber count.

What is **missing** is the network-layer durability that long-lived idle connections need on Android:

1. **No app-level SSE heartbeat / keep-alive cadence.** If the server emits no events for N minutes, neither side sends anything; intermediaries (mobile carrier NATs, OS network stack, server idle reaper) may silently drop the socket. The subscriber only learns about the dead connection when its next read fails — which on a perfectly idle TCP socket can take a *long* time.
2. **No OkHttp `pingInterval` configured.** The Ktor/OkHttp client is built without `pingInterval`, so the transport doesn’t emit TCP keepalives at the HTTP-client layer either.
3. **No `WAKE_LOCK` held by `ChatPushService`.** A foreground service prevents Android from killing the process, but it does **not** prevent the CPU from sleeping during Doze / App-Standby. Network reads on a sleeping CPU are deferred until the next maintenance window.
4. **No battery-optimization exemption flow.** Without the user adding the app to the “unrestricted” bucket, Doze restrictions apply normally.

The combined effect: when the screen is off and the chat is idle, the SSE subscriber is *technically* alive but its socket may be silently broken, and the CPU may not service network reads in time to detect this. The subscriber only notices when a real event tries to come through and the socket is already dead — at which point it falls into the exponential-backoff reconnect path, but by then the user has already missed the “instant” feel of a push notification.

---

## What works today

### 1. Foreground service keeps the process alive

`ChatPushService` (`android-compose/app/src/main/java/com/letta/mobile/channel/ChatPushService.kt`) is the linchpin:

- Promoted via `startForeground(..., FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)` (Android 10+). `REMOTE_MESSAGING` is the correct semantic and is **quota-exempt** on Android 14+ — important context, since the previous `DATA_SYNC` type hit a 6h rolling quota and crashed the process at launch (see `letta-mobile-50p2`).
- Wraps `startForeground` in `try/catch` so a refused promotion (`ForegroundServiceStartNotAllowedException` etc.) degrades to background-service rather than crashing.
- Holds an `IMPORTANCE_MIN`, `PRIORITY_MIN`, ongoing notification — the minimum visible footprint required for a foreground service.
- On create:
  - `installListener()` — wires `TimelineRepository.ingestedListener` to publish a notification when a new message arrives in a conversation the user is *not* currently viewing (`CurrentConversationTracker` suppresses the duplicate).
  - `warmupSubscribers()` — proactively calls `TimelineRepository.getOrCreate(id)` for the **5 most-recently-active conversations** (`WARMUP_CONVERSATION_COUNT`). The cap was tightened from 20 → 5 in `letta-mobile-qv6d` to avoid saturating OkHttp’s default `maxRequestsPerHost = 5` dispatcher.
- Returns `START_STICKY` from `onStartCommand` so Android restarts the service if it’s killed under memory pressure.

This is correct. The process *will* stay alive, and the subscribers *will* keep running.

### 2. Subscribers are deliberately not UI-gated

`TimelineSyncLoop.runStreamSubscriber()` (`core/src/main/java/com/letta/mobile/data/timeline/TimelineSyncLoop.kt:893`):

> `// Note: no foreground/subscriptionCount gate. The subscriber runs for`
> `// the full lifetime of this TimelineSyncLoop (which is a @Singleton-`
> `// scoped cache in TimelineRepository). Process lifetime is extended`
> `// via ChatPushService (a foreground service) so messages are delivered`
> `// even when the screen is off / app backgrounded. This is the push`
> `// architecture agreed on 2026-04-18 ...`

This was an explicit policy reversal. The previous `subscriptionCount`-gated design only ran the subscriber while a UI was actively observing — which meant background message delivery silently broke. The current design correctly trades a small amount of background CPU/network for working push.

The loop:

- Opens `messageApi.streamConversation(conversationId)` and parses with `SseParser.parse()`.
- On clean stream close: resets backoff to `STREAM_BACKOFF_START_MS`.
- On `NoActiveRunException` (idle 404): exponential backoff up to `STREAM_IDLE_BACKOFF_MAX_MS`.
- On `ApiException` 400 (server’s alternate “no active runs” / “EXPIRED” form): same idle path.
- On generic network errors: longer backoff.
- Emits granular telemetry under tag `"TimelineSync"`: `streamSubscriber.opened`, `eventReceived`, `closed`, `idle404`, `networkError`, `eventDeduped`, `merged`, `fuzzyCollapsed`, `ingested`, `listenerThrew`, `toTimelineEventNull`.

### 3. Singleton-scoped loop registry

`TimelineRepository` is `@Singleton` and caches one `TimelineSyncLoop` per conversation in a `loops: MutableMap<String, TimelineSyncLoop>` guarded by `loopsMutex`. Loops use `SupervisorJob() + Dispatchers.IO`. Once a loop exists, it persists for the process lifetime.

---

## What is missing

### A. No SSE app-level heartbeat

**Symptom:** Idle TCP sockets get killed by mobile carrier NATs (typical idle timeout: 5–15 minutes), corporate proxies, and server-side reapers — silently. The subscriber doesn’t notice until it tries to read and gets EOF/RST.

**Current behavior:** `runStreamSubscriber` only reacts to data events or stream close. There is no comment-line heartbeat (`: keep-alive\n\n`) being read or expected from the server, and no client-side timeout that forces a reconnect after N silent minutes.

**What this looks like in practice:** If a conversation has zero activity for 10 minutes while the screen is off, the next real message may not arrive until the subscriber fails its next read attempt, falls into backoff, and reconnects. With the worst-case `STREAM_IDLE_BACKOFF_MAX_MS` cap, that gap can be tens of seconds even after the socket drop is detected.

**Detection ideas (no decisions yet):**

- Check if the Letta server emits SSE comment heartbeats. If yes, add a client-side read timeout slightly larger than the server cadence — any longer silence triggers reconnect.
- If the server does not heartbeat, propose a client-side periodic POST/GET ping on a separate request to keep the OkHttp connection pool warm, *or* request a server-side change to emit heartbeats.

### B. No OkHttp `pingInterval`

**File:** `core/src/main/java/com/letta/mobile/data/api/LettaApiClient.kt`

```kotlin
install(HttpTimeout) {
    requestTimeoutMillis = 60_000
    connectTimeoutMillis = 30_000
    socketTimeoutMillis  = 60_000
}
```

There is **no** OkHttp engine configuration setting `pingInterval`. For long-lived streaming requests (SSE), OkHttp’s `pingInterval` triggers HTTP/2 PING frames (or, for HTTP/1.1, has no transport-level effect — but is still useful for surfacing dead sockets via the read timeout machinery).

`socketTimeoutMillis = 60_000` *will* eventually surface a dead socket as a `SocketTimeoutException`, but only on a 60s read silence. For idle conversations this means up to ~60s before the subscriber reacts to a dropped connection — and that 60s only starts counting when the *next* read is attempted.

### C. No `PARTIAL_WAKE_LOCK`

**File:** `ChatPushService.kt` (and nowhere else — verified by `grep WakeLock|PARTIAL_WAKE_LOCK|ACQUIRE`).

Foreground services keep the process from being killed but **do not** keep the CPU running. Under Doze (screen off + stationary device) and App-Standby (app not interacted with for a while), Android batches network and defers CPU. Without a wake lock:

- Network reads from the SSE socket are deferred to maintenance windows (typically every ~15min during Doze, longer in deep Doze).
- Backoff `delay(backoffMs)` calls are honored but on the system’s relaxed clock — short backoffs may stretch.
- Even if the socket *is* alive, message delivery latency balloons from “immediate” to “next Doze maintenance window”.

A `PARTIAL_WAKE_LOCK` on `ChatPushService` (held while there is at least one active subscriber) would keep the CPU alive enough to service network reads promptly. Cost: noticeable battery drain, especially with 5 warmed conversations each holding a long-lived connection. This is a tradeoff that needs an explicit product decision.

### D. No battery-optimization exemption prompt

There is no flow asking the user to add the app to the “unrestricted” battery bucket (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Without that, Doze and App-Standby apply normally even with a foreground service. Google Play reviews this permission carefully for messaging-style apps, but the use case here qualifies.

### E. No AlarmManager fallback

If `ChatPushService` is killed (e.g. user swipe-killed the app from Recents on some OEMs that ignore `START_STICKY`, or Android killed it under heavy memory pressure), there is no `AlarmManager` exact alarm to wake it back up. Once dead, the service stays dead until the user opens the app.

---

## Doze / App-Standby reference

Quick mental model for what restricts what when the screen is off:

| Condition | Network | Wake locks | Alarms | Foreground service helps? |
|---|---|---|---|---|
| Screen off, plugged in / moving | normal | normal | normal | n/a |
| Doze (screen off + stationary) | batched to maintenance windows | suspended | inexact only | partially — process stays alive, but CPU/network still restricted |
| App-Standby (app idle for days) | restricted | restricted | restricted | partially — same |
| App in restricted bucket | heavily throttled | heavily throttled | heavily throttled | minimally |

`FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING` is the right type for our use case, and Android documents it as appropriate for chat/messaging push channels. It does not, however, exempt the app from Doze on its own. Battery-optimization exemption is what crosses that line.

---

## Telemetry already in place

When debugging screen-off behavior, the following events (under tag `"TimelineSync"`) are the highest-signal:

- `streamSubscriber.opened` — subscriber successfully opened a stream. Includes `conversationId`.
- `streamSubscriber.eventReceived` — every raw frame. Includes `messageType`, `runId`. Use rate-vs-time to spot dead periods.
- `streamSubscriber.closed` — clean close. Includes `durationMs`, `eventsReceived`. **Closes with `eventsReceived = 0` are the canary** — they mean the server closed the stream without delivering anything, which is normal for `NoActiveRunException` cases but suspicious otherwise.
- `streamSubscriber.idle404` — server returned “no active run”. Includes `backoffMs`. High-rate idle404 == healthy idle. Sudden silence on a previously-chatty conversation == possible socket death.
- `streamSubscriber.networkError` — actual transport failure. Should correlate with screen-off + idle periods if the “silent socket death” hypothesis is right.

Service lifecycle:

- `ChatPushService:created` (`foregrounded` true/false)
- `ChatPushService:warmup.complete` / `warmup.failed`
- `ChatPushService:destroyed`

If `destroyed` fires while the user expects push to be working, the service was killed and we have a recovery problem (see Missing E).

---

## Ranked remediation options

In priority order, by impact-vs-cost. **No decisions made — these are candidates for `bd` issues.**

### 1. Add OkHttp `pingInterval` (cheap, low-risk)

Set `pingInterval` on the OkHttp engine in `LettaApiClient.kt`. Suggested: 30s. For HTTP/2 connections this triggers actual PING frames; for HTTP/1.1 it influences the transport read-timeout machinery so dead sockets surface faster. Battery impact: negligible (a few bytes every 30s per active connection).

### 2. Add app-level SSE heartbeat handling (cheap, low-risk if server cooperates)

Determine if the Letta server emits SSE comment heartbeats. If yes:

- Add a read-side watchdog in `runStreamSubscriber`: if no frame (data *or* heartbeat) is received within `serverHeartbeatPeriod * 2`, force-close the socket and reconnect.

If no:

- File a server-side issue to add periodic SSE comment heartbeats (e.g. `: ping` every 30s).
- As a stopgap, accept the 60s `socketTimeoutMillis` as the detection window and document the latency floor.

### 3. Add `PARTIAL_WAKE_LOCK` to `ChatPushService` (medium cost, high impact)

Acquire while the service is started, release on `onDestroy`. Keeps the CPU alive enough to service network reads during Doze. **Battery cost is real** — needs measurement on a real device under typical usage. Pair with a kill-switch (a setting to disable “high-priority delivery” for users who care more about battery).

### 4. Add battery-optimization exemption prompt (cheap, user-facing)

One-time prompt during onboarding (or after the first missed-message complaint) asking the user to allow the app to “run in the background without restrictions”. Standard Android intent: `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Document in user-facing copy that this is required for reliable chat delivery while the screen is off.

### 5. Add AlarmManager-based service revival (medium cost, recovery-only)

Schedule an inexact `AlarmManager` alarm every ~15 minutes that fires `ChatPushService.start(context)`. This is a no-op if the service is already running, and a recovery if it was killed. Inexact alarms are Doze-friendly and run during maintenance windows.

### 6. Tune OkHttp connection pool for long-lived SSE (cheap, contextual)

The default `ConnectionPool` evicts idle HTTP/2 connections after 5 minutes of no requests. For SSE the connection is *not* idle from the application’s perspective but may appear so to OkHttp. Confirm behavior under realistic loads; consider a custom pool with a longer idle timeout if measurements show eviction-driven reconnects.

---

## What this document explicitly does not do

- **No code changes.** This is a findings doc.
- **No commitment to any of the remediation options.** Each one has tradeoffs (especially #3) that need product input.
- **No measurement of actual current latency.** All claims about “may take tens of seconds” are derived from the code paths and Android documentation, not from instrumented runs. A follow-up `bd` issue should add structured measurement: screen-off + idle conversation + observed first-message latency, across a few devices and network conditions.

---

## Files touched (read-only) while writing this doc

- `android-compose/app/src/main/java/com/letta/mobile/channel/ChatPushService.kt`
- `android-compose/core/src/main/java/com/letta/mobile/data/timeline/TimelineRepository.kt`
- `android-compose/core/src/main/java/com/letta/mobile/data/timeline/TimelineSyncLoop.kt` (lines ~880–1000, focus on `runStreamSubscriber`)
- `android-compose/core/src/main/java/com/letta/mobile/data/api/LettaApiClient.kt`
- `android-compose/core/src/main/java/com/letta/mobile/data/stream/SseParser.kt`

## Related prior decisions

- `letta-mobile-mge5` — push architecture epic; introduced `ChatPushService` and the subscriber-not-UI-gated policy.
- `letta-mobile-50p2` — switched foreground service type from `DATA_SYNC` to `REMOTE_MESSAGING` to dodge the Android 14+ 6h quota crash loop.
- `letta-mobile-qv6d` — reduced warmup count from 20 → 5 conversations after observing OkHttp dispatcher saturation (`maxRequestsPerHost = 5`) starving foreground sends.
- 2026-04-18 — explicit reversal of `subscriptionCount`-gated subscribers; subscribers now run for full process lifetime.

---

*End of findings.*
