package com.letta.mobile.architecture

import com.letta.mobile.architecture.CountRatchetBaseline.Shrink
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Typed-id ratchets over production sources (letta-mobile-by4vm.23). The class name ends in
 * `RepositoryArchitectureTest` so the existing `architectureTest` filter runs it with the other
 * production scans. Both baselines live next to this module's build script; see
 * docs/architecture/typed-ids-and-id-ratchets.md for how to shrink them.
 *
 * Every run writes the current counts to `build/reports/id-ratchets/` in baseline format, so a
 * legitimate update is a copy of the matching lines rather than a hand count.
 */
class TypedIdRepositoryArchitectureTest {
    private val projectRoot: Path = Path.of(requireNotNull(System.getProperty("architecture.projectRoot")))
    private val compose: Path = projectRoot.resolve("android-compose")

    @Test
    fun `no new hardcoded id-prefix literals outside core ids`() {
        val modules = IdRatchetScan.modulesWithSources(compose).filterNot { it in idLiteralExemptModules }
        val actual = IdRatchetScan.countPerFile(
            compose,
            IdRatchetScan.productionKotlinFiles(compose, modules),
            IdRatchetScan::idLiteralCount,
        )
        assertWithinBaseline(actual, ID_LITERAL_BASELINE, Shrink.MUST_UPDATE)
    }

    @Test
    fun `no file gains raw String id parameters in shared and core modules`() {
        val modules = IdRatchetScan.modulesWithSources(compose)
            .filter { it == "sharedLogic" || it == "sharedUI" || it.startsWith("core/") }
            .filterNot { it == "core/testutil" }
        check("sharedLogic" in modules && "core/ids" in modules) { "Scope lost a module: $modules" }
        val actual = IdRatchetScan.countPerFile(
            compose,
            IdRatchetScan.productionKotlinFiles(compose, modules),
            IdRatchetScan::rawIdParameterCount,
        )
        assertWithinBaseline(actual, RAW_ID_PARAMS_BASELINE, Shrink.FREE)
    }

    private fun assertWithinBaseline(actual: Map<String, Int>, baselineFile: String, shrink: Shrink) {
        check(actual.isNotEmpty()) { "$baselineFile scan found nothing; the ratchet would check nothing" }
        val report = compose.resolve("architecture-tests/build/reports/id-ratchets").createDirectories()
        report.resolve(baselineFile).writeText(CountRatchetBaseline.render(actual))
        val baseline = CountRatchetBaseline.read(compose.resolve("architecture-tests/$baselineFile"))
        val drift = CountRatchetBaseline.drift(actual, baseline, baselineFile, shrink)
        check(drift.isEmpty()) { drift.joinToString(separator = "\n") }
    }

    private companion object {
        const val ID_LITERAL_BASELINE = "id-literal-baseline.txt"
        const val RAW_ID_PARAMS_BASELINE = "raw-id-params-baseline.txt"

        /** core/ids owns the formats; the rest are build, test-support or vendored code. */
        val idLiteralExemptModules = setOf(
            "core/ids",
            "core/testutil",
            "architecture-tests",
            "baselineprofile",
            "build-logic",
            "drawbox",
            "macrobenchmark",
            "plugin-api-testkit",
            "quality/detekt-rules",
            "sharedUI-devfixtures",
        )
    }
}
