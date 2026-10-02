package com.letta.mobile.architecture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Synthetic graphs, one per rule. Each `catches` test goes red if its rule is
 * removed from [ArchitectureBoundaryRules.ALL], and a new rule without a
 * synthetic violation fails `every rule is exercised by a synthetic violation`.
 */
class ArchitectureBoundaryGateTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a graph shaped like main passes`() {
        val result = ArchitectureBoundaryGate.evaluate(graph(CLEAN_EDGES), emptyList())

        assertTrue(result.passed, result.report())
    }

    @Test
    fun `catches an edge into app from a library`() {
        assertViolations(edge(":designsystem", ":app"), "into-app :designsystem -> :app")
    }

    @Test
    fun `catches an edge into app from any configuration`() {
        assertViolations(edge(":sharedUI", ":app", "kapt"), "into-app :sharedUI -> :app", "multiplatform-to-single-platform :sharedUI -> :app")
    }

    @Test
    fun `an APK test harness may target app but nothing else gets that pass`() {
        assertViolations(edge(":macrobenchmark", ":app", "testedApks"))
        assertViolations(edge(":cli", ":app", "testedApks"), "into-app :cli -> :app", "shell-to-shell :cli -> :app")
    }

    @Test
    fun `catches feature to feature`() {
        assertViolations(edge(":feature-chat", ":feature-editagent"), "feature-to-feature :feature-chat -> :feature-editagent")
    }

    @Test
    fun `catches shell to shell`() {
        assertViolations(edge(":desktop", ":web"), "shell-to-shell :desktop -> :web")
    }

    @Test
    fun `catches a library depending on a feature`() {
        assertViolations(edge(":designsystem", ":feature-chat"), "library-to-feature :designsystem -> :feature-chat")
    }

    @Test
    fun `catches a multiplatform module depending on a single-platform module`() {
        assertViolations(
            edge(":sharedLogic", ":core:android-data"),
            "multiplatform-to-single-platform :sharedLogic -> :core:android-data",
        )
    }

    @Test
    fun `every rule is exercised by a synthetic violation`() {
        val found = ArchitectureBoundaryRules.violations(graph(CLEAN_EDGES + VIOLATING_EDGES)).map { it.rule }.toSet()

        assertEquals(ArchitectureBoundaryRules.ALL.map { it.id }.toSet(), found)
    }

    @Test
    fun `an empty graph fails closed`() {
        assertFailsWith<ArchitectureGraphException> { ArchitectureGraphReader.parse(emptyList()) }
        assertFailsWith<ArchitectureGraphException> { ArchitectureGraphReader.parse(listOf("", "  ")) }
    }

    @Test
    fun `a graph with modules but no project edges fails closed`() {
        assertFailsWith<ArchitectureGraphException> { ArchitectureGraphReader.parse(MODULES) }
    }

    @Test
    fun `a missing graph file fails closed`() {
        assertFailsWith<ArchitectureGraphException> { ArchitectureGraphReader.read(tempDir.resolve("graph.jsonl")) }
    }

    @Test
    fun `a malformed record or an edge to an unknown module fails closed`() {
        assertFailsWith<ArchitectureGraphException> { ArchitectureGraphReader.parse(MODULES + CLEAN_EDGES + "not json") }
        assertFailsWith<ArchitectureGraphException> {
            ArchitectureGraphReader.parse(MODULES + CLEAN_EDGES + edge(":sharedLogic", ":ghost"))
        }
        assertFailsWith<ArchitectureGraphException> {
            ArchitectureGraphReader.parse(MODULES + """{"type":"projectEdge", "from":":app"}""")
        }
    }

    @Test
    fun `a baselined violation passes and a new one still fails`() {
        val baseline = ArchitectureBoundaryBaseline.parse(listOf("shell-to-shell :desktop -> :web letta-mobile-abc.1  # owned"))

        val baselinedOnly = ArchitectureBoundaryGate.evaluate(graph(CLEAN_EDGES + edge(":desktop", ":web")), baseline)
        assertTrue(baselinedOnly.passed, baselinedOnly.report())

        val withNew = ArchitectureBoundaryGate.evaluate(
            graph(CLEAN_EDGES + edge(":desktop", ":web") + edge(":designsystem", ":app")),
            baseline,
        )
        assertEquals(listOf("into-app :designsystem -> :app"), withNew.newViolations.map(BoundaryViolation::toString))
    }

    @Test
    fun `a stale baseline line fails`() {
        val baseline = ArchitectureBoundaryBaseline.parse(listOf("shell-to-shell :desktop -> :web letta-mobile-abc"))

        val result = ArchitectureBoundaryGate.evaluate(graph(CLEAN_EDGES), baseline)

        assertEquals(baseline, result.staleEntries)
        assertTrue(!result.passed)
    }

    @Test
    fun `a baseline line needs a known rule and an owning bead`() {
        listOf(
            "shell-to-shell :desktop -> :web",
            "shell-to-shell :desktop -> :web TODO",
            "made-up-rule :desktop -> :web letta-mobile-abc",
            "shell-to-shell :desktop :web letta-mobile-abc x",
        ).forEach { line ->
            assertFailsWith<ArchitectureGraphException>(line) { ArchitectureBoundaryBaseline.parse(listOf(line)) }
        }
    }

    @Test
    fun `the checked-in baseline is well formed and owned`() {
        val file = File("../architecture-tests/boundary-baseline.txt").canonicalFile
        assertTrue(file.isFile, "missing $file")

        val baseline = ArchitectureBoundaryBaseline.read(file)

        assertTrue(baseline.all { it.bead.startsWith("letta-mobile-") })
    }

    private fun assertViolations(extraEdge: String, vararg expected: String) {
        val result = ArchitectureBoundaryGate.evaluate(graph(CLEAN_EDGES + extraEdge), emptyList())
        assertEquals(expected.toList(), result.newViolations.map(BoundaryViolation::toString))
    }

    private fun graph(edges: List<String>): ArchitectureGraph = ArchitectureGraphReader.parse(MODULES + edges)

    private companion object {
        val MODULES = listOf(
            module(":", "gradle"),
            module(":app", "android-application"),
            module(":cli", "android-library"),
            module(":core:android-data", "android-library"),
            module(":core:ids", "android-kmp-library"),
            module(":designsystem", "android-library"),
            module(":desktop", "kotlin-jvm"),
            module(":feature-chat", "android-library"),
            module(":feature-editagent", "android-library"),
            module(":macrobenchmark", "android-test"),
            module(":sharedLogic", "android-kmp-library"),
            module(":sharedUI", "android-kmp-library"),
            module(":web", "kotlin-multiplatform"),
        )

        val CLEAN_EDGES = listOf(
            edge(":", ":app", "kover"),
            edge(":app", ":feature-chat"),
            edge(":app", ":feature-editagent"),
            edge(":app", ":designsystem"),
            edge(":core:android-data", ":sharedLogic", "api"),
            edge(":designsystem", ":sharedUI", "api"),
            edge(":desktop", ":sharedUI"),
            edge(":feature-chat", ":designsystem"),
            edge(":macrobenchmark", ":app", "testedApks"),
            edge(":sharedLogic", ":core:ids", "commonMainApi"),
            edge(":sharedUI", ":sharedLogic", "commonMainApi"),
            edge(":web", ":sharedLogic", "wasmJsMainImplementation"),
        )

        val VIOLATING_EDGES = listOf(
            edge(":designsystem", ":app"),
            edge(":desktop", ":web"),
            edge(":feature-chat", ":feature-editagent"),
            edge(":designsystem", ":feature-chat"),
            edge(":sharedLogic", ":core:android-data"),
        )

        fun module(path: String, kind: String) =
            """{"type":"module", "path":"$path", "directory":"${path.trim(':')}", "kind":"$kind"}"""

        fun edge(from: String, to: String, configuration: String = "implementation") =
            """{"type":"projectEdge", "from":"$from", "to":"$to", "configuration":"$configuration"}"""
    }
}
