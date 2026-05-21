# Testing Flows with Turbine

`app.cash.turbine:turbine` is the standard harness for any test that exercises a `Flow`, `StateFlow`, or `SharedFlow` in this codebase. It replaces the older pattern of `toList()` + `delay()` + manual stability heuristics.

Reference bead: `letta-mobile-7abe`. Worked example: `Turbine - pure-delta merge yields concatenated assistant content` in `core/src/test/java/com/letta/mobile/data/timeline/TimelineSyncLoopTest.kt`.

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
        val timeline = awaitItem()                                    // <- 1s default timeout
        val assistant = timeline.events.firstOrNull {
            it is TimelineEvent.Confirmed && it.serverId == "reply"
        } as? TimelineEvent.Confirmed
        if (assistant != null) content = assistant.content
    }
    assertEquals(expected, content)
    cancelAndIgnoreRemainingEvents()
}
```

A broken merge surfaces as `Expected item but found no events` within 1s, pointing at the line that didn't emit. The heuristic is gone.

## Pattern

1. **Open with `.test { ... }`** on the Flow under test. Inside the block, the flow is being actively collected, so emissions are observable in order.
2. **`awaitItem()`** returns the next emission, or fails after a default 1s timeout. Use it like `expect <next>`.
3. **`skipItems(n)`** advances past `n` emissions without asserting — useful for the "initial state" prelude.
4. **`expectNoEvents()`** asserts that no further emissions arrive within a short window. Use for "the system has settled."
5. **`cancelAndIgnoreRemainingEvents()`** closes collection cleanly and discards any pending emissions. Always end with this (or `expectNoEvents()`) so the test scope doesn't leak.

## Conflation

`StateFlow` is conflated — when emissions arrive faster than the collector pulls them, intermediate values are dropped. Turbine respects that, so the worked example walks `awaitItem()` until a *terminal* condition is met, rather than asserting every intermediate state. Use `expectMostRecentItem()` when you only care about the last value.

For non-conflated `Flow` / `SharedFlow`, every emission is observed in order.

## When NOT to use Turbine

- The code path doesn't expose a Flow / StateFlow / SharedFlow. Test the function directly.
- You're asserting on a single suspend-fun result. Use plain `assertEquals(expected, fn())`.
- The Flow is consumed by Compose and you're testing the UI. Use Compose UI tests (`createComposeRule`) and let Compose own the collection.

## Adoption checklist

When converting an existing test that polls a StateFlow:

- [ ] Add `import app.cash.turbine.test`. The dep is already in `core`, `app`, and `feature-editagent` test source sets.
- [ ] Replace `delay()` / stability heuristics with `flow.test { ... awaitItem() ... }`.
- [ ] Make sure the test's coroutine scope is cancelled at the end (Turbine cancels collection, but the scope you launched the producer in still needs `.coroutineContext.job.cancel()`).
- [ ] If conflation matters, prefer `expectMostRecentItem()` over a long `awaitItem()` chain.
