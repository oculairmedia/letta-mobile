## Summary

<!-- 1-3 bullets: what changed and why. -->

Shared implementation searched: <paths>
<!-- REQUIRED (owner rule, AGENTS.md "No unshared frontend or logic"; CI fails without it).
     Replace <paths> with the sharedLogic/ and sharedUI/ paths you searched for an existing
     implementation, e.g. sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/shell/
     Reuse what you find; platform modules only bind. -->

## Test plan

- [ ] `./gradlew :app:compileRootDebugKotlin` (local)
- [ ] `./gradlew :app:testRootDebugUnitTest` (local)
- [ ] CI: `test` (incl. parity guardrails), `build-apk-pass`, `shared-multiplatform` and `detekt` green
- [ ] Manual verification: <!-- screenshot, device test, or N/A -->

## Beads

<!-- Link to the bd issue this addresses (e.g. letta-mobile-xxxx), or "N/A". -->

## Notes for reviewer

<!-- Anything non-obvious: rollback strategy, blast radius, follow-up TODOs. -->
