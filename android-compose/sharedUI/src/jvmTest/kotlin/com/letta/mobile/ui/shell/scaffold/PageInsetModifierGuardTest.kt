package com.letta.mobile.ui.shell.scaffold

import com.letta.mobile.ui.guard.SharedUiSourceScan
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
            "LettaPaneDefaults.kt" to "the shared bottom-sheet pane container (S3 LettaHost) pads its own content above the navigation bar",
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

    private fun scanProductionSources(): Map<String, List<String>> =
        SharedUiSourceScan.scan(skip = setOf(OWNER), find = ::findViolations)
}
