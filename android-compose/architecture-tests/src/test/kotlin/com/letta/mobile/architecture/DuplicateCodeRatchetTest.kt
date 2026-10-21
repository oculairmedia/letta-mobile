package com.letta.mobile.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Cross-module duplicate-code ratchet (letta-mobile-ewtaq). Owner rule: no unshared frontend or
 * logic, so a block copied between modules (desktop and web, app and sharedUI, ...) fails CI.
 * `duplicate-code-baseline.txt` holds the clones on main when the ratchet landed, as
 * `<fileA>|<fileB> <lines>`; it only shrinks (scripts/ci/baseline-ratchet-guard.py rejects an added
 * or raised line), and a pair that shrinks or disappears must lower or delete its line here.
 */
class DuplicateCodeRatchetTest {
    private val projectRoot: Path = Path.of(requireNotNull(System.getProperty("architecture.projectRoot")))
    private val baselineFile = projectRoot.resolve("android-compose/architecture-tests/$BASELINE")

    @Test
    fun `no new code is duplicated across modules`() {
        val sources = DuplicateCodeScan.productionSources(projectRoot)
        check(sources.size > 500) { "Scanned ${sources.size} files; the scan is not seeing the source tree" }
        val actual = DuplicateCodeScan.crossModuleDuplicates(projectRoot, sources)
        // The tree's pairs in baseline format, for lowering lines after a cleanup.
        val report = projectRoot.resolve("android-compose/architecture-tests/build/reports/duplicate-code/actual.txt")
        report.parent.createDirectories()
        report.writeText(CountRatchetBaseline.render(actual))
        val drift = CountRatchetBaseline.drift(actual, CountRatchetBaseline.read(baselineFile), BASELINE, CountRatchetBaseline.Shrink.MUST_UPDATE)
        check(drift.isEmpty()) { failureMessage(drift) }
    }

    internal companion object {
        const val BASELINE = "duplicate-code-baseline.txt"

        fun failureMessage(drift: List<String>): String = buildString {
            appendLine("Cross-module duplicate code (PMD CPD, ${DuplicateCodeScan.MINIMUM_TOKENS}+ tokens) moved against $BASELINE:")
            drift.forEach { appendLine("  $it") }
            appendLine("What to do:")
            appendLine("  NEW: the same code lives in two modules. Keep ONE implementation in sharedLogic/commonMain")
            appendLine("       (UI in sharedUI/commonMain), delete the copy and call the shared one from both sides.")
            appendLine("       Never add or raise a baseline line (the ratchet guard rejects it).")
            appendLine("  STALE: duplication went down. Lower or delete that line in")
            appendLine("       android-compose/architecture-tests/$BASELINE so it cannot grow back")
            append("       (the tree's current pairs: architecture-tests/build/reports/duplicate-code/actual.txt).")
        }
    }
}
