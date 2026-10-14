package com.letta.mobile.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Ratchets for the shared modules' internal layering (letta-mobile-by4vm.27). Both baselines live
 * next to this module's build script and may only shrink; see
 * docs/architecture/shared-layering-ratchet.md for how to retire an entry.
 */
class SharedLayeringTest {
    private val projectRoot: Path = Path.of(requireNotNull(System.getProperty("architecture.projectRoot")))
    private val compose: Path = projectRoot.resolve("android-compose")

    @Test
    fun `shared modules add no new mutually importing package pairs`() {
        val actual = cycleScopes.flatMap { (module, sourceSets) ->
            PackageImportGraph.mutualPairs(sourceFiles(module, sourceSets)).map { pair -> "$module $pair" }
        }
        assertMatchesBaseline(actual, PACKAGE_CYCLE_BASELINE)
    }

    @Test
    fun `sharedLogic declares no new com letta mobile ui packages`() {
        val sourceSets = compose.resolve("sharedLogic/src").toFile().listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.endsWith("Test") }
            .map { it.name }
        val actual = sourceFiles("sharedLogic", sourceSets)
            .map(SourceFileImports::packageName)
            .filter { it == UI_PACKAGE || it.startsWith("$UI_PACKAGE.") }
            .toSet()
        assertMatchesBaseline(actual, SHARED_LOGIC_UI_BASELINE)
    }

    private fun sourceFiles(module: String, sourceSets: List<String>): List<SourceFileImports> {
        val roots = sourceSets.map { compose.resolve("$module/src/$it") }
        check(roots.all(Path::exists)) { "Missing source set under $module: $roots" }
        val files = Konsist.scopeFromExternalDirectories(roots.map(Path::toString)).files
            .map { file -> SourceFileImports(file.packagee?.name.orEmpty(), file.imports.map { it.name }) }
        check(files.isNotEmpty()) { "No Kotlin sources under $roots; the ratchet would check nothing" }
        return files
    }

    private fun assertMatchesBaseline(actual: Collection<String>, baselineFile: String) {
        val baseline = RatchetBaseline.read(compose.resolve("architecture-tests/$baselineFile"))
        val drift = RatchetBaseline.drift(actual, baseline, baselineFile)
        check(drift.isEmpty()) { drift.joinToString(separator = "\n") }
    }

    private companion object {
        const val UI_PACKAGE = "com.letta.mobile.ui"
        const val PACKAGE_CYCLE_BASELINE = "package-cycle-baseline.txt"
        const val SHARED_LOGIC_UI_BASELINE = "sharedlogic-ui-package-baseline.txt"

        val cycleScopes = listOf(
            "sharedLogic" to listOf("commonMain", "jvmAndAndroid"),
            "sharedUI" to listOf("commonMain"),
        )
    }
}
