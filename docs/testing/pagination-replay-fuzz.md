# Seeded pagination and timeline-replay fuzz tests

Local focused run (one Gradle build at a time on shared hosts):

```bash
export JAVA_HOME=/usr/lib/jvm/jdk-26
cd android-compose
./gradlew --no-daemon --max-workers=2 :sharedLogic:jvmTest \
  --tests 'com.letta.mobile.util.DeterministicFuzzSupportTest' \
  --tests 'com.letta.mobile.util.AgentConversationFleetFuzzTest' \
  --tests 'com.letta.mobile.util.PaginationHelpersFuzzTest' \
  --tests 'com.letta.mobile.data.timeline.TimelineStreamReplayFuzzTest'
```

Strict detekt (sharedLogic, JDK 21):

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew --no-daemon detekt -Parchitecture.strict=true
```

## Seeds and volume

| Suite | Seeds | Scenarios / seed | Total cases |
| --- | --- | --- | --- |
| Fleet pagination | 6 (`FuzzCorpus.paginationSeeds`) | 50 | 300 |
| Offset exhaust | same corpus (xor `0x0FF5E7`) | 50 | 300 |
| Cursor exhaust | same corpus (xor `0xC05000`) | 50 | 300 |
| Timeline replay | 6 (`FuzzCorpus.timelineSeeds`) | 50 | 300 |
| Fuzz support | — | 6 unit tests | 6 |

Failures print `seed=…` and `scenario=…`. Timeline replay failures also include `op=…` when the failing step is known, and may attach a minimized `trace=` (`index:label|…`). Pagination-family failures do not include `op=` in the message. Assertions use `FuzzScenarioContext` in `DeterministicFuzzSupport.kt` so diagnostics stay attached without per-call seed plumbing.

## Reproducing one scenario

Per-scenario RNG: `scenarioSeed(baseSeed, scenarioIndex)` in `DeterministicFuzzSupport.kt` (portable; no JVM system properties). Example: fleet seed `0x91A80C2B_0003L`, scenario `12` → `Random(scenarioSeed(0x91A80C2B_0003L, 12))`.

## Shrinking

**Suffix deletion only** (`minimizeBySuffixDeletion`): shrinks while the scenario still throws `FuzzCaseFailure`; other exceptions propagate. Timeline failures attach a compact `trace=` of minimized ops. No general delta-debugger.

## Behavioral mutation probe (local, not committed)

Proves pagination fuzz catches real production regressions (not inverted assertions):

```bash
ROOT=$(git rev-parse --show-toplevel)
PROD="$ROOT/android-compose/sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/repository/PaginationHelpers.kt"
BACKUP=$(mktemp)
cp -f "$PROD" "$BACKUP"
trap 'cp -f "$BACKUP" "$PROD"; rm -f "$BACKUP"' EXIT
# Drop duplicate-page guard so ignored-offset servers spin wrong results:
sed -i 's/if (fresh.isEmpty()) return merged/if (false \&\& fresh.isEmpty()) return merged/' "$PROD"
export JAVA_HOME=/usr/lib/jvm/jdk-26
cd "$ROOT/android-compose"
if ./gradlew --no-daemon --max-workers=2 :sharedLogic:jvmTest \
  --tests 'com.letta.mobile.util.PaginationHelpersFuzzTest.seededOffsetExhaustMatchesScheduleExpectation'; then
  echo UNEXPECTED_PASS
  exit 1
fi
# Inspect fresh JUnit XML: require a FuzzCaseFailure on the fetch-count assertion,
# not merely a failed build, compilation error, or stale result.
cp -f "$BACKUP" "$PROD"
./gradlew --no-daemon --max-workers=2 :sharedLogic:jvmTest \
  --tests 'com.letta.mobile.util.PaginationHelpersFuzzTest'
git diff -- "$PROD"   # must be empty
```

Invariant covered: offset exhaust stops on duplicate-only pages (`fresh.isEmpty()`). Not probed here: cursor sticky-cursor path, fleet merge, timeline stale-seq (separate oracles).

## Bounded nightly / manual campaign

PR `jvmTest` keeps the fixed corpus above (1200 generated cases + support tests). Larger campaigns reuse the same generators via internal `run*Campaign` helpers and the JVM-only runner `PaginationReplayFuzzCampaignRunnerTest`, invoked only from Gradle task `:sharedLogic:paginationReplayFuzzCampaign` (excluded from default `jvmTest`).

| Profile | Seeds | Scenarios / seed | Families | Total cases |
| --- | --- | --- | --- | --- |
| PR corpus (`jvmTest`) | 6 per family space | 50 | 4 | 1200 |
| PR workflow smoke | 2 | 3 | 4 | 24 |
| Nightly (initial) | 32 | 200 | 4 | 25600 |

Local smoke (24 cases):

```bash
export JAVA_HOME=/usr/lib/jvm/jdk-26
cd android-compose
mkdir -p ../campaign
python3 ../scripts/fuzz/resolve-pagination-replay-campaign-params.py --kind pr-smoke
set -a && source ../campaign/fuzz-params.env && set +a
./gradlew --no-daemon --max-workers=2 :sharedLogic:paginationReplayFuzzCampaign \
  -PfuzzCampaignSeedBase="$FUZZ_CAMPAIGN_SEED_BASE" \
  -PfuzzCampaignSeedCount="$FUZZ_CAMPAIGN_SEED_COUNT" \
  -PfuzzCampaignScenariosPerSeed="$FUZZ_CAMPAIGN_SCENARIOS_PER_SEED" \
  -PfuzzCampaignManifestPath="../campaign/fuzz-manifest.json" \
  -PfuzzCampaignKind="$FUZZ_CAMPAIGN_KIND"
```

Scheduled workflow: `.github/workflows/pagination-replay-fuzz.yml` (`23 6 * * *` UTC). Seed base for schedule is derived from the UTC date in `scripts/fuzz/resolve-pagination-replay-campaign-params.py`. Manual `workflow_dispatch` uses validated Python bounds (1..256 seeds, 1..1000 scenarios); workflow inputs are never interpolated into shell.

**Fleet ordering limitation:** the fleet family sorts expected output with production `ConversationRecency.sortKeyMillis` on generated valid ISO-8601 `Z` timestamps. That checks fleet ordering against the same recency policy the app uses; it does not independently fuzz date parsing.

## Extending campaigns

Adjust nightly bounds in `resolve-pagination-replay-campaign-params.py` and `FuzzCampaign.kt` (`FuzzCampaignLimits`). Do not change `FuzzCorpus` for larger nightly volume — use `-PfuzzCampaign*` / the workflow resolver instead. Do not weaken invariants when a seed finds a production bug — capture the seed in the failure output and fix production separately.

## Deliberate exclusions

- Fleet: agent-free rows, cancellation mid-read, archive-hiding (only `hidden` list filter), cross-agent duplicate ids (generator uses unique ids per agent).
- Timeline: tool/approval/reconcile paths, skill envelopes, terminal resurrection, invalid cross-conversation traffic.
- Pagination oracle: the generated fixtures serve unique consecutive pages before repeating an already-read page. Expected membership and terminal request counts are computed algebraically, without another pagination loop.
