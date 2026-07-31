package com.letta.mobile.data.controller.node.iroh

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LettaShim retirement architecture gate.
 *
 * Phase 4 cleared the frozen Phase 1 violation inventory. New production
 * wiring that reintroduces LettaShim admin base / HTTP subagent discovery /
 * shim_until_cutover matrix rows fails this suite immediately.
 *
 * Set `SHIM_FREE_ARCHITECTURE_GATE=1` to also assert the scan is empty in
 * environments that want the hard gate enabled explicitly (same as empty
 * inventory here).
 */
class ShimRetirementArchitectureGateTest {
    private data class Violation(val id: String, val path: String, val detail: String)

    private val repoRoot: Path = locateRepoRoot()

    @Test
    fun knownShimRetirementViolationsAreEmpty() {
        val remaining = scanViolations()
        assertEquals(
            emptyList(),
            remaining,
            buildString {
                appendLine("Shim-retirement architecture inventory must stay empty after Phase 4.")
                remaining.forEach { appendLine(" - ${it.id}: ${it.path}: ${it.detail}") }
            },
        )
    }

    @Test
    fun shimFreeProductionWiringHasNoKnownViolations() {
        val enabled = System.getenv("SHIM_FREE_ARCHITECTURE_GATE") == "1"
        val remaining = scanViolations()
        if (!enabled) {
            assertTrue(remaining.isEmpty(), "Phase 4 cleared the inventory; unexpected findings: $remaining")
            return
        }
        assertEquals(
            emptySet(),
            remaining.map { it.id }.toSet(),
            "Shim-free architecture gate is enabled; production wiring must have zero known violations",
        )
    }

    private fun scanViolations(): List<Violation> {
        val findings = mutableListOf<Violation>()
        // letta-mobile-zsgad moved the production wrapper command out of the
        // Android `:cli` module into the pure-JVM `:iroh-wrapper-cli` module so
        // it could ship as an installable distribution. This gate follows the
        // file: it must scan whatever the deployed wrapper actually runs, so a
        // missing file has to fail loudly rather than silently scanning nothing.
        val cli = repoRoot.resolve(
            "android-compose/iroh-wrapper-cli/src/main/kotlin/com/letta/mobile/cli/commands/" +
                "AppServerServeIrohCommand.kt",
        )
        assertTrue(
            cli.isRegularFile(),
            "shim-retirement gate cannot find the wrapper command at ${rel(cli)}; " +
                "if it moved again, update this path instead of deleting the check",
        )
        val cliText = cli.readText()
        if (cliText.contains("http://127.0.0.1:8291")) {
            findings += Violation("cli.default_admin_base_8291", rel(cli), "default admin base still points at LettaShim")
        }
        if (cliText.contains("LETTA_IROH_ADMIN_BASE_URL")) {
            findings += Violation("cli.admin_base_env", rel(cli), "generic admin base env still accepted")
        }
        if (cliText.contains("HttpSubagentRegistrySource")) {
            findings += Violation(
                "cli.http_subagent_registry_discover",
                rel(cli),
                "production still references shim HTTP subagent registry",
            )
        }

        val subagentSource = repoRoot.resolve(
            "android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/HttpSubagentRegistrySource.kt",
        )
        if (subagentSource.isRegularFile()) {
            findings += Violation(
                "handlers.http_subagent_registry_source",
                rel(subagentSource),
                "HttpSubagentRegistrySource source file still present",
            )
        }

        val matrix = repoRoot.resolve(
            "android-compose/sharedLogic/src/jvmTest/resources/appserver/iroh-admin-ownership-matrix.json",
        )
        val matrixText = matrix.readText()
        // Count operation rows still on migration-time shim fallback (enum may
        // still list the historical value for schema compatibility).
        val shimFallbackRows = Regex(""""fallback"\s*:\s*"shim_until_cutover"""").findAll(matrixText).count()
        if (shimFallbackRows > 0) {
            findings += Violation(
                "matrix.shim_until_cutover_rows",
                rel(matrix),
                "ownership matrix still declares $shimFallbackRows shim_until_cutover migration fallbacks",
            )
        }

        return findings.sortedBy { it.id }
    }

    private fun rel(path: Path): String = repoRoot.relativize(path).pathString.replace('\\', '/')

    private fun locateRepoRoot(): Path {
        val starts = listOf(
            Path.of("").toAbsolutePath(),
            Path.of("android-compose").toAbsolutePath(),
            Path.of("..").toAbsolutePath(),
            Path.of("../..").toAbsolutePath(),
        )
        for (start in starts) {
            findRepoRootFrom(start)?.let { return it }
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }

    private fun findRepoRootFrom(start: Path): Path? {
        var cur: Path? = start
        while (cur != null) {
            if (isRepoRoot(cur)) {
                return if (Files.isDirectory(cur.resolve("android-compose"))) cur else cur.parent
            }
            cur = cur.parent
        }
        return null
    }

    private fun isRepoRoot(path: Path): Boolean {
        val nestedCompose = path.resolve("android-compose")
        if (Files.isRegularFile(path.resolve("android-compose/settings.gradle.kts"))) return true
        return Files.isRegularFile(path.resolve("settings.gradle.kts")) &&
            Files.isDirectory(nestedCompose)
    }
}
