# Android performance playbook (v1)

A 60-second lookup tool for on-call and feature engineers reacting to a perf
or stability regression. Pick the symptom, follow the branch, run the listed
tool. Each section links to the canonical reference doc — this playbook does
not duplicate that material.

**Scope of v1.** This playbook only covers paths backed by reference docs
already on `main`:

- CI perf gate (cold startup)
- Production JankStats sampling (release jank)
- Sentry ANR / crash budget dashboard (ANR + crash-free)

Perfetto capture, Baseline Profile refresh, Compose metrics interpretation,
and microbenchmark workflows are intentionally out of scope until their
reference docs land. Tracked in `letta-mobile-84oa`.

---

## Quick reference

| Symptom | Branch | Primary tool |
| --- | --- | --- |
| CI perf gate failed on a PR | [Cold start regressed](#1-cold-start-regressed) | Perf gate artifact + local macrobench |
| Field reports of choppy scroll / typing in release | [Release jank regressed](#2-release-jank-regressed) | Sentry jank events + sample-rate bump |
| ANR rate climbing in Play vitals or Sentry | [ANR rate climbing](#3-anr-rate-climbing) | Sentry ANR/crash dashboard |
| Crash-free sessions dropped below budget | [Crash-free dropped](#4-crash-free-sessions-dropped) | Sentry ANR/crash dashboard |

---

## Decision tree

### 1. Cold start regressed

**Trigger.** `Android Perf Gate / perf-gate` CI job failed on a PR, or a
local macrobench run shows `startup.cold.p95_ms` above the
`+20%` ceiling defined in `android-compose/perf/baselines.json`.

**Do this.**

1. Confirm the failure is from the canonical runner (GitHub Actions API 33
   `pixel_6` `x86_64` emulator). Physical-device numbers are not gating and
   cannot be compared against `baselines.json`. See
   [`perf-gate.md` → Canonical benchmark device](perf-gate.md#canonical-benchmark-device).
2. Download the `android-perf-results-*` artifact from the failing run.
   Inspect `*-benchmarkData.json` and the rendered HTML report. Verify the
   failing metric is `StartupBenchmark#coldStartupCompilationPartial` and not
   noise from a different benchmark. See
   [`perf-gate.md` → Investigating flakes](perf-gate.md#investigating-flakes).
3. **Rerun the workflow once.** First-run cold-start variance on the shared
   GitHub Actions emulator is a known characteristic of the gate; a clean
   second run is the documented first response.
4. If the second run also fails on `startup.cold.p95_ms`, treat it as a real
   regression. Do **not** widen tolerances reactively.
5. Reproduce locally if you have the canonical emulator available — see
   [Tool: Run macrobench locally](#run-macrobench-locally).
6. If the regression is intentional and accepted (for example, a deliberate
   startup-cost feature), rebaseline deliberately using the documented
   workflow at
   [`perf-gate.md` → Re-baselining](perf-gate.md#re-baselining). Use the
   `[perf:rebaseline]` PR-title escape hatch only to generate the candidate
   `baselines.json` artifact; the merge still requires the new values to be
   committed.

**Do not.**

- Do not loosen `startup.cold.p95_ms` past `+20%` to make a PR pass without a
  documented baseline change. The current `+20%` envelope already accounts
  for measured shared-runner drift up to `+17.2%`.
- Do not move warm startup back into the gate. Warm startup is informational
  on this runner because of one-sided spikes that are runner noise, not real
  regressions.
- Do not move FrameTimingMetric benchmarks into the gate before the
  deterministic benchmark launch route lands (`letta-mobile-4ccv`). Empty
  benchmark surfaces produce zero `frameDurationCpuMs` samples and false
  failures.

---

### 2. Release jank regressed

**Trigger.** Field reports of dropped frames, choppy scroll, or laggy
composer typing in `release` builds; or an uptick in `Telemetry.event(tag="Jank", name="frame.sampled")`
volume in Sentry.

**Do this.**

1. Confirm you are looking at `release` data, not `debug`. The debug
   `DebugPerformanceMonitor` emits rich local breadcrumbs and StrictMode
   events; that signal does not reflect what users see. See
   [`jankstats-production-sampling.md` → Build-type policy](jankstats-production-sampling.md#build-type-policy).
2. In Sentry, look at the active spans/transactions associated with sampled
   jank events. The production monitor attaches bounded jank measurements
   (`jank_frame_count`, `jank_frame_max_ms`, `jank_frame_total_ms`,
   `jank_frame_over_budget_ms`, `jank_frame_last_ms`) plus per-frame detail
   for the first five janky frames. See
   [`jankstats-production-sampling.md` → Sentry measurements emitted](jankstats-production-sampling.md#sentry-measurements-emitted).
3. If the default `1%` sample rate gives you too few samples to localize
   the regression, raise the rate temporarily on an investigation branch —
   see [Tool: Adjust JankStats sample rate](#adjust-jankstats-sample-rate).
4. Once you have enough samples to identify the affected screen or
   interaction, return the rate to `0.01` before merging back to `main`.

**Do not.**

- Do not rely on debug-build jank to characterize a release regression —
  the debug monitor includes overhead the production monitor explicitly
  avoids.
- Do not leave an elevated `sentry_jankstats_sample_rate` on `main`.
  Higher rates increase runtime overhead and Sentry ingest volume.
- Do not enable the production monitor in `benchmark` builds. It is
  intentionally disabled to avoid polluting macrobenchmark results.

---

### 3. ANR rate climbing

**Trigger.** Google Play vitals shows the weekly ANR rate trending toward
or above `0.47%`, or the Sentry app-hang issue alert fires.

**Do this.**

1. Open the Sentry ANR / crash budget dashboard. The **App hangs / ANR
   issue count (7d)** tile is the manual review surface for the Play-vitals
   threshold — Sentry does not yet expose a first-class mobile ANR-rate
   aggregate. See
   [`sentry-anr-crash-budget.md` → ANR / app-hang caveat](../observability/sentry-anr-crash-budget.md#anr--app-hang-caveat).
2. Treat the issue alert as a triage accelerator: it tells you a spike is
   happening, not the exact rate. Cross-check against Play vitals for the
   authoritative number.
3. Group app-hang issues by transaction tag where possible. The dashboard
   ties ANR visibility to the same Lane C1 transaction set
   (`app.startup`, `chat.sync_cycle`, `chat.send_message`, `bot.turn`,
   `composer.keystroke_to_render`) used by the startup tiles, so a hang
   inside one of those flows is directly attributable.
4. If the regression aligns with a recent release, check the cold-start
   tiles too — startup-time regressions and ANRs sometimes share a root
   cause (main-thread work added to app init).

**Do not.**

- Do not treat the Sentry app-hang issue count as a percentage. It is a
  count fallback; the percentage check still belongs in Play vitals until
  `letta-mobile-j25x` upgrades the alert.
- Do not edit the dashboard or alert payloads in Sentry without
  round-tripping the change back into
  `docs/observability/sentry-anr-crash-budget-dashboard.json` and
  `docs/observability/sentry-anr-crash-budget-alerts.json`. Those files
  are the reprovisioning source. See
  [`sentry-anr-crash-budget.md` → Provisioning workflow](../observability/sentry-anr-crash-budget.md#provisioning-workflow).

---

### 4. Crash-free sessions dropped

**Trigger.** The **Crash-free sessions < 99.5%** Sentry metric alert fires,
or the dashboard's crash-free tile drops below the budget over a 24h
window.

**Do this.**

1. Open the Sentry ANR / crash budget dashboard. Check the **Crash-free
   sessions (7d)** tile and the 7d comparison delta the metric alert ships
   with. See
   [`sentry-anr-crash-budget.md` → What the dashboard covers](../observability/sentry-anr-crash-budget.md#what-the-dashboard-covers).
2. Filter Sentry issues by release to identify whether the drop correlates
   with a specific build. If yes, that build is the suspect; if no, look
   for a backend or device-class change.
3. Cross-reference with the Lane C1 transaction tiles. A crash spike
   inside one of those transactions usually points at the responsible
   surface area faster than scanning unfiltered issues.
4. If the drop is concentrated on a specific device class or Android
   version, capture that in the issue investigation — the alert payload
   does not currently slice by device class, so this filtering is manual.

**Do not.**

- Do not silence the crash-free alert to "ride out" a regression. The
  budget exists to force a response.
- Do not rebaseline the crash-free threshold without an explicit
  decision. `99.5%` over 24h is the documented budget.

---

## Tool sections

### Run macrobench locally

Use this when CI flagged a startup regression and you need to reproduce on
the canonical emulator (or its closest local equivalent) before deciding
whether to fix or rebaseline.

```bash
cd android-compose
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.letta.mobile.macrobenchmark.StartupBenchmark#coldStartupCompilationPartial,\
com.letta.mobile.macrobenchmark.StartupBenchmark#warmStartup
```

Output JSON lands in
`android-compose/macrobenchmark/build/outputs/connected_android_test_additional_output/`.

To compare against the committed baseline without rebaselining:

```bash
python3 perf/check_baselines.py \
  macrobenchmark/build/outputs/connected_android_test_additional_output
```

To rebaseline locally (only after a deliberate decision):

```bash
python3 perf/check_baselines.py \
  macrobenchmark/build/outputs/connected_android_test_additional_output \
  --rebaseline
```

Review the diff against `android-compose/perf/baselines.json` and commit
explicitly. See
[`perf-gate.md` → Local / manual rebaseline](perf-gate.md#local--manual-rebaseline).

### Read the CI perf gate artifact

Every `Android Perf Gate` run uploads `android-perf-results-*`. Inside:

- `*-benchmarkData.json` — the AndroidX benchmark JSON the gate parses
- HTML reports — human-readable per-iteration timings
- The exact `baselines.json` the gate compared against

The parser reads from
`android-compose/macrobenchmark/build/outputs/connected_android_test_additional_output/`,
so the artifact mirrors that path. Match the failing metric in the JSON to
a specific benchmark method before deciding the failure is real.

If the failure looks like emulator startup noise (one bad iteration, others
healthy), rerun the workflow once before assuming a regression. See
[`perf-gate.md` → Investigating flakes](perf-gate.md#investigating-flakes).

### Read the Sentry ANR / crash dashboard

The dashboard groups four budget tiles plus the Lane C1 transaction set:

1. Crash-free sessions (7d) — primary crash budget
2. Cold startup p95 (7d) — `app.startup` filtered to `startupType:cold`
3. Warm startup p95 (7d) — informational comparison
4. App hangs / ANR issue count (7d) — Play-vitals proxy

The committed source payload lives at
`docs/observability/sentry-anr-crash-budget-dashboard.json`. Three alert
rules ship in `docs/observability/sentry-anr-crash-budget-alerts.json`:

- Crash-free sessions < 99.5% over 24h, with a 7d comparison delta
- Cold startup p95 regression > 15% vs previous 7d
- App-hang issue-volume fallback (see Known Limitations in the source doc)

When a tile changes meaning or a rule changes threshold, edit the JSON
files first and round-trip into Sentry — not the other way around. See
[`sentry-anr-crash-budget.md` → Provisioning workflow](../observability/sentry-anr-crash-budget.md#provisioning-workflow).

### Adjust JankStats sample rate

Edit `android-compose/app/src/main/res/values/sentry.xml` and change the
`@string/sentry_jankstats_sample_rate` value. Common temporary settings:

- `0.05` → ~5% of release sessions
- `0.10` → ~10% of release sessions
- `1.0` → all release sessions (investigation only)

Restore to `0.01` before merging back to `main`. The monitor draws a single
random value per process start and enables JankStats only when
`draw < sampleRate`, so the rate change takes effect on next process start
in the bumped build. See
[`jankstats-production-sampling.md` → Increasing the rate for targeted investigations](jankstats-production-sampling.md#increasing-the-rate-for-targeted-investigations).

---

## References

Reference docs cited above:

- [`docs/performance/perf-gate.md`](perf-gate.md) — canonical CI gate, tolerances, rebaseline workflow, flake investigation, regression simulation.
- [`docs/performance/jankstats-production-sampling.md`](jankstats-production-sampling.md) — release-build JankStats sampling policy, Sentry measurements, rate tuning.
- [`docs/observability/sentry-anr-crash-budget.md`](../observability/sentry-anr-crash-budget.md) — dashboard tiles, alert rules, ANR caveat, provisioning workflow.

Related artifacts:

- `android-compose/perf/baselines.json` — gate tolerances live here, not in the checker.
- `docs/observability/sentry-anr-crash-budget-dashboard.json` — committed dashboard source.
- `docs/observability/sentry-anr-crash-budget-alerts.json` — committed alert rules.

Related beads:

- `letta-mobile-o7ob.4.1` — original perf gate landing (context for the `+20%` cold-start envelope).
- `letta-mobile-o7ob.3.4` — production JankStats sampling.
- `letta-mobile-o7ob.3.5` — Sentry ANR / crash budget dashboard.
- `letta-mobile-4ccv` — extend the perf gate with FrameTimingMetric benchmarks once a deterministic launch route exists.
- `letta-mobile-ce2c` — API 30 `profileinstaller` handshake; reason the canonical runner is API 33.
- `letta-mobile-j25x` — upgrade the ANR fallback alert to a first-class mobile ANR-rate metric.
- `letta-mobile-84oa` — extend this playbook with Perfetto capture, Baseline Profile refresh, Compose metrics, and microbench sections once their reference docs land on `main`.
