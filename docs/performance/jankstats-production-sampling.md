# Production JankStats Sampling

Lane C issue `letta-mobile-o7ob.3.4` extends the existing debug-only JankStats monitor into release builds with a low-rate sampling policy.

## What ships

- `DebugPerformanceMonitor` stays debug-only and continues to emit rich local breadcrumbs and StrictMode events.
- `ProductionJankStatsMonitor` runs only in `release` builds.
- Release sessions are sampled at `1%` by default via `@string/sentry_jankstats_sample_rate`.
- When a sampled session sees a janky frame above the 16ms frame budget, the monitor:
  - records a `Telemetry.event(tag="Jank", name="frame.sampled")`
  - attaches bounded jank measurements to the active Sentry span / transaction

## Sampling math

The monitor draws a single random value per process start and enables JankStats only when:

`draw < sampleRate`

At the default `sampleRate = 0.01`, that means roughly `1 out of 100` release sessions will collect JankStats data.

This keeps overhead low while still building up enough field evidence to spot regressions over time.

## Sentry measurements emitted

For each active span / transaction, the monitor maintains aggregated jank measurements:

- `jank_frame_count`
- `jank_frame_max_ms`
- `jank_frame_total_ms`
- `jank_frame_over_budget_ms`
- `jank_frame_last_ms`

To keep measurement cardinality bounded, only the first five janky frames on a given active span get per-frame detail keys:

- `jank_frame_1_ms`
- `jank_frame_2_ms`
- `jank_frame_3_ms`
- `jank_frame_4_ms`
- `jank_frame_5_ms`

If no active Sentry span exists when a janky frame arrives, the monitor still emits the local telemetry event but skips Sentry attachment.

## Increasing the rate for targeted investigations

For a temporary investigation branch, raise the value in:

- `android-compose/app/src/main/res/values/sentry.xml`

Example overrides:

- `0.05` → ~5% of release sessions
- `0.10` → ~10% of release sessions
- `1.0` → all release sessions

Do **not** leave elevated rates on `main` longer than necessary; higher rates increase both runtime overhead and Sentry ingest volume.

## Build-type policy

- `release`: production JankStats sampling enabled
- `debug`: production monitor disabled; debug monitor remains the source of truth
- `benchmark`: production monitor disabled to avoid polluting macrobenchmark runs with extra instrumentation overhead
