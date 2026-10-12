package com.letta.mobile.ui.shell.scaffold

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Shared pages never pad for the system bars or the keyboard themselves: [LettaPageScaffold] owns
 * those insets exactly once (letta-mobile-ckmub.2.1). A page that calls `statusBarsPadding()` and
 * friends either pads twice (outside a scaffold that already consumed them it is a no-op, inside a
 * host that did not it double-counts) or opts out of edge to edge.
 *
 * [ALLOWLIST] names the files that still pad for themselves and why; it only shrinks. An entry whose
 * file no longer pads fails the ratchet test, so a fix also removes its entry.
 */
class PageInsetModifierGuardTest {

    companion object {
        /**
         * File name -> why it still pads itself. Containers only, not pages. The chat page left this
         * list in slice S4 (letta-mobile-ckmub.1): ChatSurface renders in the scaffold and its layers
         * lift by its keyboard inset (ChatSurfaceInsets).
         */
        val ALLOWLIST: Map<String, String> = mapOf(
            "ModelControlModal.kt" to "modal bottom sheet container pads its own content above the navigation bar",
        )

        private val PER_PAGE_INSET_MODIFIER = Regex(
            """\.(statusBarsPadding|navigationBarsPadding|systemBarsPadding|safeDrawingPadding|safeContentPadding|imePadding|displayCutoutPadding)\s*\(\s*\)""" +
                """|windowInsetsPadding\s*\(\s*WindowInsets\.(statusBars|navigationBars|systemBars|ime)\s*\)""",
        )

        fun findViolations(source: String): List<String> =
            PER_PAGE_INSET_MODIFIER.findAll(source).map { it.value }.toList()

        /** The scaffold itself is the one place that applies them. */
        private const val OWNER = "LettaPageScaffold.kt"
    }

    @Test
    fun `no sharedUI page pads for system bars or the keyboard itself`() {
        val violations = scanProductionSources()
            .filterKeys { it !in ALLOWLIST }
        assertTrue(
            violations.isEmpty(),
            "Shared pages must render inside LettaPageScaffold instead of padding for insets themselves:\n" +
                violations.entries.joinToString("\n") { (file, hits) -> "$file: ${hits.joinToString()}" },
        )
    }

    @Test
    fun `allowlist only shrinks`() {
        val stale = ALLOWLIST.keys - scanProductionSources().keys
        assertTrue(stale.isEmpty(), "These files no longer pad for insets; remove them from ALLOWLIST: $stale")
    }

    @Test
    fun `guard detects every planted per-page inset modifier`() {
        listOf(
            "Modifier.statusBarsPadding()",
            "Modifier.fillMaxSize().navigationBarsPadding()",
            "Modifier.systemBarsPadding()",
            "Modifier.imePadding()",
            "Modifier.safeDrawingPadding()",
            "Modifier.windowInsetsPadding(WindowInsets.statusBars)",
        ).forEach { planted ->
            assertTrue(findViolations(planted).isNotEmpty(), "Guard missed: $planted")
        }
        assertTrue(findViolations("// see statusBarsPadding docs").isEmpty())
    }

    private fun scanProductionSources(): Map<String, List<String>> {
        val sharedUi = locateSharedUiDir()
        val roots = listOf("commonMain", "jvmMain", "androidMain", "skikoMain", "wasmJsMain")
            .map { sharedUi.resolve("src/$it/kotlin") }
            .filter { Files.isDirectory(it) }
        assertTrue(roots.isNotEmpty(), "sharedUI production sources not found under $sharedUi")
        val hits = mutableMapOf<String, List<String>>()
        roots.forEach { root ->
            Files.walk(root).use { stream ->
                stream.filter { it.isRegularFile() && it.name.endsWith(".kt") && it.name != OWNER }.forEach { file ->
                    val found = findViolations(file.readText())
                    if (found.isNotEmpty()) hits[file.name] = found
                }
            }
        }
        return hits
    }

    private fun locateSharedUiDir(): Path {
        var candidate: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (candidate != null) {
            if (candidate.fileName?.toString() == "sharedUI") return candidate
            val direct = candidate.resolve("sharedUI")
            if (Files.isDirectory(direct)) return direct
            val nested = candidate.resolve("android-compose/sharedUI")
            if (Files.isDirectory(nested)) return nested
            candidate = candidate.parent
        }
        error("Unable to locate sharedUI from ${System.getProperty("user.dir")}")
    }
}
