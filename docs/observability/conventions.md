# Telemetry conventions

This doc codifies the conventions every `Telemetry.*` call site in the app
should follow. Tracked as part of `letta-mobile-2uzn` (observability
expansion).

If you add a new Telemetry event, read this first. If you're reviewing a
PR that adds telemetry, these are the things to check for.

Tests that rely on Telemetry snapshots (e.g. the `letta-mobile-gqz3`
regression test) depend on these conventions being honored. Breaking
them silently is a correctness bug.

---

## 1. Three call shapes only

There are exactly three recommended call shapes. Nothing else.

```kotlin
// 1. Point event — "something happened"
Telemetry.event("Tag", "verb.noun", "key" to value, …)

// 2. Measured span — "this block took N ms"
val result = Telemetry.measure("Tag", "verb.noun", "key" to value) { … }

// 3. Error event — "this blew up, here's the throwable"
Telemetry.error("Tag", "verb.failed", throwable, "key" to value, …)
```

### Anti-patterns to avoid

```kotlin
// ❌ Don't manually add errorClass/errorMessage. Use Telemetry.error().
Telemetry.event(
    "Tag", "verb.failed",
    "errorClass" to t::class.simpleName,
    "errorMessage" to (t.message ?: ""),
    level = Telemetry.Level.ERROR,
)

// ✅ The one-liner below is equivalent AND carries the stack trace to
// Logcat via Log.e(tag, body, throwable).
Telemetry.error("Tag", "verb.failed", t)
```

Why: `Telemetry.error()` automatically injects `errorClass` +
`errorMessage` attributes and also carries the `Throwable` through to
Logcat so `adb logcat` shows a real stack trace. Hand-rolled equivalents
lose the stack trace.

**Linter intent**: if we ever add a KtLint/detekt rule, it should flag
`Telemetry.event(..., level = Telemetry.Level.ERROR)` and suggest
`Telemetry.error(...)`.

---

## 2. Tag naming — the "subsystem"

The tag is the first argument. It identifies which subsystem the event
comes from and is the primary axis for filtering in Grafana / the
in-app inspector.

**Rules**:

- PascalCase. `TimelineSync`, `ChatPushService`, `Http`.
- No abbreviations unless the full name would be >20 chars.
- **One tag per file / class.** Do not mix tags in a single source file.
- If a class has both VM-layer and domain-layer concerns, split the
  telemetry into two tags with distinct prefixes (e.g. `AdminChatVM`
  for user-visible state transitions vs `AdminChat.repo` for data).
- Never create a new tag without adding a row to
  `docs/observability/coverage-matrix.md`.

### Known tag inventory (canonical)

| Tag                  | Owner module                              | Purpose |
|----------------------|-------------------------------------------|---------|
| `TimelineSync`       | core/data/timeline/TimelineSyncLoop.kt    | sync loop, stream subscriber, reconcile |
| `Timeline`           | core/data/timeline/Timeline.kt            | timeline data-structure invariants |
| `TimelineRepo`       | core/data/timeline/TimelineRepository.kt  | repository cache |
| `Http`               | core/data/api/TelemetryInterceptor.kt     | HTTP round trips |
| `ChatPushService`    | app/channel/ChatPushService.kt            | foreground service lifecycle |
| `ChatComposerAttach` | app/ui/screens/chat/ChatComposerAttach.kt | attachment decoding |
| `AdminChatVM`        | app/ui/screens/chat/AdminChatViewModel.kt | chat screen actions |

Open issue: `AdminChatViewModel.kt` currently emits under both
`AdminChatVM` and `AdminChatViewModel`. Must be consolidated to
`AdminChatVM` — tracked in a child of `letta-mobile-2uzn`.

---

## 3. Name conventions — the "verb"

The second argument is the event name. It's a **dotted verb.noun phrase**
that reads naturally after the tag.

**Rules**:

