# Android perf regression gate

This document describes the CI performance gate introduced for
`letta-mobile-o7ob.4.1`.

## Canonical benchmark device

The required perf gate runs on a **GitHub Actions API 33 emulator** with
the following configuration:

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

## What the gate measures

The CI workflow runs the benchmark methods that back `perf/baselines.json`:

- `StartupBenchmark#coldStartupCompilationPartial`
- `StartupBenchmark#warmStartup`
- `ScrollJankBenchmark#scrollChatListCompilationPartial`
- `ComposerTypingBenchmark#typeComposerCompilationPartial`

The parser reads AndroidX benchmark JSON output from:

`android-compose/macrobenchmark/build/outputs/connected_android_test_additional_output/`

## Tolerances

Tolerances are stored in `android-compose/perf/baselines.json`, not hard-coded
in the checker.

Current policy:

- `startup.cold.p95_ms`: `+10%`
- `startup.warm.p95_ms`: `+10%`
- `scroll.jank.pct`: `max(+15%, +1.0 absolute)`
- `composer.typing.jank.pct`: `max(+15%, +1.0 absolute)`

The absolute `+1.0` floor prevents tiny jank percentages from flaking on
measurement noise alone.

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
com.letta.mobile.macrobenchmark.StartupBenchmark#warmStartup,\
com.letta.mobile.macrobenchmark.ScrollJankBenchmark#scrollChatListCompilationPartial,\
com.letta.mobile.macrobenchmark.ComposerTypingBenchmark#typeComposerCompilationPartial

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

## Investigating flakes

If the job fails unexpectedly:

1. download the `android-perf-results-*` artifact
2. inspect the `*-benchmarkData.json` files and HTML reports
3. verify the failing metric is from the expected benchmark method
4. rerun the workflow once to rule out emulator startup noise
5. if the change is legitimate and repeatable, rebaseline deliberately

## Regression simulation

`android-compose/perf/test_check_baselines.py` includes a regression test
that verifies the parser exits non-zero when a benchmark value exceeds the
allowed ceiling. Keep that test passing so the CI gate itself does not
silently rot.

## Known quirks

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
