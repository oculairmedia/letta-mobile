# Advisory architecture gates

This module keeps architecture checks separate from production modules and central CI workflows.

- `./gradlew advisoryDetekt` runs the Detekt configuration already introduced on main and writes SARIF, XML, and HTML under each participating module's `build/reports/detekt/`. Generated/build/KSP sources are excluded.
- `./gradlew :architecture-tests:architectureTest` runs repository-facing Konsist source checks plus ArchUnit bytecode cycle/layer checks. It depends only on the small JVM-capable core surface needed by the bytecode rules.
- Both commands are non-blocking by default during baseline rollout. Add `-Parchitecture.strict=true` to fail on findings after the existing debt is triaged.
- `./gradlew :architecture-tests:test` also runs fixture tests that prove known source and cycle violations are detected.

Konsist scans production `*Main` source sets and excludes `build`, `generated`, and `ksp` source paths. ArchUnit imports the compiled `core/ids` and `core/domain` surface, excludes test-fixture packages, checks package-slice cycles, and constrains repository API/model dependencies. Generated classes that compile into the same class directory cannot always be distinguished by bytecode location, so any such finding remains advisory and must be verified against source provenance.

The advisory architecture workflow runs these reports for Gradle and Kotlin/Java changes, uploads their artifacts, and can promote either gate independently after the baseline is triaged.