- lowerCamelCase parts, separated by dots: `streamSubscriber.opened`,
  `send.roundtrip`, `loadAndNormalize.failed`.
- First token is the operation or state machine.
- Second token (if present) is the lifecycle phase (`start`, `ok`,
  `failed`, `retry`, `completed`, `deduped`).
- Pair phases consistently: if you emit `x.start`, also emit one of
  `x.ok` / `x.failed` / `x.completed`. Never leave a span dangling.
- Prefer `verb.failed` over `verb.error` — it reads better with the
  `Telemetry.error(...)` helper.
- For a point event with no lifecycle, use a single bare verb:
  `duplicateOtid`, `cacheHit`, `warmupComplete`.

### Canonical phase suffixes

| Suffix       | Use                                                      |
|--------------|----------------------------------------------------------|
| `.start`     | Work began. Always paired with `.ok` or `.failed`.       |
| `.ok`        | Work finished successfully.                              |
| `.failed`    | Work threw or produced a user-visible failure.           |
| `.completed` | Long-lived state ended (e.g. `streamSubscriber.closed`). |
| `.retry`     | Automatic retry attempted. Include `attempt` attr.       |
| `.deduped`   | Input was recognized as duplicate and dropped.           |

### Anti-patterns

```kotlin
// ❌ Ambiguous — is this the start or the end?
Telemetry.event("TimelineSync", "hydrate", …)

// ✅ Unambiguous pair.
Telemetry.event("TimelineSync", "hydrate.start", …)
// … later …
Telemetry.event("TimelineSync", "hydrate.ok", "rawCount" to n, durationMs = ms)

// ✅ Or use a measured span which emits both automatically.
Telemetry.measure("TimelineSync", "hydrate") { … }
```

---

## 4. Attribute conventions — the payload

Attributes are the third-and-beyond varargs. They're how Grafana /
alerts / the inspector pivot on events.

**Rules**:

- **Flat key-value only.** Never nest maps or lists — stringify first if
  you need structure.
- Keys are `lowerCamelCase` and as short as possible while still being
  unambiguous: `conversationId`, `durationMs`, `backoffMs`, `runId`.
- **Always use the canonical key for cross-subsystem concerns.** See the
  table below. This is the #1 thing that makes dashboards easy to build.
- Emit enumerable values as plain strings. E.g. `"via" to "apiException"`
  rather than `"via" to 2`.
- Emit booleans as booleans (`true`/`false`) — the Telemetry serializer
  handles them fine.
- Emit numbers as their natural Kotlin type; the serializer handles Long,
  Int, Double, Float consistently.
- Do not include PII. No message content, no user text, no tokens. Ids
  are OK.

### Canonical attribute keys

Use these EXACT spellings so dashboards can filter across subsystems:

| Key                | Type    | Meaning                                        |
|--------------------|---------|------------------------------------------------|
| `conversationId`   | String  | `conv-…` — the conversation UUID.             |
| `agentId`          | String  | `agent-…` — the owning agent.                 |
| `runId`            | String  | `run-…` — the model run id, when known.       |
| `messageId`        | String  | `message-…` — server-assigned id.             |
| `otid`             | String  | client-assigned message correlation id.       |
| `serverId`         | String  | any server-assigned id not covered above.     |
| `durationMs`       | Long    | elapsed wall time. Always `durationMs`, never `ms` / `duration` / `elapsed`. |
| `backoffMs`        | Long    | current retry backoff.                        |
| `attempt`          | Int     | 1-indexed retry attempt counter.              |
| `errorClass`       | String  | `throwable.javaClass.simpleName`. Auto-injected by `Telemetry.error()`. |
| `errorMessage`     | String  | `throwable.message`. Auto-injected by `Telemetry.error()`. |
| `via`              | String  | short label disambiguating a branch (e.g. `apiException`). |
| `result`           | String  | enum-like outcome for non-throwing branches (`ok` / `empty` / `decodeFailed`). |
| `status`           | Int     | HTTP status code.                             |
| `path`             | String  | HTTP path (no query).                         |
| `method`           | String  | HTTP method (`GET` / `POST` / `PATCH`).       |

