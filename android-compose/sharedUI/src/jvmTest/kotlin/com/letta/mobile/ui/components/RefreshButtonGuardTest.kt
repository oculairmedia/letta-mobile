package com.letta.mobile.ui.components

import com.letta.mobile.ui.guard.SharedUiSourceScan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Shared pages never draw their own refresh button (letta-mobile-ckmub.3.21): a list reloads through
 * [LettaRefreshable] - pull-to-refresh on touch - and its header's [LettaRefreshAction], the one
 * refresh icon, shown on pointer hosts only. A refresh glyph anywhere else in sharedUI is a phone
 * refresh button in the making.
 *
 * [ALLOWLIST] names the files that still use the refresh glyph and why; it only shrinks. An entry
 * whose file no longer uses it fails the ratchet test, so a fix also removes its entry.
 */
class RefreshButtonGuardTest {

    companion object {
        /** File name -> why it still shows the refresh glyph. None of these reloads a page's list. */
        val ALLOWLIST: Map<String, String> = mapOf(
            "MessageMenu.kt" to "the message menu's \"Rerun in fork\" item: a rerun, not a reload",
            "A2uiBasicWidgets.kt" to "the A2UI icon catalog maps the agent-authored name \"Refresh\" to the glyph",
            "ModelPickerContent.kt" to "the model picker's \"Refresh Models\" footer lives in a sheet whose drag dismisses it",
        )

        private val REFRESH_GLYPH = Regex("""LettaIcons\.Refresh\b|Icons\.\w+\.Refresh\b|import androidx\.compose\.material\.icons\.\w+\.Refresh\b""")

        fun findViolations(source: String): List<String> = REFRESH_GLYPH.findAll(source).map { it.value }.toList()

        /** The primitive itself draws the one shared refresh button. */
        private const val OWNER = "LettaRefreshable.kt"
    }

    @Test
    fun `no sharedUI page draws its own refresh button`() {
        val violations = scanProductionSources().filterKeys { it !in ALLOWLIST }
        assertTrue(
            violations.isEmpty(),
            "Reload a page's list through LettaRefreshable / LettaRefreshAction, not a refresh button of its own:\n" +
                violations.entries.joinToString("\n") { (file, hits) -> "$file: ${hits.joinToString()}" },
        )
    }

    @Test
    fun `allowlist only shrinks`() {
        val stale = ALLOWLIST.keys - scanProductionSources().keys
        assertTrue(stale.isEmpty(), "These files no longer use the refresh glyph; remove them from ALLOWLIST: $stale")
    }

    @Test
    fun `guard detects every planted refresh glyph`() {
        listOf(
            "Icon(LettaIcons.Refresh, null)",
            "Icon(Icons.Outlined.Refresh, contentDescription = \"Refresh\")",
            "import androidx.compose.material.icons.filled.Refresh",
        ).forEach { planted -> assertTrue(findViolations(planted).isNotEmpty(), "Guard missed: $planted") }
        assertTrue(findViolations("LettaIcons.RefreshCw2 // not ours").isEmpty())
    }

    private fun scanProductionSources(): Map<String, List<String>> =
        SharedUiSourceScan.scan(skip = setOf(OWNER), find = ::findViolations)
}
