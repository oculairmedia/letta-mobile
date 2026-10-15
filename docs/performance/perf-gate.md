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

Both **cold and warm startup are gating** on the shared GitHub Actions API 33
emulator. Each metric must provide at least ten samples and remain within its
configured tolerance.

The repo still contains `ScrollJankBenchmark` and `ComposerTypingBenchmark`,
but they are **not gating in CI yet**. On a fresh API 33 emulator the app
currently launches into a nondeterministic empty-state surface, which means the
frame-timing benchmarks can produce zero `frameDurationCpuMs` samples even when
the emulator and benchmark harness are healthy. They move back into the gate
only after the app exposes a deterministic benchmark launch route. Track that
follow-up under `letta-mobile-4ccv`.

The parser reads AndroidX benchmark JSON output from:

`android-compose/macrobenchmark/build/outputs/connected_android_test_additional_output/`

Each CI run also writes a compact summary, `android-compose/build/perf-summary/perf-summary.{json,md}`,
publishes it in the job summary, and uploads it on its own as the small
`android-perf-summary-<run>` artifact (the control step below reads those).

The summaries include observed value, baseline, ceiling, percent delta, benchmark source, AndroidX metric name, source JSON path, and whether the metric is gating or informational.

## Tolerances

Tolerances are stored in `android-compose/perf/baselines.json`, not hard-coded
in the checker.

Current policy:

- `startup.cold.p95_ms`: `+50%` (ceiling `2269.1 ms`; letta-mobile-3gpwo: runner
  noise alone produced cold p95 of `1955-2091 ms` against the former `1815.3 ms` ceiling)
- `startup.warm.p95_ms`: `+20%`

Warm startup was refreshed to a `405.889 ms` baseline after consecutive
canonical PR runs measured `353.541 ms` and `405.889 ms` while cold startup
remained below its baseline. That evidence showed the previous `285.988 ms`
seed no longer represented the shared runner. It was rebaselined again to
`542.299 ms` (letta-mobile-gvswz) when #1886 stopped resolving the start
destination from placeholder settings: the benchmark's unconfigured install
used to draw a throwaway Conversations frame first, and TTID stopped there; it
now draws the real first screen (the settings form) on its first frame, so TTID
includes rendering it. Cold startup retains its
`1512.749 ms` baseline after healthy runs measured bounded drift up to
`1772.844 ms` (`+17.2%`).

Warm startup uses a `+20%` envelope, cold startup a `+50%` envelope, and both gate by default. Their minimum
sample counts prevent absent or undersampled output from silently passing.

## One measurement, then the control (no automatic retry)

Until letta-mobile-by4vm.32 the workflow reran the whole suite when exactly one
startup metric breached, and passed if the second attempt did. That is retrying
until green, which the project's deflake rule forbids: it hid real regressions
behind a coin flip and doubled the emulator time on every noisy run.

Now:

1. The suite runs **once**. `perf/check_baselines.py` exits 1 on any breach
   (2 on missing, malformed, undersampled or unseeded input). There is no
   retryable exit code any more.
2. On a breach, the **control** step lists the last 5 completed main pushes of
   this workflow (success or failure; cancelled runs are skipped), downloads
   their `android-perf-summary-*` artifacts (older runs: `android-perf-results-*`),
   and renders `perf/perf_control.py`'s table into the job summary: this run's
   cold/warm p95 next to main's, each flagged when over today's ceiling, plus
   a one-line reading per breached metric.
3. The job fails. A **human** decides:
   - the control shows main at or over the same ceiling: the runner moved.
     Rerun the job once and say so on the PR, quoting the control line;
   - main sits comfortably under it: the change is the suspect. Profile it
     (see the playbook) before any rerun. If the slowdown is real and
     accepted, rebaseline deliberately (below), never by widening a ceiling
     to make one PR pass.

Noise is still real on the shared emulator (2026-10-14: #1935, which touched
no startup code, breached cold 2362/2269 and warm 745/651 while neighbouring
PRs and main passed), so a rerun with control evidence is legitimate. What is
gone is the silent, automatic one.

## Baseline Profile freshness

The gate measures `playBenchmark`, which ships
`app/src/playRelease/generated/baselineProfiles/baseline-prof.txt`. ART
silently ignores profile entries whose classes no longer exist, so a stale
profile shows up only as startup drift. Each perf-gate run executes
`scripts/ci/baseline-profile-staleness.py --max-unresolved-pct 25`, which
resolves the profile's first-party (`com/letta/...`) classes against the
Kotlin/Java sources (Hilt/Dagger/Room generated classes resolve through the
class they are generated from). Over 25% unresolved it emits a workflow
warning and a job-summary section; it never fails this required job.

On 2026-10-14 the profile (captured 2026-06-25) had 145 of 283 first-party
classes unresolved (51%): the bot/, client-mode, REST `data/api` and old
navigation routes it was recorded against are gone. Regenerating needs a
device or emulator run of the `:baselineprofile` generators
(`baselineprofile/src/main/java/.../BaselineProfileGenerator.kt`, output saved
under `app/src/playRelease/generated/`); that is tracked separately, not done
in CI. Run the check locally with
`python3 scripts/ci/baseline-profile-staleness.py --list`.

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
2. inspect `build/perf-summary/perf-summary.md` first for the compact
   observed/baseline/ceiling/delta/source/path view
3. inspect the underlying `*-benchmarkData.json` files and HTML reports for the
   failing run
4. verify the failing metric is from the expected benchmark method
5. read the control table in the job summary before deciding on a rerun (see
   "One measurement, then the control"); never rerun more than once to get green
6. if the change is legitimate and repeatable, rebaseline deliberately

## Regression simulation

`android-compose/perf/test_check_baselines.py` includes a regression test
that verifies the parser exits non-zero when a benchmark value exceeds the
allowed ceiling; `test_perf_control.py` covers the control table. The
perf-gate job runs both (`python3 -m unittest discover -s perf -p 'test_*.py'`)
on every run, so the gate itself cannot silently rot.

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
