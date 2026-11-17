# Android perf regression gate

This document describes the CI performance gate introduced for
`letta-mobile-o7ob.4.1`.

## When it runs (nightly + main, not a PR gate)

The emulator macrobenchmark takes ~30 minutes, and as a required PR check it
throttled merge throughput while its noise band sat on the ceiling. Since
2026-10-15 `.github/workflows/android-perf.yml` runs it:

- **nightly** (`schedule`, 07:00 UTC) against `main`;
- on every **push to `main`** that touches `android-compose/**` or the workflow;
- on demand via **`workflow_dispatch`** (optionally `rebaseline: true`);
- on a **PR only** when the PR edits the gate itself
  (`.github/workflows/android-perf.yml`, `android-compose/perf/**`,
  `android-compose/macrobenchmark/**`) or has `[perf:rebaseline]` in its title.

`perf-gate` is **not** a required status check. Every PR still runs the cheap
`perf-scripts` job (perf script unit tests + Baseline Profile freshness, about
a minute, no emulator).

A failed nightly or main run fails the workflow and the `report perf
regression` job opens a GitHub issue titled **`perf-gate regression`**, or
comments on the open one (de-duplicated by that exact title). Triage it with
the control table below and the playbook; close the issue once main is green.
A regression is therefore caught after merge, within a day, and bisected over
the main commits since the last green nightly.

## Canonical benchmark device

The perf gate runs the `playBenchmark` app variant on a
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
- it gives stable enough numbers for nightly regression detection without
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
     Rerun the job once and say so on the `perf-gate regression` issue (or
     the PR, for an opted-in PR run), quoting the control line;
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
warning and a job-summary section; it never fails the job. This check runs
in the `perf-scripts` job, so it reports on every PR as well.

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
baselines before the gate can pass.

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

`perf-gate` is deliberately **not** in branch protection: it runs nightly and
on main pushes (see "When it runs"). The required set is `test`,
`build-apk-pass`, `shared-multiplatform` and `detekt` (`scripts/bootstrap-branch-protection.sh`).
`perf-scripts` is not required either; it is a cheap, honest self-test.

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
perf-scripts job runs both (`python3 -m unittest discover -s perf -p 'test_*.py'`)
on every PR, main push and nightly run, so the gate itself cannot silently rot.

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

## Seeded physical-device frame investigation

The frame benchmarks are separate from the startup gate. On the Pixel 2 XL,
charge to at least 50%, record battery temperature and level, and use only its
explicit adb serial (`711KPAE0914240`). Use the same seeded agent and
conversation, compilation mode, benchmark sources and iteration count for both
builds; alternate installations to control drift. A populated timeline and a
working turn are required: an empty surface is not a measurement.

Build `playBenchmark` with `-Pkotzilla=false`, install it alongside the normal
app, then configure its `ca.oculair.meridian.benchmark` package with the
benchmark-only automation launch extra. For the mini, obtain the Iroh ticket
from `/opt/letta-test-server/current-iroh-ticket` on `graphiti`; construct a
base64 JSON payload with `serverUrl` set to `iroh://<ticket>`, a nonempty
`accessToken`, `configId` set to `automation-auth`, and `mode` set to
`SELF_HOSTED`. Launch `com.letta.mobile.MainActivity` with the extra
`com.letta.mobile.extra.AUTOMATION_CONFIG_PAYLOAD_BASE64`. Use only a
dedicated test host and test credentials: AndroidX logs the launch intent,
including its encoded payload, to device logcat. Do not commit tickets or
tokens or share unredacted device logs. Confirm the conversation opens and
contains messages before recording results.

If the ID launch route lands on Home, the benchmark falls back to selecting a
recent conversation by visible title and zero-based index. Supply
`conversationTitle`, `conversationIndex` and a distinctive visible
`conversationMarker` from that timeline. The marker is checked after navigation;
verify the chosen conversation and its content manually before measuring.

Pass the encoded payload as `configPayload`, along with `agentId` and
`conversationId`, as Android instrumentation arguments to
`ScrollJankBenchmark` and `ComposerTypingBenchmark`. The latter includes a
settlement scenario that sends a real message and waits for a turn to become
finish, and verifies a unique assistant response, so use a dedicated test
conversation. The tests fail if the
expected controls or turn state are absent. Preserve the raw benchmark JSON and
traces for each run; report the number of frames and describe any unavailable
metrics. The seeded-host dependency is tracked by `letta-mobile-4ccv` and
these scenarios remain non-gating until CI has a reproducible host.

For example, from `android-compose` (set the two IDs to a seeded conversation):

