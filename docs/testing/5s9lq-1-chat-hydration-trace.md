# 5s9lq.1 Chat Hydration Trace Baseline

## Scope

This is a measurement-only change. `ChatHydrationTrace` is disabled by default and emits count-only, redacted events when enabled. It does not change hydration, reducer, publication, layout, or scroll behavior.

Every event carries `generation`, `agentId`, `conversationId`, `backendId`, `runtimeId`, monotonic `elapsedMs`, publication/layout/scroll/thinking counters, `commitReason`, `missingOptionalSources`, and `staleCount`. No message body, tool-call arguments, A2UI payload, or exception text enters this trace.

## Pixel Baseline

Collected on 2026-08-15 without installing, launching, clearing data, setting properties, or otherwise mutating the target Pixel.

```text
$ adb connect 100.79.179.71:5555
already connected to 100.79.179.71:5555

$ adb -s 100.79.179.71:5555 get-state
device

$ adb -s 100.79.179.71:5555 shell getprop ro.product.model
Pixel 9 Pro

$ adb -s 100.79.179.71:5555 shell getprop log.tag.LettaChatHydrationTrace
<empty>

$ adb -s 100.79.179.71:5555 logcat -d -v epoch -s Telemetry/ChatHydration:D '*:S'
<no matching events>
```

The device is reachable and has `com.letta.mobile` installed, but the new production-safe trace gate is off. Enabling the gate or opening a chat would mutate device/runtime state, so no live reproduction was run under the no-mutation constraint.

## Deterministic Fixture Trace

The focused unit fixture enables only the trace gate and drives the source/publication/layout/scroll sequence. Representative output is deterministic except for non-negative elapsed milliseconds:

```text
ChatHydration/hydration.started generation=1 agentId=agent-fixture conversationId=conv-fixture backendId=remote-letta:fixture runtimeId=remote-letta:fixture elapsedMs=0 publicationCount=0 layoutPassCount=0 scrollCorrectionCount=0 thinkingTransitionCount=0 staleCount=0 commitReason=conversation_open missingOptionalSources=none
ChatHydration/source_ready generation=1 ... source=timeline sourceLatencyMs=0 sourceCount=42
ChatHydration/presentation_published generation=1 ... publicationCount=1 messageCount=42 commitReason=Full missingOptionalSources=a2ui
ChatHydration/first_layout generation=1 ... layoutPassCount=1 renderItemCount=42
ChatHydration/scroll_initialized generation=1 ... scrollCorrectionCount=1 scrollCorrection=conversation_reset
ChatHydration/settled generation=1 ... publicationCount=1 layoutPassCount=1 scrollCorrectionCount=1 thinkingTransitionCount=0 commitReason=initial_frame_settled
```

The stale-generation safety fixture starts generation 1 and generation 2 for the same identity, then publishes generation 1. It records `presentation_published generation=1 isStale=true staleCount=1`, making superseded work distinguishable without recording rendered content.

## Reproduction Commands

Use an already-built debug app only; do not install or clear the Pixel:

```bash
adb -s 100.79.179.71:5555 logcat -c
adb -s 100.79.179.71:5555 logcat -v epoch -s Telemetry/ChatHydration:D '*:S'
# Enable Telemetry.chatHydrationTraceEnabled only in a debug reproduction build,
# then manually exercise cold/warm cache, conversation/backend switches, long history,
# stale/active subagent, tool approval, and delayed A2UI states.
```

Local deterministic verification:

```bash
ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk \
  android-compose/gradlew -p android-compose \
  :feature-chat:testDebugUnitTest \
  --tests com.letta.mobile.feature.chat.coordination.ChatHydrationTraceTest --no-daemon
```

## GO / NO-GO

**NO-GO for accumulator architecture.** The only available baseline is the non-mutating Pixel confirmation plus deterministic instrumentation fixture; it does not establish independent intermediate domain publications. The trace records publication count, source readiness, first layout, initial scroll corrections, and activity transitions specifically so a later enabled-device matrix can prove or disprove that condition. Do not implement `5s9lq.2+` based on this result.
