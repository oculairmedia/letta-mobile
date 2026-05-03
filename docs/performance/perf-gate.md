# Android perf regression gate

This document describes the CI performance gate introduced for
`letta-mobile-o7ob.4.1`.

## Canonical benchmark device

The required perf gate runs the `playBenchmark` app variant on a
**GitHub Actions API 33 emulator** with the following configuration:

- app distribution flavor: `play`
- app build type: `benchmark`
- API level: `33`
- target: `google_apis`
- profile: `pixel_6`
- architecture: `x86_64`

Why this device is the source of truth:

- it is hermetic and reproducible inside CI
- it avoids the Android 11 / API 30 `profileinstaller` handshake issue
  tracked in `letta-mobile-ce2c`
- it gives stable enough numbers for per-PR regression detection without
  depending on physical-device scheduling

Physical-device slow-floor runs are still valuable, but they belong in a
separate tier and do **not** define the baseline for this gate.

## What the gate measures today

The CI workflow runs the benchmark methods that back `perf/baselines.json`:

- `StartupBenchmark#coldStartupCompilationPartial`
- `StartupBenchmark#warmStartup`

Only **cold startup is gating** on the shared GitHub Actions API 33 emulator.
Warm startup is still collected and reported, but it is informational-only on
this runner because repeated healthy benchmark runs showed wide warm variance
that would make a blocking PR gate flaky rather than protective.

The repo still contains `ScrollJankBenchmark` and `ComposerTypingBenchmark`,
but they are **not gating in CI yet**. On a fresh API 33 emulator the app
currently launches into a nondeterministic empty-state surface, which means the
frame-timing benchmarks can produce zero `frameDurationCpuMs` samples even when
the emulator and benchmark harness are healthy. They move back into the gate
only after the app exposes a deterministic benchmark launch route. Track that
follow-up under `letta-mobile-4ccv`.

The parser reads AndroidX benchmark JSON output from:

`android-compose/macrobenchmark/build/outputs/connected_android_test_additional_output/`

Each CI run also writes compact summaries under `android-compose/build/perf-summary/`:

- `attempt-1/perf-summary.json` and `.md` for the first benchmark sample
- `attempt-2/perf-summary.json` and `.md` only when the cold-start retry path runs

The summaries include observed value, baseline, ceiling, percent delta, benchmark source, AndroidX metric name, source JSON path, and whether the metric is gating or informational.

## Tolerances

Tolerances are stored in `android-compose/perf/baselines.json`, not hard-coded
in the checker.

Current policy:

- `startup.cold.p95_ms`: `+20%`
- `startup.warm.p95_ms`: informational only (`gate: false`)

Warm startup keeps a wider envelope than cold startup because consecutive seed
and verify runs on the canonical API 33 emulator drifted by `+17.4%`
(`285.988 ms` -> `335.808 ms`) during gate bring-up. Cold startup also needed a
modest bump after the first PR-triggered verify run on the updated branch
measured bounded drift up to `1772.844 ms` against a `1512.749 ms` seed
(`+17.2%`), so the cold envelope is now `+20%` on this shared runner.

Warm startup is non-gating because later PR runs on the same healthy emulator
showed one-sided warm spikes (for example `434.843 ms` against a `285.988 ms`
seed) while cold startup simultaneously improved, which is a strong signal of
shared-runner noise rather than a trustworthy per-PR regression detector.

## Retry behavior

The workflow automatically performs **one bounded retry** when, and only when,
the first baseline check finds a regression limited to
`startup.cold.p95_ms`. The first check exits through a dedicated retryable
status, the job reruns the macrobenchmark suite on a fresh emulator action
invocation, clears the first attempt's AndroidX output, and then runs the
baseline check again without the retryable exit code.

Outcomes:

- attempt 1 passes: job passes, no retry
- attempt 1 has only `startup.cold.p95_ms` above ceiling and attempt 2 passes:
  job passes and both attempt summaries are uploaded
- attempt 2 also has `startup.cold.p95_ms` above ceiling: job fails and should
  be treated as a repeatable startup regression
- any non-retryable failure, malformed input, unseeded gating baseline, or
  benchmark task failure: job fails immediately

