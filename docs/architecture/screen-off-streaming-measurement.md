# Screen-Off Streaming Measurement Protocol

**Date:** 2026-05-02  
**Bead:** `letta-mobile-jmzq.9`  
**Build baseline:** `0d30f0ab Add chat push alarm recovery` or newer  
**Scope:** Measure whether persistent chat streams continue delivering assistant output while the Android device screen is off, and distinguish normal delivery from stale-socket reconnects, Doze deferral, or service death.

---

## Current reliability stack to measure

Run this protocol after the following screen-off reliability pieces are present:

1. SSE heartbeat parsing and heartbeat telemetry.
2. Stream silence watchdog and reconnect on missing liveness.
3. OkHttp long-lived transport tuning (`pingInterval(30s)`).
4. Screen-off diagnostics telemetry.
5. Battery optimization exemption UI/status flow.
6. Inexact `AlarmManager` recovery alarm for `ChatPushService`.

The alarm fallback is **recovery-only**. It should revive a killed service during a future maintenance window, not provide instant Doze delivery.

---

## Primary questions

1. Does an active conversation keep receiving assistant stream output while the screen is off?
2. Does the notification/timeline update arrive without waking/unlocking the device?
3. After 5, 15, and 30 minutes idle, is first-message latency acceptable?
4. If latency is high, is the cause:
   - service death,
   - stale socket / watchdog reconnect,
   - idle polling/backoff,
   - Android Doze maintenance deferral,
   - network transition or carrier behavior?
5. Does battery optimization exemption materially improve latency/reliability?
6. Is an opt-in wake-lock mode (`letta-mobile-jmzq.8`) justified by measured improvement versus battery risk?

---

## Instrumentation to capture

Use the in-app telemetry screen filtered to these tags, and optionally `adb logcat` with matching tags.

### `BatteryOptimization`

- `status` with `exempt=true|false`
- `requestTapped`
- `requestLaunched`
- `requestReturned`
- `requestFailed`

### `ChatPushService`

- `started`
- `created` with `foregrounded=true|false`
- `startCommand`
- `warmup.start`
- `warmup.complete` / `warmup.failed`
- `suppressedForegroundConv`
- `destroyed`
- `startForeground.failed`
- `startFailed`

### `TimelineSync`

- `streamSubscriber.opened`
- `streamSubscriber.heartbeat`
- `streamSubscriber.eventReceived`
- `streamSubscriber.silenceTimeout`
- `streamSubscriber.watchdogReconnect`
- `streamSubscriber.closed`
- `streamSubscriber.idle404`
- `streamSubscriber.networkError`
- `streamSubscriber.ingested` / notification-adjacent ingestion events if visible in the build

### `ChatPushAlarm`

- `scheduled`
- `cancelled`
- `fired`
- `startFailed`
- `scheduleSkipped`
- `scheduleFailed`

---

## Device/run metadata

Record this once per session:

| Field | Value |
|---|---|
| Tester |  |
| Date/time |  |
| Device model |  |
| Android version |  |
| App commit/build |  |
| App installed fresh or upgraded? |  |
| Network baseline | Wi-Fi / mobile data / VPN / captive portal |
| Battery level |  |
| Plugged in? | yes / no |
| Android app battery mode before exemption | optimized / unrestricted / restricted / unknown |
| Notification permission granted? | yes / no |
| Foreground service notification visible? | yes / no |
| Test conversation ID/name |  |
| Remote sender/client used to trigger message | Android app / Matrix / web / API / other |

---

## Quick smoke test: unrestricted battery mode

Use this first while the user is already testing unrestricted background delivery.

1. Open Settings → Background delivery.
2. Verify the app reports **Unrestricted**.
3. Open a chat that has a persistent/client-mode stream active.
4. Confirm recent telemetry includes:
   - `BatteryOptimization.status exempt=true`
   - `ChatPushService.started`
   - `ChatPushService.created foregrounded=true` or a recent `startCommand`
   - `ChatPushAlarm.scheduled`
   - `TimelineSync.streamSubscriber.opened` for the target conversation
5. Send or trigger an assistant response.
6. Turn the screen off immediately.
7. Wait 2–5 minutes.
8. Wake the phone and record:
   - whether the conversation continued streaming,
   - whether a notification/timeline update arrived while locked,
   - first visible arrival time or latency estimate,
   - last heartbeat/event time,
   - any `silenceTimeout`, `watchdogReconnect`, `networkError`, `destroyed`, or `ChatPushAlarm.fired` events.
9. If successful, repeat with a 10–15 minute idle before triggering the remote message.

---

## Full measurement matrix

Prefer one device first, then repeat the highest-risk scenarios on a second Android/OEM if available.

