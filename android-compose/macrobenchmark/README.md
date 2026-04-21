# :macrobenchmark

Macrobenchmark (end-to-end, on-device perf tests) module for
letta-mobile. Related issues:

- `letta-mobile-o7ob.2.1` — Baseline Profile generation
- `letta-mobile-o7ob.4.1` — CI perf regression gate

## Build variant

The `:app` module exposes a dedicated `benchmark` build type:

| property | value |
| --- | --- |
| `initWith` | `release` (minified, shrunk) |
| `isDebuggable` | `false` |
| `isProfileable` | `true` |
| `signingConfig` | `debug` (no release keystore needed) |
| `applicationIdSuffix` | `.benchmark` |

We keep a distinct `applicationId` so benchmark installs do not collide
with the dev build on the same device.

## Running benchmarks

Connect a device and run:

```bash
# All benchmarks
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest

# Startup only
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=\
com.letta.mobile.macrobenchmark.StartupBenchmark

# Scroll jank only
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=\
com.letta.mobile.macrobenchmark.ScrollJankBenchmark
```

Results land under
`macrobenchmark/build/outputs/connected_android_test_additional_output/`
as JSON + traces alongside the Gradle HTML reports.

## Current benchmarks

### `StartupBenchmark`

`StartupTimingMetric` for cold / warm / hot launches across
`CompilationMode.None()` and `CompilationMode.Partial()` (which mirrors
a real Play Store install with a baseline profile applied).

### `ScrollJankBenchmark`

`FrameTimingMetric` for scrolling the top-level list using UiAutomator.
Reports p50/p90/p95/p99 per-frame render times plus jank counts.

## CI gate

Thresholds live in `perf/baselines.json` at the repo root. The CI job
parses the `*-benchmarkData.json` output and fails the build if any
measured metric exceeds `baseline + tolerance`. The canonical CI device
is the API 33 `pixel_6` emulator. Re-baselining is an explicit commit.

Today the CI gate enforces **startup metrics only**. The scroll/composer
`FrameTimingMetric` benchmarks stay in-tree for local investigation, but they
are non-gating until the app exposes a deterministic benchmark launch surface
on the canonical emulator.

See `letta-mobile-o7ob.4.1` for the CI pipeline wiring.
