# Perfetto trace capture

Use `scripts/perf/capture-trace.sh` to collect a reproducible 10-second system trace from a connected Android device.

## Quick start

```bash
scripts/perf/capture-trace.sh
```

Optional arguments:

```bash
scripts/perf/capture-trace.sh 15 ca.oculair.meridian
```

- First arg: duration in seconds (default `10`)
- Second arg: Android app id passed to `perfetto --app` (default `ca.oculair.meridian`)

## What the script records

The helper captures the categories that matter most for app startup and UI interaction triage in this repo:

- scheduler (`sched`, plus CPU frequency / idle)
- Android app services (`am`, `wm`)
- UI pipeline (`view`, `input`)
- binder driver handoffs (`binder_driver`)

Because the app already emits `androidx.tracing` sections through `Telemetry`, the resulting trace also shows repo-specific sections such as:

- `TimelineSync/*`
- `AdminChatVM/*`
- `StrictMode/violation`
- `JankStats/slowFrame`

## Open the trace

1. Run the script.
2. Open [ui.perfetto.dev](https://ui.perfetto.dev/).
3. Drag the generated `.perfetto-trace` file from `scripts/perf/traces/` into the UI.

## How to interpret the trace in this repo

- Use the CPU + scheduler tracks to confirm whether a slow interaction is main-thread bound or blocked by background work.
- Inspect `TimelineSync/*` and `AdminChatVM/*` slices to line up app work with platform scheduling.
- Check `StrictMode/violation` events for disk/network misuse during debug startup flows. WorkManager scheduling can be a noisy baseline on debug builds, so confirm whether a violation is app-owned before filing follow-up bugs.
- Check `JankStats/slowFrame` events for slow frames over 16ms and correlate them with the current screen.
