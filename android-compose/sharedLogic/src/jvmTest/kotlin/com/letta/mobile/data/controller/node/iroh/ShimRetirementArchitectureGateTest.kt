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
 * Phase 1 architecture freeze for LettaShim retirement.
 *
 * Inventories known production wiring that still depends on the shim / shared
 * admin base / direct-disk tier. The expected set is intentional debt: new
 * violations fail CI immediately; removing a violation requires updating this
 * inventory in the same change (runbook Phase 4/5 clears it to empty).
 *
 * The end-state gate ([shimFreeProductionWiringHasNoKnownViolations]) stays
 * disabled until `SHIM_FREE_ARCHITECTURE_GATE=1` so Phase 2–4 can land without
 * a permanently red suite, while still proving the desired assertions fail
 * against today's tree when that env var is set.
 */
class ShimRetirementArchitectureGateTest {
    private data class Violation(val id: String, val path: String, val detail: String)

    private val repoRoot: Path = locateRepoRoot()

    private val expectedViolationIds = setOf(
        "cli.default_admin_base_8291",
        "cli.admin_base_env",
        "cli.local_backend_dir_env",
        "cli.http_subagent_registry_discover",
        "native.admin_proxy_fallback",
        "registry.local_backend_store",
        "handlers.http_subagent_registry_source",
        "matrix.shim_until_cutover_rows",
    )

    @Test
    fun knownShimRetirementViolationsMatchFrozenInventory() {
        val found = scanViolations().map { it.id }.toSet()
        assertEquals(
            expectedViolationIds,
            found,
            buildString {
                appendLine("Shim-retirement architecture inventory drifted.")
                appendLine("Added: ${found - expectedViolationIds}")
                appendLine("Removed: ${expectedViolationIds - found}")
                appendLine("All findings:")
                scanViolations().forEach { appendLine(" - ${it.id}: ${it.path}: ${it.detail}") }
            },
        )
    }

    @Test
    fun shimFreeProductionWiringHasNoKnownViolations() {
        val enabled = System.getenv("SHIM_FREE_ARCHITECTURE_GATE") == "1"
        val remaining = scanViolations()
        if (!enabled) {
            assertTrue(
                remaining.isNotEmpty(),
                "Expected Phase 1 red-gate debt, but no violations were found",
            )
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
        val cli = repoRoot.resolve(
            "android-compose/cli/src/main/java/com/letta/mobile/cli/commands/AppServerServeIrohCommand.kt",
        )
        val cliText = cli.readText()
        if (cliText.contains("http://127.0.0.1:8291")) {
            findings += Violation("cli.default_admin_base_8291", rel(cli), "default admin base still points at LettaShim")
        }
        if (cliText.contains("LETTA_IROH_ADMIN_BASE_URL")) {
            findings += Violation("cli.admin_base_env", rel(cli), "generic admin base env still accepted")
        }
        if (cliText.contains("LETTA_LOCAL_BACKEND_DIR")) {
            findings += Violation("cli.local_backend_dir_env", rel(cli), "direct-disk backend env still wired")
        }
        if (cliText.contains("HttpSubagentRegistrySource.discover")) {
            findings += Violation(
                "cli.http_subagent_registry_discover",
                rel(cli),
                "production still discovers subagents from shim HTTP",
            )
        }

        val native = repoRoot.resolve(
            "android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/NativeAdminSupport.kt",
        )
        if (native.readText().contains("toRoute = \"shim_http\"")) {
            findings += Violation("native.admin_proxy_fallback", rel(native), "NativeAdmin still falls back to shim_http")
        }

        val registry = repoRoot.resolve(
            "android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/AdminRpcRegistry.kt",
        )
        if (registry.readText().contains("LocalBackendAdminStore")) {
            findings += Violation("registry.local_backend_store", rel(registry), "optional LocalBackendAdminStore still constructed")
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
        if (matrix.readText().contains("\"shim_until_cutover\"")) {
            findings += Violation(
                "matrix.shim_until_cutover_rows",
                rel(matrix),
                "ownership matrix still declares shim_until_cutover migration fallbacks",
            )
        }

        return findings.sortedBy { it.id }
    }

    private fun rel(path: Path): String = repoRoot.relativize(path).pathString.replace('\\', '/')

    private fun locateRepoRoot(): Path {
        val starts = listOf(
            Path.of("").toAbsolutePath(),
            Path.of(System.getProperty("user.dir")).toAbsolutePath(),
        )
        for (start in starts) {
            var cursor: Path? = start
            repeat(8) {
                val current = cursor ?: return@repeat
                val matrix = current.resolve(
                    "android-compose/sharedLogic/src/jvmTest/resources/appserver/iroh-admin-ownership-matrix.json",
                )
                if (matrix.isRegularFile()) return current
                val nested = current.resolve(
                    "sharedLogic/src/jvmTest/resources/appserver/iroh-admin-ownership-matrix.json",
                )
                if (nested.isRegularFile()) return current.parent
                cursor = current.parent
            }
        }
        error("Unable to locate repository root from ${System.getProperty("user.dir")}")
    }
}
