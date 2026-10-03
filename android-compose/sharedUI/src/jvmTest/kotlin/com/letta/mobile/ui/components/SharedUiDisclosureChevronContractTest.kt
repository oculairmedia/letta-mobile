package com.letta.mobile.ui.components

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Enforces Invariant I1 for the sharedUI module:
 * No production source in sharedUI renders a disclosure-role Icon directly
 * outside [DisclosureChevron.kt].
 *
 * The temporary allowlist is empty after letta-mobile-eohab.4.
 */
class SharedUiDisclosureChevronContractTest {

    companion object {
        /**
         * Kept as an explicit empty set so a reintroduced bypass fails this gate.
         */
        val TEMPORARY_ALLOWLIST = emptySet<String>()

        private val LEGACY_ICON_PATTERN = Regex(
            """Icon\s*\(.*?(LettaIcons\.(ExpandMore|ExpandLess|ChevronDown|ChevronUp)|Lucide\.(ChevronDown|ChevronUp)|Icons\.[A-Za-z0-9_]+\.Expand)""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

        private val DIRECT_LUCIDE_CHEVRON_IMPORT = Regex(
            """import\s+com\.composables\.icons\.lucide\.Chevron(Down|Up)""",
        )

        fun findViolationsInSource(content: String): List<String> {
            val iconViolations = LEGACY_ICON_PATTERN.findAll(content).map { it.value.trim() }.toList()
            val importViolations = DIRECT_LUCIDE_CHEVRON_IMPORT.findAll(content).map { it.value.trim() }.toList()
            return iconViolations + importViolations
        }
    }

    private fun shouldScanFile(file: Path): Boolean {
        if (!file.isRegularFile() || !file.name.endsWith(".kt")) return false
        val name = file.name
        // DisclosureChevron is the one allowed renderer. LettaIcons is the glyph
        // source. A2uiBasicWidgets is the A2UI catalog (name → vector), not a
        // disclosure rendering site — leave it out so nobody "fixes" the catalog
        // into this scan.
        if (name == "DisclosureChevron.kt" || name == "LettaIcons.kt" || name == "A2uiBasicWidgets.kt") {
            return false
        }
        return !TEMPORARY_ALLOWLIST.contains(name)
    }

    @Test
    fun `no unapproved disclosure icons in sharedUI production sources`() {
        val sharedUiDir = locateSharedUiDir()
        val prodSourceRoots = listOf(
            sharedUiDir.resolve("src/commonMain/kotlin"),
            sharedUiDir.resolve("src/jvmMain/kotlin"),
            sharedUiDir.resolve("src/androidMain/kotlin"),
        ).filter { Files.isDirectory(it) }

        assertTrue(prodSourceRoots.isNotEmpty(), "At least one production source root must exist")

        val violations = mutableListOf<String>()

        prodSourceRoots.forEach { root ->
            Files.walk(root).use { stream ->
                stream.filter(::shouldScanFile).forEach { file ->
                    val fileViolations = findViolationsInSource(file.readText())
                    if (fileViolations.isNotEmpty()) {
                        violations.add("${file.fileName}: ${fileViolations.joinToString("; ")}")
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Invariant I1 violated! Found direct disclosure Icon usages outside DisclosureChevron.kt:\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `sharedUI disclosure allowlist is empty`() {
        assertTrue(
            TEMPORARY_ALLOWLIST.isEmpty(),
            "TEMPORARY_ALLOWLIST must stay empty after letta-mobile-eohab.4. Remaining: $TEMPORARY_ALLOWLIST",
        )
    }

    @Test
    fun `contract test fails on planted legacy patterns`() {
        val plantedPatterns = listOf(
            """Icon(LettaIcons.ExpandMore, contentDescription = "Expand")""",
            """Icon(imageVector = LettaIcons.ExpandLess, contentDescription = null)""",
            """Icon(imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown, contentDescription = null)""",
            """Icon(Icons.Default.ExpandMore, contentDescription = "More")""",
            """Icon(Icons.Outlined.ExpandLess, contentDescription = null)""",
            """import com.composables.icons.lucide.ChevronDown""",
            """import com.composables.icons.lucide.ChevronUp""",
        )

        plantedPatterns.forEach { planted ->
            val detected = findViolationsInSource(planted)
            assertFalse(
                detected.isEmpty(),
                "Contract test failed to detect planted legacy pattern:\n$planted",
            )
        }
    }

    private fun locateSharedUiDir(): Path {
        var candidate: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (candidate != null) {
            val direct = candidate.resolve("sharedUI")
            if (Files.isDirectory(direct)) return direct
            val sub = candidate.resolve("android-compose/sharedUI")
            if (Files.isDirectory(sub)) return sub
            if (candidate.fileName?.toString() == "sharedUI") return candidate
            candidate = candidate.parent
        }
        error("Unable to locate sharedUI directory from ${System.getProperty("user.dir")}")
    }
}
