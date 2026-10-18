# POC Validation Results

**Status:** Historical empirical report (2026-04-17); POC coverage is not current App Server validation.

## Code audit — 2026-10-18

The retained [poc/chat-cli](../../poc/chat-cli/) build is a separate experiment. Production timeline semantics now live in [sharedLogic/data/timeline](../../android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/timeline/) and sends in shared `ChatSendCoordinator` / `AppServerTurnEngine`. The report mixes live, fake and design-only passes: its own out-of-scope section excludes live drop recovery, long streams and historical scroll pagination. Therefore “all eight pass” below means the recorded mixture, not eight live end-to-end scenarios or proof of today's concurrency/retry semantics. Use [current boundary suites](chat-boundary-regression-harness.md). Preserve the old server observations as dated evidence.

## Original record

**Date:** 2026-04-17
**Bead:** letta-mobile-iuq8
**POC location:** `/opt/stacks/letta-mobile/poc/chat-cli/`

## Summary

| # | Scenario | Status | Notes |
|---|----------|--------|-------|
| 1 | Basic send/receive | ✅ PASS | User message appears immediately, stream appends assistant, reconcile confirms user |
| 2 | Rapid consecutive (sequential) sends | ✅ PASS | 3 sequential sends, all processed, perfect ordering |
| 3 | Truly concurrent sends | ✅ PASS | 3 parallel `launch` sends, all processed, no race/no duplicates |
| 4 | Send during active stream | ✅ PASS (by design) | Queue serializes automatically — second send waits for first |
| 5 | Identical content sent twice | ✅ PASS (unit tested) | Two distinct Local events with different otids |
| 6 | Pagination backfill ordering | ✅ PASS (unit tested) | `insertOrdered` places at correct position |
| 7 | Timeline invariants under mutation | ✅ PASS | 16/16 unit tests |
| 8 | otid echo end-to-end | ✅ PASS | User otid round-trips: send → GET → reconcile match |

## Key discoveries

### 1. Server only accepts ONE request per conversation at a time

**Finding:** Attempting truly parallel POSTs to `/v1/conversations/{id}/messages` returns:
```
409 Conflict
{"detail": "CONFLICT: Cannot send a new message: Another request is currently being processed for this conversation."}
```

**Implication:** Client MUST serialize send requests per-conversation.

**Implementation:** `SyncLoop` uses an unbounded `Channel<PendingSend>` with a single consumer. All sends append Local events atomically (under mutex) AND enqueue atomically. The queue worker processes one at a time.

**This was NOT in the original plan** — discovered empirically in POC testing. Important for mobile migration.

### 2. otid echo is reliable for user messages

**Confirmed:** Our client-generated otid survives round-trip:
- Sent: `client-d2a2ebc1-5ddf-4e2e-...`
- Stored server-side: `client-d2a2ebc1-5ddf-4e2e-...` (exact match)
- Retrievable via GET with same otid

This is the foundation for Matrix-style txn_id matching — it works.

### 3. Stream does NOT echo user messages

**Confirmed:** The SSE stream from POST emits only:
- `ping`
- `assistant_message` / `reasoning_message` / `tool_*` (server-generated)
- `stop_reason`
- `error_message` (cleanup_error is benign)

The user message is persisted but never streamed back. Our reconciliation strategy (fetch after stream completes) handles this correctly.

### 4. Intra-run ordering via otid suffix

Server-generated otids follow a pattern within a step:
- Reasoning: `{prefix}80` or `{prefix}00`
- Assistant: `{prefix}81` or `{prefix}01`

Two-character suffix defines ordering. **We did not need this in the POC** because the stream delivers events in correct order (our `nextLocalPosition()` just increments). But this pattern is useful as a tiebreaker for any out-of-order scenarios.

### 5. otid is NOT enforced unique server-side

Duplicate otids are silently accepted as separate messages. Client must ensure uniqueness, which UUID generation handles automatically.

## Architecture validation

### Claim: "Single source of truth" eliminates UI bugs

✅ **Validated in POC.** The CLI reads only `sync.state.value` — no separate local state. Every mutation goes through `writeMutex`. Zero cases observed of:
- Messages disappearing
- Duplicates appearing
- Ordering inconsistency
- Pending state getting stuck

### Claim: "otid-based matching" eliminates content-hash fragility

✅ **Validated in POC.** Reconciliation works by exact otid match. Never touched content comparison. Works for identical-content messages (Scenario 5 unit test).

### Claim: "Single sync loop" eliminates race conditions

✅ **Validated in POC.** All state mutations happen in one of:
- `send()` — mutex-protected append + enqueue
- `processSendQueue()` → `streamAndReconcile()` — mutex-protected per-event
- `reconcileAfterSend()` — mutex-protected whole-batch

No other code paths write to state. Zero observed races.

## Open findings for mobile migration

### Must handle in mobile implementation

1. **Send queue is required.** Original plan didn't specify this; POC proved necessary.

2. **Reconcile uses `listMessages` with `order=desc` then reverses.** The server's `desc` is useful for getting recent messages efficiently; we reverse to get ascending.

3. **Position scheme:** Using `Double` with integer values (1.0, 2.0, ...). If we need to insert between existing events (backfill), we can use fractional positions (1.5, 1.75, etc). For now, append-only works.

4. **Ignore `cleanup_error` with "uuid" detail.** Server bug, harmless.

### Did not test (out of scope for POC, defer to Phase 1 tests)

- Long-running streams (30s+)
- Network drop mid-stream with reconnection
- Pagination of historical messages on scroll-up
- Tool call / tool return message ordering

These are covered by existing unit tests in POC (via fake API) or will be added during Phase 1 extraction.

## Code size (complexity validation)

| File | Lines | Purpose |
|------|-------|---------|
| `Timeline.kt` | 128 | Data model + invariants |
| `LettaApi.kt` | 180 | Minimal HTTP client |
| `SyncLoop.kt` | 195 | The ONE sync handler |
| `Main.kt` | 135 | CLI REPL |
| **Total (non-test)** | **~640** | **Well under 1000-line target** |
| `TimelineTest.kt` | 180 | 16 unit tests |

Compare to current mobile: `MessageRepository.kt` alone is 750+ lines with multiple overlapping state stores and merge logic.

## Decision

✅ **POC validated. Proceed with migration plan Phase 1 (extract sync to core module).**

All 8 scenarios pass. Architecture holds up under:
- Sequential sends
- Concurrent sends
- Identical content
- Timeline mutations

Two refinements added based on POC discoveries:
- **Send queue** (serialize per-conversation due to 409 Conflict)
- **Atomic append+enqueue** under single lock

These should be incorporated into Phase 1 design.
