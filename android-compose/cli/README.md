# letta-mobile-cli

Headless CLI that drives the same `:core` streaming code paths the Android app uses (`SseParser`, the `TimelineSyncLoop` merge heuristic), so we can reproduce, debug, and regression-test streaming bugs without putting eyes on a device.

Filed for: `letta-mobile-6p4o` — SSE streaming chunks render as garbled text.

## Build / run

The CLI is implemented as an Android library (so it has access to `:core`'s Ktor / serialization / model classes). It's launched through a JUnit test entrypoint via a custom Gradle task:

```
./gradlew :cli:run -PcliArgs="<command> [options]"
```

No args = print help.

## Commands

### `stream` — send a message and watch every wire frame + merge

```
./gradlew :cli:run -PcliArgs='stream -m "your prompt here"'
```

Required env (or flags):

| env / flag                           | what                                              |
| ------------------------------------ | ------------------------------------------------- |
| `LETTA_BASE_URL` / `--base-url`      | Letta server, default `https://letta.oculair.ca`  |
| `LETTA_TOKEN` / `--token`            | Bearer token                                      |
| `LETTA_CONVERSATION_ID` / `--conversation` | Conversation to send into                   |

For each SSE frame received, prints:
- frame index, message type, server id, merge branch (INIT / EQUAL / CUMULATIVE / STALE / APPEND / GARBLE-RISK)
- the OLD + NEW + OUT text (with whitespace/control chars made visible)

After the stream closes, prints a final per-message summary so you can verify the assembled text matches what should appear on screen.

## Bug-hunt workflow

1. Run `./gradlew :cli:run -PcliArgs='stream -m "<the prompt that garbles in the app>"'`
2. Look at the trace:
   - **Wire is clean, merge is clean** → bug is downstream of merge (display layer, fuzzyCollapse, ServerEvent race in `TimelineSyncLoop`)
   - **Wire is dirty** → bug is server / parser / transport
   - **Wire is clean, merge is dirty** → bug is in the merge heuristic (lines 1132–1138 of `TimelineSyncLoop.kt`)
3. Save the trace, screenshot the device showing garbled text, byte-compare.
4. Convert into a `TimelineSyncLoopStreamingTest` regression fixture once we have a confirmed repro.

## Why this design (and its caveats)

- `:cli` is an Android library because `:core` is an Android library and pulling in `:core`'s real code from a pure-JVM module would require either fighting Gradle's variant attributes or duplicating code. The library + test-classpath trick gets us there in ~1 file.
- The CLI lives in `src/test/` because that's the source set that gets the full Android-stub classpath when running on the JVM (provided by the unit-test setup).
- The trade-off: we abuse JUnit-5 as our entrypoint. Output is wrapped in `CliRunnerTest > runCli() STANDARD_OUT`. Acceptable for the day-1 use case (debugging this bug). If we need pretty output later, we can swap the entrypoint to a real `application` plugin module that depends on a slimmer extracted streaming-only library.
- The merge tracer is **a copy** of the `TimelineSyncLoop` heuristic, not a call into it. If the heuristic changes, update `MergeTracer.kt` to match. Better long-term: hoist the heuristic into a pure function both call. Tracked as a follow-up.
