# App Performance Score History

Quarterly review log for the official Android App Performance Score rubric.
The entry and its due date below are historical Q2 evidence, not an October score;
source audits cannot refresh measured startup/rendering scores.
Run the review, record the score, and file any uncovered gaps as beads under
`letta-mobile-o7ob`.

## Rubric source

- Official rubric: <https://developer.android.com/topic/performance/app-score>
- Supporting thresholds: <https://developer.android.com/topic/performance/vitals>

## Scoring notes for this repo

Google publishes the **criteria** for the App Performance Score, but not a
public quiz URL or exported scoring sheet. This repo therefore logs a
rubric-aligned quarterly review using the official categories and the best
shipped evidence available at review time.

- **Static score** — percentage of official static-rubric checks met in the
  source tree and release configuration.
- **Dynamic score** — operator score across the two official dynamic areas
  (startup and rendering), grounded in the evidence available that quarter.
  Prefer representative physical-device measurements; if only proxy evidence is
  available, call that out explicitly.
- **Overall score** — rounded average of static and dynamic.

Static rubric checks used here:

1. current AGP line in repo
2. minification / optimization enabled with R8
3. Baseline Profiles present and applied
4. Baseline Profiles cover one or more user journeys
5. Startup Profiles / dex-layout optimization applied
6. current Compose stack adopted
7. `FullyDrawnReporter` / `reportFullyDrawn()` wired at the interactive-ready
   point

Dynamic rubric areas used here:

1. application startup
2. rendering performance

## Format per entry

- **Quarter**: `YYYY-Qn`
- **Date run**: ISO date
- **Operator**: who ran the assessment
- **Score**: overall + static + dynamic + category breakdown
- **Strengths**: what the app scores well on
- **Gaps**: what the app still does poorly or measures only partially
- **Deltas filed as beads**: new bead IDs created from the review, plus any
  existing open beads that already cover the same gaps
- **Next review due**: ISO date (+ 90 days)

## History

### 2026-Q2 — first baseline

- **Date run**: 2026-04-22
- **Operator**: Claude Code (OpenCode)
- **Rubric source**: Android App Performance Score + Android Vitals thresholds
- **Score**:
  - **Overall**: `63 / 100`
  - **Static**: `71 / 100` (`5 / 7` rubric checks met)
  - **Dynamic**: `55 / 100` (proxy review; startup evidence is stronger than
    rendering evidence)
  - **Category breakdown**:
    - **Build-time improvements**: `2 / 2`
      - `android-compose/build.gradle.kts` is on AGP `8.9.2`
      - `android-compose/app/build.gradle.kts` enables release minification and
        optimization (`isMinifyEnabled = true`, R8/proguard wiring present)
    - **Startup performance tooling**: `2 / 3`
      - Baseline Profiles are present and bundled into release builds
      - the generator covers startup + scroll + drill-in user journeys
      - Startup Profiles / dex-layout optimization are **not** present
    - **Compose adoption**: `1 / 1`
      - app is on the current repo Compose stack (`compose-bom:2026.03.01`)
    - **Monitoring and optimization**: `0 / 1`
      - no `FullyDrawnReporter` / `reportFullyDrawn()` hook is wired today
    - **Dynamic startup**: `65 / 100`
      - strong proxy evidence: canonical CI cold-start gate is seeded and
        enforced (`1512.749 ms` baseline, `+20%` tolerance)
      - gap: this is still `timeToInitialDisplayMs` on the canonical API 33
        emulator, not a representative-device TTFD run
    - **Dynamic rendering**: `45 / 100`
      - good signal exists: release JankStats sampling ships at `1%` and
        attaches bounded jank measurements to active Sentry spans
      - gap: no deterministic scroll/composer CI benchmark route yet, and this
        quarter does not log a representative-device slow/frozen-frame baseline
- **Strengths**:
  - release APK ships with a checked-in Baseline Profile and a dedicated
    baseline-profile generator module
  - startup regressions are gated in CI on the canonical API 33 emulator
  - release builds emit sampled JankStats measurements to Sentry with bounded
    per-span metrics
  - crash-free sessions, cold-start regression, and ANR/app-hang fallback are
    already tracked in the committed Sentry dashboard + alert payloads
- **Gaps**:
  - no fully-drawn startup completion signal yet
  - no Startup Profile / dex-layout optimization path yet
  - quarterly review still lacks a representative physical-device App
    Performance Score run path for startup + rendering
  - rendering evidence is partial until deterministic scroll/composer
    macrobench coverage lands in CI
  - ANR alerting is still app-hang issue-volume fallback rather than a
    Play-vitals-style rate metric
- **Deltas filed as beads**:
  - **New from this review**: `letta-mobile-o7ob.2.11` — fully-drawn signal +
    Startup Profiles
  - **Already open and relevant**:
    - `letta-mobile-4ccv` — deterministic scroll/composer CI benchmark route
    - `letta-mobile-ce2c` — slow-floor physical-device benchmark path
    - `letta-mobile-wy3c` — sustainable Baseline Profile generation path
    - `letta-mobile-j25x` — ANR rate metric upgrade
- **Next review due**: 2026-07-22

## Evidence used for 2026-Q2

- `docs/performance/perf-gate.md`
- `docs/performance/jankstats-production-sampling.md`
- `docs/performance/playbook.md`
- `docs/observability/sentry-anr-crash-budget.md`
- `android-compose/perf/baselines.json`
- `android-compose/baselineprofile/build.gradle.kts`
- `android-compose/baselineprofile/src/main/java/com/letta/mobile/baselineprofile/BaselineProfileGenerator.kt`
- `android-compose/app/build.gradle.kts`
- `android-compose/app/src/main/java/com/letta/mobile/LettaApplication.kt`
- `android-compose/app/src/main/java/com/letta/mobile/performance/ProductionJankStatsMonitor.kt`

## Related

- `docs/performance/playbook.md`
- `letta-mobile-o7ob.4.3`
- `letta-mobile-o7ob.2.11`
