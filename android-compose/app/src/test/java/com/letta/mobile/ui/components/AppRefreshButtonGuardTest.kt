package com.letta.mobile.ui.components

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * letta-mobile-ckmub.3.23: on a phone every refresh button is a pull. The Android app's screens
 * reload a list through the shared LettaRefreshable (pull-to-refresh on touch), never a refresh
 * button in a top bar; sharedUI has the same guard (RefreshButtonGuardTest).
 *
 * [ALLOWLIST] names the app files that still use the refresh glyph and why; it only shrinks.
 */
@Tag("unit")
class AppRefreshButtonGuardTest {

    @Test
    fun noAppScreenDrawsARefreshButton() {
        val violations = scan().filterKeys { it !in ALLOWLIST }
        assertTrue(
            "Reload through LettaRefreshable (a pull on touch), not a refresh button:\n" +
                violations.entries.joinToString("\n") { (file, hits) -> "$file: ${hits.joinToString()}" },
            violations.isEmpty(),
        )
    }

    @Test
    fun allowlistOnlyShrinks() {
        val stale = ALLOWLIST.keys - scan().keys
        assertTrue("These files no longer use the refresh glyph; remove them from ALLOWLIST: $stale", stale.isEmpty())
    }

    @Test
    fun guardDetectsEveryPlantedRefreshGlyph() {
        listOf(
            "IconButton(onClick = viewModel::refresh) { Icon(LettaIcons.Refresh, \"Refresh\") }",
            "Icon(Icons.Outlined.Refresh, contentDescription = null)",
            "import androidx.compose.material.icons.filled.Refresh",
        ).forEach { planted -> assertTrue("Guard missed: $planted", findViolations(planted).isNotEmpty()) }
        assertTrue(findViolations("LettaIcons.RefreshCw2").isEmpty())
    }

    private fun scan(): Map<String, List<String>> {
        val hits = mutableMapOf<String, List<String>>()
        Files.walk(locateAppMain()).use { stream ->
            stream.filter { it.isRegularFile() && it.name.endsWith(".kt") }.forEach { file ->
                val found = findViolations(file.readText())
                if (found.isNotEmpty()) hits[file.name] = found
            }
        }
        return hits
    }

    private fun locateAppMain(): Path {
        var candidate: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (candidate != null) {
            val direct = candidate.resolve("app/src/main/java")
            if (Files.isDirectory(direct)) return direct
            val nested = candidate.resolve("android-compose/app/src/main/java")
            if (Files.isDirectory(nested)) return nested
            candidate = candidate.parent
        }
        error("Unable to locate app sources from ${System.getProperty("user.dir")}")
    }

    private companion object {
        /** File name -> why it still shows the refresh glyph. None of these reloads a list. */
        val ALLOWLIST: Map<String, String> = mapOf(
            "ConversationAdminDialog.kt" to "the conversation inspector's \"Recompile\" item: a rebuild of the agent's context, not a reload",
        )

        private val REFRESH_GLYPH =
            Regex("""LettaIcons\.Refresh\b|Icons\.\w+\.Refresh\b|import androidx\.compose\.material\.icons\.\w+\.Refresh\b""")

        fun findViolations(source: String): List<String> = REFRESH_GLYPH.findAll(source).map { it.value }.toList()
    }
}
