# Architecture gates

This module keeps architecture checks separate from production modules.

- `./gradlew :architecture-tests:test` runs fixture tests that prove known source and cycle violations are detected, plus Gradle isolation greps (`SharedLogicIsolationTest`, `SharedUiIsolationTest`).
- `./gradlew :architecture-tests:architectureTest` runs production Konsist source checks (`KotlinSourcePolicy.productionScope`) plus ArchUnit bytecode cycle/layer checks. It depends on `:core:ids:jvmMainClasses` and `:sharedLogic:jvmMainClasses`. **Failures fail the build** (required CI via the `test` job).
- `./gradlew advisoryDetekt` runs the Detekt configuration already introduced on main and writes SARIF, XML, and HTML under each participating module's `build/reports/detekt/`. Generated/build/KSP sources are excluded. Detekt remains advisory (`-Parchitecture.strict=true` still promotes Detekt only).

Konsist scans production `*Main` source sets and excludes `build`, `generated`, and `ksp` source paths. CommonMain must not import `android.*`, `java.*`, or `javax.*`. ArchUnit imports the compiled `core/ids` and `sharedLogic` JVM surface (former `:core:domain` contract packages), excludes test-fixture packages, checks package-slice cycles, and constrains repository API/model dependencies. `SharedLogicIsolationTest` fails if `:sharedLogic` Gradle-depends on `:app`, `:core:android-data`, `:designsystem`, `:feature-*`, `:desktop`, `:web`, or `:sharedUI`. Generated classes that compile into the same class directory cannot always be distinguished by bytecode location, so any such finding remains advisory and must be verified against source provenance.
