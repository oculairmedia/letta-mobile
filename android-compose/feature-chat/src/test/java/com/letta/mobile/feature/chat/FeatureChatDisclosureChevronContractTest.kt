package com.letta.mobile.feature.chat

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant I1 for feature-chat: disclosure chevrons render only through
 * [com.letta.mobile.ui.components.DisclosureChevron].
 */
class FeatureChatDisclosureChevronContractTest {
    @Test
    fun noDirectDisclosureIconsInFeatureChat() {
        val root = locateFeatureChatMain()
        val violations = mutableListOf<String>()
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && it.name.endsWith(".kt") }.forEach { file ->
                val hits = findViolationsInSource(file.readText())
                if (hits.isNotEmpty()) {
                    violations.add("${file.fileName}: ${hits.joinToString("; ")}")
                }
            }
        }
        assertTrue(
            "Invariant I1 violated in feature-chat:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun plantedLegacyPatternsAreDetected() {
        val planted = listOf(
            """Icon(LettaIcons.ExpandMore, contentDescription = "Expand")""",
            """Icon(imageVector = LettaIcons.ExpandLess, contentDescription = null)""",
            """Icon(imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown, contentDescription = null)""",
            """Icon(Icons.Default.ExpandMore, contentDescription = "More")""",
            """import com.composables.icons.lucide.ChevronDown""",
        )
        planted.forEach { sample ->
            assertFalse(
                "Contract test failed to detect:\n$sample",
                findViolationsInSource(sample).isEmpty(),
            )
        }
    }

    private fun locateFeatureChatMain(): Path {
        var candidate: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (candidate != null) {
            val direct = candidate.resolve("feature-chat/src/main/java")
            if (Files.isDirectory(direct)) return direct
            val nested = candidate.resolve("android-compose/feature-chat/src/main/java")
            if (Files.isDirectory(nested)) return nested
            candidate = candidate.parent
        }
        error("Unable to locate feature-chat sources from ${System.getProperty("user.dir")}")
    }

    companion object {
        private val LEGACY_ICON_PATTERN = Regex(
            """Icon\s*\(.*?(LettaIcons\.(ExpandMore|ExpandLess|ChevronDown|ChevronUp)|Lucide\.(ChevronDown|ChevronUp)|Icons\.[A-Za-z0-9_]+\.Expand)""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val DIRECT_LUCIDE_CHEVRON_IMPORT = Regex(
            """import\s+com\.composables\.icons\.lucide\.Chevron(Down|Up)""",
        )

        fun findViolationsInSource(content: String): List<String> {
            val icons = LEGACY_ICON_PATTERN.findAll(content).map { it.value.trim() }.toList()
            val imports = DIRECT_LUCIDE_CHEVRON_IMPORT.findAll(content).map { it.value.trim() }.toList()
            return icons + imports
        }
    }
}