| Scenario ID | Network | Power/device state | Battery exempt? | Idle before remote message | Expected signal |
|---|---|---|---|---:|---|
| S1 | Wi-Fi | screen on | no | 0 min | Baseline latency and telemetry shape |
| S2 | Wi-Fi | screen off, plugged in or recently moved | no | 5 min | FGS + heartbeat/watchdog under light restrictions |
| S3 | Wi-Fi | screen off, stationary, battery | no | 15 min | Optimized/default Doze behavior |
| S4 | Wi-Fi | screen off, stationary, battery | no | 30 min | Longer idle default behavior |
| S5 | Wi-Fi | screen on | yes | 0 min | Exempt baseline |
| S6 | Wi-Fi | screen off, plugged in or recently moved | yes | 5 min | Exempt light restrictions |
| S7 | Wi-Fi | screen off, stationary, battery | yes | 15 min | Primary target for reliable background delivery |
| S8 | Wi-Fi | screen off, stationary, battery | yes | 30 min | Longer idle exempt behavior |
| S9 | Mobile data | screen off, stationary, battery | yes | 5 min | Carrier/NAT behavior |
| S10 | Mobile data | screen off, stationary, battery | yes | 15 min | Carrier/NAT + Doze behavior |
| S11 | Wi-Fi | service killed/process killed if safely possible | yes | next alarm window | Alarm recovery attempts service restart |
| S12 | Wi-Fi | optional forced Doze debug device | yes | 5–15 min | Stress/debug signal only, not user-realistic |

If wake-lock mode is implemented later, duplicate S6–S10 with wake-lock off/on and compare first-message latency plus battery impact.

---

## Per-run result table

Copy one row per run.

| Run | Scenario | Device/network | Exempt? | Plugged in? | Idle duration | Service alive before trigger? | Last stream open | Last heartbeat/event before trigger | Delivery latency | Notification shown? | Reconnect/service/alarm events | Outcome | Follow-up bead |
|---|---|---|---|---|---:|---|---|---|---:|---|---|---|---|
|  |  |  | yes/no | yes/no |  | yes/no/unknown |  |  |  | yes/no |  | pass/fail/inconclusive |  |

### Outcome labels

- **pass:** message arrives promptly and telemetry shows continuous stream liveness or fast reconnect.
- **delayed-doze:** no service death; delivery waits for a likely Doze maintenance window.
- **stale-socket:** `silenceTimeout`, `networkError`, or `watchdogReconnect` precedes delivery.
- **service-death:** `ChatPushService.destroyed`, missing service liveness, or recovery alarm needed.
- **permission/config:** notification permission, battery optimization, auth, or service start precondition prevented expected behavior.
- **inconclusive:** missing timestamps or external trigger time.

---

## Timestamp discipline

For each remote-triggered message, capture three timestamps as precisely as possible:

1. **Remote send time** — when Matrix/web/API/other client sent the message or started the run.
2. **Local telemetry arrival time** — first `TimelineSync.streamSubscriber.eventReceived` or ingestion event for that run/message.
3. **User-visible time** — notification timestamp or first observed timeline update after waking.

Delivery latency is `local telemetry arrival - remote send time`. If telemetry is unavailable, use `user-visible time - remote send time` and mark the run lower confidence.

---

## Interpreting common patterns

### Healthy continuous stream

Likely signals:

- `ChatPushService` remains alive.
- `TimelineSync.streamSubscriber.heartbeat` continues at least every telemetry sampling window.
- Remote message produces `streamSubscriber.eventReceived` without a preceding `silenceTimeout` or `networkError`.
- Latency remains near screen-on baseline.

Recommendation: balanced mode is probably sufficient for that scenario.

### Stale socket recovered by watchdog

Likely signals:

- Long gap since last heartbeat/event.
- `streamSubscriber.silenceTimeout` followed by `streamSubscriber.watchdogReconnect`.
- Delivery happens after reconnect delay.

Recommendation: keep watchdog; investigate server heartbeat cadence or mobile-network behavior if frequent.

### Service killed, alarm attempted recovery

Likely signals:

- `ChatPushService.destroyed` or no recent service liveness.
- `ChatPushAlarm.fired`.
- `ChatPushService.started` or `ChatPushAlarm.startFailed` after alarm.

Recommendation: alarm fallback is working as designed if recovery happens; file follow-up only if the service dies often or start is repeatedly rejected.

### Doze deferral despite live service

Likely signals:

- Service is alive.
- No immediate event/heartbeat while screen off stationary.
- Delivery clusters near device wake or a maintenance window.
- No clear network error or service death.

Recommendation: this is the main evidence needed before considering wake-lock mode. Compare optimized vs unrestricted battery mode first.

---

## Optional adb helpers

Use only on a debug/test device; forced Doze is a stress signal and may be harsher than normal user behavior.

```bash
adb logcat | grep -E 'TimelineSync|ChatPushService|ChatPushAlarm|BatteryOptimization'

# Force idle for stress testing.
adb shell dumpsys deviceidle force-idle

# Return to normal behavior.
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
```

Manual receiver testing can verify that the broadcast path does not crash, but it is not a substitute for waiting for a real inexact alarm window.

---

## Recommendation gate for wake-lock mode

Do **not** enable a wake-lock mode by default from architecture alone. Consider implementing or shipping `letta-mobile-jmzq.8` only if measurements show one of these persistent gaps after battery exemption and alarm recovery:

- Exempt screen-off first-message latency is still unacceptable at 15–30 minute idle.
- Service stays alive, but CPU/network delivery is repeatedly deferred until maintenance windows.
- Wake-lock prototype demonstrates a clear latency improvement with acceptable battery cost.

