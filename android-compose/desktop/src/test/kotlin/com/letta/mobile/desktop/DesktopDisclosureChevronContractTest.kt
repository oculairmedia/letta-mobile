package com.letta.mobile.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Invariant I1 for the desktop shell: disclosure chevrons render only through
 * [com.letta.mobile.ui.components.DisclosureChevron]. Paging chevrons are outside
 * this pattern.
 */
class DesktopDisclosureChevronContractTest {
    @Test
    fun noDirectDisclosureIconsInDesktop() {
        val violations = scan(locateModuleMain())
        assertTrue(
            violations.isEmpty(),
            "Invariant I1 violated in desktop:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun plantedLegacyPatternsAreDetected() {
        listOf(
            """Icon(imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)""",
            """import androidx.compose.material.icons.outlined.ExpandMore""",
            """import com.composables.icons.lucide.ChevronDown""",
        ).forEach { sample ->
            assertFalse(
                findViolationsInSource(sample).isEmpty(),
                "Contract test failed to detect:\n$sample",
            )
        }
    }

    private fun scan(root: Path): List<String> {
        val violations = mutableListOf<String>()
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && it.name.endsWith(".kt") }.forEach { file ->
                val hits = findViolationsInSource(file.readText())
                if (hits.isNotEmpty()) {
                    violations.add("${file.fileName}: ${hits.joinToString("; ")}")
                }
            }
        }
        return violations
    }

    private fun locateModuleMain(): Path {
        var candidate: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (candidate != null) {
            val direct = candidate.resolve("desktop/src/main/kotlin")
            if (Files.isDirectory(direct)) return direct
            val nested = candidate.resolve("android-compose/desktop/src/main/kotlin")
            if (Files.isDirectory(nested)) return nested
            candidate = candidate.parent
        }
        error("Unable to locate desktop sources from ${System.getProperty("user.dir")}")
    }

    private fun findViolationsInSource(content: String): List<String> {
        val icons = LEGACY_ICON_PATTERN.findAll(content).map { it.value.trim() }.toList()
        val imports = DIRECT_CHEVRON_IMPORT.findAll(content).map { it.value.trim() }.toList()
        return icons + imports
    }

    companion object {
        private val LEGACY_ICON_PATTERN = Regex(
            """Icon\s*\(.*?(LettaIcons\.(ExpandMore|ExpandLess|ChevronDown|ChevronUp)|Lucide\.(ChevronDown|ChevronUp)|Icons\.[A-Za-z0-9_]+\.Expand)""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val DIRECT_CHEVRON_IMPORT = Regex(
            """import\s+(com\.composables\.icons\.lucide\.Chevron(Down|Up)|androidx\.compose\.material\.icons\.[A-Za-z0-9_.]*Expand(More|Less))""",
        )
    }
}
