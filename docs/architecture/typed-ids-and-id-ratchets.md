# Typed ids and the id ratchets

Bead: letta-mobile-by4vm.23. Ids in `sharedLogic` are still mostly raw `String`s. On main, when
this note was written, the scan counted 1,842 raw `*Id: String` parameters across 462 files, plus
40 hardcoded id-prefix literals across 24 files. The fix is gradual: typed ids and one owner for
id formats now exist, and two ratchets stop the numbers from growing while call sites migrate one
PR at a time.

## Where things live

| What | Where |
|---|---|
| Domain id value classes (`AgentId`, `ConversationId`, `MessageId`, `RunId`, `RequestId`, `PluginId`, …) | `android-compose/core/ids/src/commonMain/kotlin/com/letta/mobile/data/model/DomainIds.kt` |
| Id string formats (`IdFormats`) | `android-compose/core/ids/src/commonMain/kotlin/com/letta/mobile/data/model/IdFormats.kt` |
| Runtime-contract ids (`ToolCallId`, `ToolApprovalId`, `BackendId`, runtime `RunId`/`ConversationId`) | `android-compose/core/runtime/src/commonMain/kotlin/com/letta/mobile/runtime/` |
| Ratchet test, scanner and baselines | `android-compose/architecture-tests/` (`TypedIdRepositoryArchitectureTest`, `IdRatchetScan`, `CountRatchetBaseline`, `id-literal-baseline.txt`, `raw-id-params-baseline.txt`) |

`core/runtime` keeps its own `RunId` and `ConversationId` for the turn contracts. New data-layer
code should take the `com.letta.mobile.data.model` types. Merging the two sets is follow-up work.

## Adding a typed id

1. Add a value class to `DomainIds.kt` in the existing style:

   ```kotlin
   @JvmInline
   @Serializable
   value class FooId(val value: String) {
       init {
           require(value.isNotBlank()) { "FooId cannot be blank." }
       }

       override fun toString(): String = value
   }
   ```

   It serializes as its bare string, so wire shapes don't change. Leave out the blank check only
   if blank values really occur on the wire. Adding the check to an existing type later is a
   behaviour change, because decoding a blank value then throws.
2. Add tests to `core/ids/src/commonTest` (round-trip serialization, `toString`, blank rejection).
   Test names must be camelCase with no punctuation, because the Kotlin/Native build rejects
   backtick names containing `()` or `.`. CI runs `:core:ids:jvmTest` whenever `core/ids` changes.
3. If the id has a special string shape (a prefix, a sentinel), put the constant and the predicate
   in `IdFormats`. Don't add a new literal at the call site.

## Migrating call sites (shrinking the baselines)

Migrate a few files per PR. A sweeping migration conflicts with every other PR in flight.

- **Raw id params** (`raw-id-params-baseline.txt`): change `fun f(agentId: String)` to
  `fun f(agentId: AgentId)` and unwrap with `.value` at the edges (wire DTOs, Room, logging).
  Shrinking costs nothing: the test doesn't fail when a count falls. Lower or delete the file's
  line anyway so the next PR can't spend the gain. A file that is renamed or split shows up as
  NEW; move its line to the new path.
- **Id literals** (`id-literal-baseline.txt`): replace the literal with an `IdFormats` reference.
  This baseline must stay exact, so a count that falls fails as STALE until you lower the line,
  or delete it once the count reaches 0. This keeps the list a true inventory of what is left.

To get the right numbers, run `./gradlew :architecture-tests:architectureTest`. Every run writes
the current counts, in baseline format, to `android-compose/architecture-tests/build/reports/id-ratchets/`.
Copy the lines for the files you changed. Each baseline stays sorted by path with one line per
file, and the test enforces this.

Raising a count is not the fix for a NEW failure. Use a typed id or `IdFormats` in the new code.

## What the scans count

Both scans are line-based heuristics, not a Kotlin parser. Lines that start with `//`, `*` or `/*`
are skipped. They scan production source sets only: any `src/<set>` whose name does not contain
"test".

- **Id literals**: a string literal that starts with `conv-default-`, `client-` or `cm-` (including
  templates and longer forms such as `"cm-stream-"`). Also the bare `"agent-"` prefix or an
  `"agent-$x"` template. `"agent-starter"`-style tags are not counted. Also `"default"` on a line
  that mentions an id or a conversation (`id`, `fooId`, `ID`, `conversation`, `CONVERSATION`,
  `conv`). Skipped modules: `core/ids` (the owner), plus test-support, build and vendored modules
  (`core/testutil`, `architecture-tests`, `baselineprofile`, `build-logic`, `drawbox`,
  `macrobenchmark`, `plugin-api-testkit`, `quality/detekt-rules`, `sharedUI-devfixtures`).
- **Raw id params**: `name: String` or `name: String?` where the name ends in `Id`/`ID`, in
  `sharedLogic`, `sharedUI` and `core/*` (except `core/testutil`). This covers function,
  constructor and lambda parameters. Declarations directly after `val `/`var ` are properties and
  are not counted.

The production checks run in `TypedIdRepositoryArchitectureTest`. Its name puts it under the
existing `*RepositoryArchitectureTest` filter of `:architecture-tests:architectureTest`, which CI
requires. `IdRatchetFixtureTest` runs in `:architecture-tests:test` and proves the counters and
the drift rules on synthetic input.
