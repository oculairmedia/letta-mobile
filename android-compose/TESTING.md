# Test tier contract

The Android test suite is split into explicit tiers so local runs stay fast and CI can spend heavier work where it matters.

## Tiers

| Tier | Tag | Typical contents | Run with |
| --- | --- | --- | --- |
| Unit | `@Tag("unit")` | Pure JVM logic, parsers, mappers, serializers, helper functions | `./gradlew :module:testUnit --max-workers=1` |
| Integration | `@Tag("integration")` | Robolectric, Compose-backed tests, Android runtime setup, ViewModel wiring | `./gradlew :module:testIntegration --max-workers=1` |
| Screenshot | `@Tag("screenshot")` | Paparazzi or Roborazzi visual regression tests | `./gradlew :module:testScreenshot --max-workers=1` |

`check` and each module's default debug unit-test task still run the full suite. In the app module, use `testPlayDebugUnitTest` as the conservative default because the app has distribution flavors. The tiered tasks are additive filters for faster local verification and CI splitting.

## Authoring rules

- Every new file under `src/test/` must declare exactly one tier tag on the test class.
- Prefer `unit` unless the test needs Android resources, Compose runtime behavior, Robolectric, or heavier wiring.
- Use `integration` for tests that boot Android framework code, exercise ViewModel + repository plumbing together, or depend on mocked platform behavior.
- Use `screenshot` only for visual golden tests.

## Module commands

Run these from `android-compose/`.

```bash
./gradlew :app:testUnit --max-workers=1
./gradlew :app:testIntegration --max-workers=1
./gradlew :app:testScreenshot --max-workers=1

./gradlew :core:testUnit --max-workers=1
./gradlew :core:testIntegration --max-workers=1

./gradlew :bot:testUnit --max-workers=1
./gradlew :bot:testIntegration --max-workers=1

./gradlew :designsystem:testUnit --max-workers=1
./gradlew :designsystem:testIntegration --max-workers=1
./gradlew :designsystem:testScreenshot --max-workers=1
```

`app/TESTING.md` remains the place for snapshot-tooling specifics in the app module. This file defines the cross-module tiering policy.
