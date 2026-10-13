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

        /**
         * letta-mobile-ckmub.1.2: the run block's files (its summary line, its step rows, the tool
         * line, the reasoning row) draw their disclosure only through [DisclosureChevron]: no chevron
         * or arrow glyph of their own, and nothing rotated by hand.
         */
        val RUN_BLOCK_FILES = setOf(
            "ChatRowRunBlock.kt",
            "ChatRowRunSummary.kt",
            "ChatRowRunStep.kt",
            "ChatRowToolRun.kt",
            "ChatRowToolCard.kt",
            "ChatRowToolStatus.kt",
            "ChatRowReasoning.kt",
        )

        private val RUN_BLOCK_ARROW_PATTERN = Regex(
            """(LettaIcons|Lucide|Icons\.[A-Za-z]+)\.(Chevron|Arrow|Expand|KeyboardArrow|NavigateNext)[A-Za-z]*""",
        )

        private val RUN_BLOCK_ARROW_IMPORT = Regex("""import\s+com\.composables\.icons\.lucide\.(Chevron|Arrow)[A-Za-z]*""")

        private val HAND_ROTATION = Regex("""\.rotate\(""")

        fun findRunBlockViolations(content: String): List<String> =
            listOf(RUN_BLOCK_ARROW_PATTERN, RUN_BLOCK_ARROW_IMPORT, HAND_ROTATION)
                .flatMap { pattern -> pattern.findAll(content).map { it.value.trim() } }

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
    fun `no run block file draws a chevron or arrow of its own`() {
        val rows = locateSharedUiDir().resolve("src/commonMain/kotlin/com/letta/mobile/ui/chat/surface/timeline/rows")
        val violations = RUN_BLOCK_FILES.flatMap { name ->
            val file = rows.resolve(name)
            assertTrue(file.isRegularFile(), "run block file moved: $name (update RUN_BLOCK_FILES)")
            findRunBlockViolations(file.readText()).map { "$name: $it" }
        }
        assertTrue(
            violations.isEmpty(),
            "Run block files must disclose only through DisclosureChevron:\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `run block scan fails on planted chevrons and arrows`() {
        listOf(
            """Icon(LettaIcons.ChevronRight, contentDescription = null)""",
            """Icon(Lucide.ArrowUpRight, contentDescription = null)""",
            """import com.composables.icons.lucide.ChevronRight""",
            """Icon(Icons.Default.KeyboardArrowDown, null)""",
            """Modifier.rotate(180f)""",
        ).forEach { planted ->
            assertFalse(findRunBlockViolations(planted).isEmpty(), "run block scan missed:\n$planted")
        }
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