This retry does **not** raise the `+20%` ceiling and does not make warm startup
or future informational metrics gating. It exists only to separate a single
shared-emulator cold-start spike from a repeatable regression.

## Re-baselining

The first green CI run on the canonical emulator must seed the `null`
baselines before PR verification can pass.

### Local / manual rebaseline

If you can run the canonical emulator locally:

```bash
cd android-compose
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.letta.mobile.macrobenchmark.StartupBenchmark#coldStartupCompilationPartial,\
com.letta.mobile.macrobenchmark.StartupBenchmark#warmStartup

python3 perf/check_baselines.py \
  macrobenchmark/build/outputs/connected_android_test_additional_output \
  --rebaseline
```

Review the diff and commit it explicitly.

### CI-assisted rebaseline

The `Android Perf Gate` workflow supports `workflow_dispatch` with a
`rebaseline` input. That mode:

1. runs the canonical emulator benchmark suite
2. runs `perf/check_baselines.py --rebaseline`
3. uploads the updated `baselines.json` as an artifact

Use that artifact to update your branch, commit the new baseline values,
and rerun the PR without rebaseline mode.

### PR title escape hatch

If a PR needs a legitimate baseline bump, add `[perf:rebaseline]` to the
title temporarily. The workflow will run in rebaseline mode and upload a
candidate `baselines.json` artifact instead of enforcing the old values.

This tag is for generating the new baseline artifact, **not** for merging
without updating `baselines.json`.

## Required check wiring

After landing:

1. enable branch protection on `main`
2. add **Android Perf Gate / perf-gate** as a required status check

This repo-level setting needs admin access and is intentionally documented
here rather than hard-coded in the repo.

## Known quirks

- **Shell line continuations in workflow `script:` blocks** can be passed
  through literally and break Gradle invocation. Keep the benchmark command
  simple and shell-safe.
- **YAML folded scalars (`>-`) introduce spaces** when you later split or join
  multi-line values. The benchmark class filter is stored as a literal block
  (`|`) and collapsed in shell to avoid leading whitespace in class names.
- **AndroidX Benchmark rejects emulator measurements by default.** The
  `:macrobenchmark` module sets
  `testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"`
  in Gradle DSL, which is the AndroidX-recommended configuration and avoids the
  configuration-cache warning emitted by command-line injection.
- **Pushes during an in-flight perf run cancel it** because the workflow uses
  `concurrency.cancel-in-progress: true`. Time docs-only pushes accordingly,
  especially during the first seed run.
- **FrameTimingMetric benchmarks are intentionally non-gating in v1.** The
  canonical emulator currently lands on a generic project home empty state with
  no guaranteed scrollable list or editable composer, which can yield zero
  frame samples and false failures. Add a deterministic benchmark launch route
  before moving jank metrics into CI (`letta-mobile-4ccv`).

## Investigating flakes

If the job fails unexpectedly:

1. download the `android-perf-results-*` artifact
2. inspect `build/perf-summary/attempt-*/perf-summary.md` first for the compact
   observed/baseline/ceiling/delta/source/path view
3. inspect the underlying `*-benchmarkData.json` files and HTML reports for the
   failing attempt
4. verify the failing metric is from the expected benchmark method
5. if the workflow already used attempt 2 and cold startup failed again, treat
   it as repeatable rather than manually rerunning to hide the failure
6. if the change is legitimate and repeatable, rebaseline deliberately

## Regression simulation

`android-compose/perf/test_check_baselines.py` includes a regression test
that verifies the parser exits non-zero when a benchmark value exceeds the
allowed ceiling. Keep that test passing so the CI gate itself does not
silently rot.

### `reactivecircus/android-emulator-runner` `script:` line continuations

The emulator-runner action's `script:` input does **not** honor YAML
line-continuation backslashes the way standard `run:` steps do. The action
passes the block through an intermediate shell invocation that consumes the
`\` literally, which then shows up as a malformed Gradle task name (e.g.
`Task '\' not found in root project`).

Keep each `./gradlew` invocation on a single line inside `script:`, or
split it across multiple `- uses: ... with: { script: ... }` steps if
readability demands it. Normal multiline `run: |` blocks elsewhere in the
workflow are unaffected and can continue using `\` line continuations.

This bit us once during the initial perf-gate bring-up (2026-04-21); the
fix was commit `021e832`.
