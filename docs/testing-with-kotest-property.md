# Property-Based Testing with kotest-property

`io.kotest:kotest-property` is the standard harness for invariant tests on merge logic, value classes, and protocol validators — code paths where example-based tests keep missing edge cases. It generates many inputs per run and shrinks failures to a minimal counter-example, so a broken invariant surfaces as the smallest input that breaks it instead of a one-off repro.

Reference bead: `letta-mobile-3wo3`. Worked example: `TimelineSyncMergePropertyTest` in `core/src/test/java/com/letta/mobile/data/timeline/`.

## Why

The merge step in `TimelineSyncIngest.kt` has hosted a parade of regressions (`lcp-cv3`, `lcp-pro`, `lcp-r0m`, `wucn-snapshot-recovery` cascade, `mge5.*`). Every fix was example-based: "this particular input now produces this particular output." Example-based coverage leaves the surrounding input space untested — the next regression hides one boundary case away.

A property test states the invariant directly:

```kotlin
"pure-delta merge fold equals direct concatenation of non-empty deltas" {
    checkAll(Arb.list(Arb.string(0..50), 1..20)) { deltas ->
        val merged = deltas.fold("") { acc, delta ->
            if (delta.isEmpty()) acc else acc + delta
        }
        merged shouldBe deltas.joinToString("")
    }
}
```

If someone changes the production merge to do anything other than plain concatenation (snapshot-replace, prefix-drop, "near-snapshot" heuristics), this property fails on the smallest counter-example. The shrinker reports e.g. `["a", "ab"]` instead of the original randomly-generated 17-element list, so the failure is immediately diagnosable.

## Pattern

1. **Inherit from a Kotest spec.** `StringSpec` is the most concise: each `"description" { ... }` block is a test. Other styles (`FunSpec`, `WordSpec`, `DescribeSpec`) work too — pick what reads best for the invariant.
2. **`checkAll(arb1, arb2, ...) { args -> ... }`** runs 1000 iterations by default, each with fresh generated arguments. The block holds the assertion(s); a single failure causes the run to fail with a shrunk counter-example.
3. **`Arb.*` generators** model the input space. Common ones:
   - `Arb.int(range)`, `Arb.string(lenRange, codepointGen?)`
   - `Arb.list(elementArb, sizeRange)`, `Arb.set(...)`, `Arb.map(...)`
   - `Arb.orNull()` to inject nulls; `Arb.choice(a, b, c)` for one-of
   - `arb.filter { ... }` to constrain; `arb.map { ... }` to transform
4. **Assertions are normal Kotest matchers** — `shouldBe`, `shouldHaveSize`, `shouldContain`, etc. The matcher message is included in the shrunk failure report.

## Tagging and tiers

The two test tiers in this project (`unit` and `integration`) apply to property tests too. Tag pure-logic property tests with `@Tag("unit")` so they run in the fast tier:

```kotlin
@Tag("unit")
class TimelineSyncMergePropertyTest : StringSpec({ ... })
```

A 1000-iteration property test on pure functions runs in well under 2s; the bead acceptance is "under 2s for 1000 iterations." If a property is slow, drop the iteration count (`PropTestConfig(iterations = 100)`) before tagging it `integration`.

## When NOT to use property testing

- The invariant under test is "exactly this input maps to exactly this output." Use a regular example test.
- The code path requires expensive setup (database, full ViewModel scaffolding). The fixed overhead × 1000 iterations dwarfs the value.
- Generating valid inputs is harder than asserting the invariant. If the `Arb` becomes a parser/validator in disguise, you're testing the generator, not the code.

## Adoption checklist

When you spot a new property-test candidate:

- [ ] State the invariant in plain English first. If you can't, you don't have a property — write an example test instead.
- [ ] Confirm `kotest-property` is on the module's test classpath. Currently: `:core`, `:feature-chat`.
- [ ] Tag `@Tag("unit")` for pure-logic properties; `@Tag("integration")` for anything that touches Robolectric / Room / Hilt.
- [ ] Pick a generator that overlaps the production input space (sizes, character classes, null-ability) — too narrow misses bugs, too wide finds bugs in your generator instead.
- [ ] If the property fails with an opaque message, attach `withClue { ... }` to the assertion so the shrunk counter-example is actionable.

## Reference

- kotest-property docs: https://kotest.io/docs/proptest/property-based-testing.html
- Companion adoption: `docs/testing-with-turbine.md` (`letta-mobile-7abe`) — Turbine guards Flow emission patterns, property tests guard invariants. Complementary, not overlapping.