If implemented, wake-lock mode should remain explicit and user-facing, for example “High-priority delivery,” with copy that explains higher battery use.

---

## Results log

No formal measurements have been recorded in-repo yet. Add completed rows here or in a linked issue comment before closing `letta-mobile-jmzq.9`.

| Run | Scenario | Device/network | Exempt? | Idle duration | Delivery latency | Reconnect/service/alarm events | Outcome | Notes |
|---|---|---|---|---:|---:|---|---|---|
|  |  |  |  |  |  |  |  |  |

---

## Observed behaviors

### 2026-05-02 — Client Mode WS torn down on screen-off (`letta-mobile-etc1`)

**Symptom:** users reported that an in-flight Client Mode (LettaBot WS gateway) run was cancelled the moment the phone screen turned off / the app left the foreground. Timeline-mode runs were not affected — they continued via the singleton `TimelineSyncLoop` kept alive by `ChatPushService`.

**Root cause:** `ClientModeController` was registered as a `ProcessLifecycleOwner` observer and called `botGateway.stop()` from `onStop`. That destroyed the per-agent WS session under the active `ClientModeChatSender.streamMessage(...)` collector inside `AdminChatViewModel.sendMessageViaClientMode`, terminating the run mid-stream.

**Fix:** the bot gateway is now process-scoped. Its lifecycle follows the Client Mode settings (enabled + base URL), not the UI lifecycle. `onStop` no longer tears the gateway down (`ClientModeController.stopGatewayOnAppBackground = false`); the WS transport stays alive across screen-off, consistent with how the SSE timeline subscribers behave.

**Telemetry to confirm in the field:**

- `ClientModeController.appBackgrounded gatewayKeptAlive=true` fires on screen-off / app background.
- No `WsBotClient` / `RemoteBotSession` close events fire purely due to backgrounding.
- `TimelineSync.streamSubscriber.eventReceived` continues to ingest assistant chunks while the screen is off for the same conversation.

**Regression test:** `ClientModeControllerTest` asserts `botGateway.stop()` is not called from `onStop`.

### 2026-05-02 — Sleep/wake follow-up: terminal snapshot duplication

**Symptom:** after the gateway-lifetime fix, a field retest showed the Client Mode run survived screen-off and continued tool execution, but returning to the chat could duplicate assistant content and, in the same run, appear to miss the final assistant response.

**Finding:** the timeline-backed Client Mode ASSISTANT merge path computed an idempotent `merged` value for snapshot-shaped frames but did not use it, so a text-bearing terminal/snapshot frame could append the current assistant accumulator again. A first fix applied the prefix collapse to all ASSISTANT frames, but review caught that this would violate the WS gateway's pure-delta contract and could reintroduce the old prefix-collision text-loss bug.

**Fix:** normal ASSISTANT frames remain byte-for-byte delta appends. Prefix/idempotency collapse is limited to defensive text-bearing `done=true` terminal frames, which should normally be empty but can surface as snapshot-shaped around reconnect/sleep-wake edges. A regression test covers terminal-snapshot dedup without weakening the existing byte-perfect delta tests.

**Field result:** the user retested the installed build and reported the issue appears fixed; continue to watch for separate missing-terminal-frame evidence before reopening.

### 2026-05-02 — Fresh Client Mode route rejected pre-created conversation (`letta-mobile-w4pp`)

**Symptom:** existing Client Mode conversations persisted after the screen-off fix, but starting a new conversation showed the thinking indicator briefly and then it disappeared with no assistant response.

**Device logs:** fresh-route sends created an app-side empty conversation first, appended the optimistic user Local, then called the WS gateway with that pre-created conversation id. The gateway immediately returned `BotGatewayException: Missing "type" field` before emitting any assistant chunks.

**Fix:** fresh Client Mode routes no longer pre-create the Letta conversation for the WS send path. They pass `conversationId=null`, let the gateway allocate the real conversation, and migrate the optimistic user/assistant locals to the timeline once the gateway echoes the new conversation id. The VM also pins that returned id into `activeConversationId` so toggling Client Mode off stays on the newly-created conversation instead of falling back to the agent's previous chat.

**Regression coverage:** `AdminChatViewModelTest` fresh-route cases now assert no app-side `createConversation` preflight, `streamMessage(..., conversationId = null)`, optimistic bubble visibility before the first gateway chunk, migration to the gateway-created conversation, and persistence of that new conversation when Client Mode is toggled off.

---

## Follow-up issue rules

File a bead for each reproducible failure pattern:

- repeated service death despite alarm recovery,
- repeated `ChatPushAlarm.startFailed`,
- stale sockets not caught within the 90s silence watchdog,
- unacceptable exempt-mode Doze latency,
- notification/timeline mismatch where telemetry receives events but user-visible UI does not update.

Close `letta-mobile-jmzq.9` only after enough rows exist to recommend one of:

1. balanced mode remains the default; no wake-lock needed now,
2. high-priority wake-lock mode should be implemented as opt-in,
3. further transport/service reliability work is needed before product decision.