```bash
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_SERIAL=711KPAE0914240
/usr/local/bin/gradle-slot ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pkotzilla=false \
  -Pandroid.testInstrumentationRunnerArguments.configPayload="$CONFIG_PAYLOAD" \
  -Pandroid.testInstrumentationRunnerArguments.agentId="$AGENT_ID" \
  -Pandroid.testInstrumentationRunnerArguments.conversationId="$CONVERSATION_ID" \
  -Pandroid.testInstrumentationRunnerArguments.conversationTitle="$CONVERSATION_TITLE" \
  -Pandroid.testInstrumentationRunnerArguments.conversationIndex="$CONVERSATION_INDEX" \
  -Pandroid.testInstrumentationRunnerArguments.conversationMarker="$CONVERSATION_MARKER" \
  -Pandroid.testInstrumentationRunnerArguments.class='com.letta.mobile.macrobenchmark.ScrollJankBenchmark#scrollChatListCompilationNone,com.letta.mobile.macrobenchmark.ComposerTypingBenchmark#typeComposerCompilationNone,com.letta.mobile.macrobenchmark.ComposerTypingBenchmark#settleTurnCompilationNone'
```

Android 11 does not provide AndroidX's `frameOverrunMs` (it requires API 31),
so an overrun-based jank percentage is unavailable on the Pixel 2 XL. Report
`frameDurationCpuMs` separately and do not substitute a CPU-time threshold as
if it were the overrun metric.

### Mini working-turn fixture

On the mini test server, run `scripts/test-sites/setup-mini-bench-agent.sh`
from a checkout to start the local model and external-tool responder; set
`BENCH_SECONDS` to an integer from 7 to 110 (default 40). The script prints
its dedicated agent and conversation IDs. `--teardown` stops its processes.
Only `mini-bench` uses `ollama/bench-model`; other mini agents retain their
model. Re-run setup after a server restart. The setup requires port 11434 free.

Use the seeded arguments above with
`ComposerTypingBenchmark#workingTurnCompilationNone`, the default 40-second
hold, and a populated fixture conversation. Preserve the raw JSON and the
fixture's `model.log` and `worker.log` under
`/opt/letta-test-server/bench-fixture/`, along with the device's
`WorkingTurnBenchmark` window timestamps. A run is valid only when Stop was
visible at both window endpoints, the matching tool start and end enclose the
window by at least three seconds on each side, and the model log shows no
assistant content in the tool-call response. Compare clocks before the run;
clock skew or ambiguous log correlation invalidates the run. The tool call in
flight with no assistant tokens implies WORKING through
`RunPhaseReducer.kt:36` and `AppServerRuntimeEventMapper.kt:194`.

The earlier Bash approach was blocked by letta-code's foreground-sleep guard,
not by a model refusal: on the mini's pinned 0.34.39 installation,
`letta.js:218232` contains the guard message, which appeared as the Bash tool
result in the original probe conversation before the assistant reported it.

### Core-loop harness prerequisites (letta-mobile-vu08m.1)

The mini fixture accepts `[core-loop:S1]`, `[core-loop:S3]` and
`[core-loop:S4]` in the latest user message to stream the fixed three-paragraph
reply at 60 ms per word. `[core-loop:S2]` preserves `bench_wait` followed by
`DONE`. The streaming fixture and `mini-bench/analyze-settle.py` reuse the
fixture/analysis from `fix/settle-reply-pop` (#2515). They do not change app code.

`python3 scripts/test-sites/mini-bench/score-core-loop.py evidence.json
--output results` scores normalized observations and exits nonzero for absent
or failing evidence. Each input yields a JSON verdict and a printed table.
`test_score_core_loop.py` describes the observation schema and independently
mutates it to check fail-closed behavior. Thresholds are 3 s stored-copy latency,
5 s Stop clearance, template correlation >= 0.9 and luminance dip <= 10%.
The visible assistant identity window must be sampled at least every 100 ms,
cover terminal through five seconds afterwards, and retain all observed rows;
mount/dispose observations must cover every row. Times require a shared,
aligned monotonic clock; model completion is not a stored-display observation.
The pixel input uses the summary emitted by `analyze-settle.py`.

**Incomplete: this is not yet an automated end-to-end harness.** The scorer
accepts normalized observations, not raw logcat. No collector currently creates
those observations. `TimelineFrameRecorder` is a JVM-test helper, not installed
app diagnostics. The remaining work is debug-only row mount/dispose and
stored-display instrumentation, a serial-restricted Pixel driver with network
restoration, raw-evidence provenance/coverage validation and scenario event
checks (held tool, cancellation/resend and actual network loss). In particular,
a normalized S3 verdict alone does not establish that an interruption occurred.
No baseline may be claimed from these unit checks. Missing device runs must be
reported as BLOCKED, with n=0, separately from measured app failures.

Run fixture tests with `node --test scripts/test-sites/mini-bench/*.test.cjs`
and scorer tests with `python3 -m pytest
scripts/test-sites/mini-bench/test_score_core_loop.py`. Pixel acceptance requires
serial `711KPAE0914240`; never select the first attached device. Before each run,
check for another owner of that serial. Capture terminal, stored display, row
lifecycle, UI text and Stop state plus pixel frames; restore networking on both
normal completion and all exit paths. Do not deploy this fixture to production
or change any other mini agent's model.