### Examples

```kotlin
// ✅ Canonical keys, flat structure.
Telemetry.event(
    "TimelineSync", "streamSubscriber.opened",
    "conversationId" to conversationId,
    "runId" to runId,
)

// ❌ Key drift breaks dashboards. Don't.
Telemetry.event(
    "TimelineSync", "streamSubscriber.opened",
    "conv_id" to conversationId,     // — should be conversationId
    "run" to runId,                  // — should be runId
    "elapsed" to ms,                 // — should be durationMs (via named param)
)
```

---

## 5. Levels — default to INFO

`Telemetry.event()` defaults to `Level.INFO`. Only deviate when you have
a reason.

- `DEBUG` — high-volume / low-signal (e.g. every HTTP request on a busy
  screen). Goes through `Log.d`.
- `INFO` — the default. Meaningful lifecycle transitions.
- `WARN` — unexpected but recoverable (e.g. listener threw in the
  subscriber; subscriber kept running).
- `ERROR` — always use `Telemetry.error(…, throwable)` which sets this
  automatically.

Grafana alert thresholds should filter by `level=error` / `level=warn`
rather than regex-matching names.

---

## 6. Do's and don'ts

### Do

- Use `Telemetry.measure { }` for any block where you'd otherwise write
  a `val start = System.currentTimeMillis()` prelude.
- Emit `.failed` events even if the failure is recoverable (e.g. one
  failed retry in a larger span). Dashboards want the rate.
- Keep high-cardinality values (like `otid`) out of the tag/name axes.
  They belong in attributes.
- Add a unit test whenever you add an event that a future reader might
  depend on (example: `letta-mobile-gqz3` regression test asserts that
  `streamSubscriber.idle404` fires with `via=apiException`).

### Don't

- Don't emit inside tight loops without a rate limit. The ring buffer
  is 1000 events; you'll evict useful history.
- Don't rely on event ordering across threads. Events carry a
  `timestampMs`; use that, not insertion order.
- Don't stuff large payloads into attributes. Stringify to ≤200 chars.
- Don't emit PII. No message bodies, no auth headers, no email
  addresses, no tokens.
- Don't bypass Telemetry with raw `Log.x` calls in production code
  paths. If the signal is worth logging, it's worth measuring.

---

## 7. Adding a new event — checklist

When you add a new Telemetry event:

1. Pick the right call shape (`event` / `measure` / `error`).
2. Reuse an existing tag, or add a new one + update the inventory here.
3. Name it with a `verb.noun` pattern using the canonical phase
   suffixes.
4. Use canonical attribute keys.
5. If the event name is user-facing (in a dashboard or an alert rule),
   add a row to `docs/observability/coverage-matrix.md` when it exists.
6. If correctness depends on it (e.g. a regression test observes it),
   add a unit test that asserts it's emitted.

---

## 8. Retrofitting existing code

Known sites that don't yet follow these conventions:

- `Timeline.kt:129` / `Timeline.kt:137` / `Timeline.kt:176` / `Timeline.kt:186`
  use `Telemetry.event(..., level = Level.ERROR)` — should migrate to
  `Telemetry.error(...)`.
- `TimelineSyncLoop.kt:695` / `TimelineSyncLoop.kt:887` / `:898`
  hand-roll `errorClass` / `errorMessage` attributes — should migrate to
  `Telemetry.error(...)`. Note the existing attrs are still useful (e.g.
  `attempt`, `conversationId`); keep those as trailing varargs.
- `AdminChatViewModel.kt` emits under two different tags
  (`AdminChatVM` and `AdminChatViewModel`) — must normalize.

These are not blocking; they're tracked under `letta-mobile-2uzn`
children.
