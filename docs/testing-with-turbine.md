# Testing Flows with Turbine

`app.cash.turbine:turbine` is the standard harness for any test that exercises a `Flow`, `StateFlow`, or `SharedFlow` in this codebase. It replaces the older pattern of `toList()` + `delay()` + manual stability heuristics.

Reference bead: `letta-mobile-7abe`. Current examples live under `android-compose/sharedLogic/src/commonTest/` and `android-compose/core/android-data/src/test/`. The former `TimelineSyncLoopTest.kt` worked-example file has been split; locate a current collector with `rg -n "awaitItem|expectMostRecentItem" android-compose/sharedLogic/src/commonTest android-compose/core/android-data/src/test`.

## Why

Polling-loop tests look fine on green but fail in informative-only ways when they break:

```kotlin
// Old pattern
withTimeout(5_000) {
    while (sync.state.value.findByOtid("reply-otid") == null) delay(10)
    var stable = 0; var lastLen = -1
    while (stable < 5) {
        val len = sync.state.value.events.firstOrNull { ... }?.content?.length ?: -1
        if (len == lastLen && len >= 0) stable++ else { stable = 0; lastLen = len }
        delay(10)
    }
}
```

This:
- waits up to 5s before failing even when the bug is "no emission ever"
- decides "stable" by a heuristic (5 ticks with unchanged length) — a merge that produces the wrong-but-stable content passes
- has nothing to do with the assertion it eventually makes, just the timing of when to make it

The Turbine equivalent says what we actually mean:

```kotlin
sync.state.test {
    assertEquals(0, awaitItem().events.size)
    sync.send("hello")

    var content: String? = null
    while (content != expected) {
        val timeline = awaitItem()                                    // <- configured timeout
        val assistant = timeline.events.firstOrNull {
            it is TimelineEvent.Confirmed && it.serverId == "reply"
        } as? TimelineEvent.Confirmed
        if (assistant != null) content = assistant.content
    }
    assertEquals(expected, content)
    cancelAndIgnoreRemainingEvents()
}
```

A broken merge surfaces as `Expected item but found no events` when its configured timeout expires, pointing at the line that didn't emit. The heuristic is gone.

## Pattern

1. **Open with `.test { ... }`** on the Flow under test. Inside the block, the flow is being actively collected, so emissions are observable in order.
2. **`awaitItem()`** returns the next emission, or fails after the configured Turbine timeout (3 seconds upstream by default). Use it like `expect <next>`.
3. **`skipItems(n)`** advances past `n` emissions without asserting — useful for the "initial state" prelude.
4. **`expectNoEvents()`** checks immediately that its event queue is empty; it does not wait for a quiet window or prove the producer has settled. Advance the test scheduler or await a meaningful terminal state first.
5. **`cancelAndIgnoreRemainingEvents()`** closes collection cleanly and discards any pending emissions. Use this when remaining events are intentionally irrelevant. `expectNoEvents()` does not cancel the collector; `.test` owns collector cleanup on block exit.

## Conflation

`StateFlow` is conflated — when emissions arrive faster than the collector pulls them, intermediate values are dropped. Turbine respects that, so the worked example walks `awaitItem()` until a *terminal* condition is met, rather than asserting every intermediate state. Use `expectMostRecentItem()` when you only care about the last value.

For other flows, delivery still depends on their buffering, overflow, replay and subscription policies; Turbine cannot recover emissions dropped before collection.

## When NOT to use Turbine

- The code path doesn't expose a Flow / StateFlow / SharedFlow. Test the function directly.
- You're asserting on a single suspend-fun result. Use plain `assertEquals(expected, fn())`.
- The Flow is consumed by Compose and you're testing the UI. Use Compose UI tests (`createComposeRule`) and let Compose own the collection.

## Adoption checklist

When converting an existing test that polls a StateFlow:

- [ ] Add `import app.cash.turbine.test`. The dep is already in the `core` and `app` test source sets.
- [ ] Replace `delay()` / stability heuristics with `flow.test { ... awaitItem() ... }`.
- [ ] Make sure the test's coroutine scope is cancelled at the end (Turbine cancels collection, but the scope you launched the producer in still needs `.coroutineContext.job.cancel()`).
- [ ] If conflation matters, prefer `expectMostRecentItem()` over a long `awaitItem()` chain.

Library semantics: [Turbine documentation](https://github.com/cashapp/turbine) (timeout, test cleanup, and `expectNoEvents`).
