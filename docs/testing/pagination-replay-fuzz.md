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

Failures print `seed=… scenario=…`. Timeline replay failures also print `op=…`, plus an optional minimized op trace (`index:label|…`). Assertions use `FuzzScenarioContext` in `DeterministicFuzzSupport.kt` so diagnostics stay attached without per-call seed plumbing.

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

## Extending campaigns

Increase `FuzzCorpus.SCENARIOS_PER_SEED` or append seeds in `DeterministicFuzzSupport.kt`. Keep cases cheap for PR `shared-multiplatform` (`:sharedLogic:jvmTest`). Do not weaken invariants when a seed finds a production bug — capture the seed in the failure output and fix production separately.

## Deliberate exclusions

- Fleet: agent-free rows, cancellation mid-read, archive-hiding (only `hidden` list filter), cross-agent duplicate ids (generator uses unique ids per agent).
- Timeline: tool/approval/reconcile paths, skill envelopes, terminal resurrection, invalid cross-conversation traffic.
- Pagination oracle: the generated fixtures serve unique consecutive pages before repeating an already-read page. Expected membership and terminal request counts are computed algebraically, without another pagination loop.
