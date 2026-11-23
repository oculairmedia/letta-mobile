package com.letta.mobile.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readLines
import kotlin.io.path.writeText

/**
 * Colour lives in the theme. A `Color(0xAARRGGBB)` literal or a `"#RRGGBB"` string in the shared UI
 * (sharedUI commonMain) or the Android design system (designsystem main) outside the token and theme
 * definition files bypasses the palette: it is how the old teal survived the brand change. Such a
 * colour must become a token (LettaColorTokens, a theme role, CustomColors) or, when it is artwork or
 * content rather than theme, a documented entry in [ALLOWED].
 */
class BrandHygieneGuardTest {
    private val projectRoot: Path = Path.of(requireNotNull(System.getProperty("architecture.projectRoot")))

    @Test
    fun `shared UI and the design system name no colour outside the theme`() {
        val hits = HardcodedColourScan.hits(projectRoot, SCANNED_ROOTS, ALLOWED.keys)
        assertEquals(emptyList<String>(), hits) {
            "hard-coded colours outside the theme; use a token or theme role instead:\n${hits.joinToString("\n")}"
        }
    }

    @Test
    fun `every allowlisted file still exists and still holds a colour literal`() {
        val stale = ALLOWED.keys.filterNot { path ->
            val file = projectRoot.resolve(path)
            Files.isRegularFile(file) && HardcodedColourScan.lineHits(file).isNotEmpty()
        }
        assertEquals(emptyList<String>(), stale) { "stale allowlist entries, delete them from ALLOWED: $stale" }
    }

    @Test
    fun `the scan flags a literal colour and a hex string but not comments or arithmetic`(@TempDir root: Path) {
        val dir = Files.createDirectories(root.resolve("ui/src/commonMain/kotlin"))
        dir.resolve("Bad.kt").writeText(
            """
            package x
            // Color(0xFF00BFA5) in a comment is fine
            val a = Color(0xFF00BFA5)
            val b = "#1DE9B6"
            val c = Color(0xFF000000 or rgb)
            """.trimIndent(),
        )
        dir.resolve("Allowed.kt").writeText("val d = Color(0xFF123456)")

        val hits = HardcodedColourScan.hits(root, listOf("ui/src/commonMain"), setOf("ui/src/commonMain/kotlin/Allowed.kt"))

        assertEquals(listOf("ui/src/commonMain/kotlin/Bad.kt:3", "ui/src/commonMain/kotlin/Bad.kt:4"), hits)
    }

    @Test
    fun `user-visible strings name the product, not Letta or Meridian`() {
        val hits = ProductNameScan.hits(projectRoot, PRODUCT_NAME_ROOTS, PRODUCT_NAME_ALLOWED_FILES.keys)
        assertEquals(emptyList<String>(), hits) {
            "user-visible strings with an old product name; use DisplayNames.PRODUCT:\n${hits.joinToString("\n")}"
        }
    }

    @Test
    fun `the product-name scan reads strings and resources but not comments, KDoc or platform names`(@TempDir root: Path) {
        val dir = Files.createDirectories(root.resolve("ui/src/commonMain"))
        dir.resolve("Copy.kt").writeText(
            """
            package x
            /** Letta and Meridian in KDoc are fine. */
            // as is a comment naming Meridian
            val a = "Open Meridian on your phone"
            val b = "Runs the bundled Letta Code runtime against the Letta server"
            val c = "Letta Desktop"
            """.trimIndent(),
        )
        dir.resolve("strings.xml").writeText(
            """
            <resources>
                <string name="ok">Baken</string>
                <string name="bad">Letta update</string>
            </resources>
            """.trimIndent(),
        )

        val hits = ProductNameScan.hits(root, listOf("ui/src/commonMain"), emptySet())

        assertEquals(listOf("ui/src/commonMain/Copy.kt:4", "ui/src/commonMain/Copy.kt:6", "ui/src/commonMain/strings.xml:3"), hits)
    }

    private companion object {
        val PRODUCT_NAME_ROOTS = listOf(
            "android-compose/sharedUI/src/commonMain",
            "android-compose/app/src/main/res",
            "android-compose/desktop/src/main",
        )

        /** Files whose old-name strings are not product copy, each with why. */
        val PRODUCT_NAME_ALLOWED_FILES: Map<String, String> = mapOf(
            "android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/chat/DesktopChatModels.kt" to
                "a sample tool call's on-disk log path (%LOCALAPPDATA%\\Letta), a real folder name",
        )

        val SCANNED_ROOTS = listOf(
            "android-compose/sharedUI/src/commonMain",
            "android-compose/designsystem/src/main",
        )

        private const val SHARED_THEME = "android-compose/sharedUI/src/commonMain/kotlin/com/letta/mobile/ui"
        private const val DESIGN_THEME = "android-compose/designsystem/src/main/java/com/letta/mobile/ui/theme"

        /** The files that may name a colour, each with why. Keep it short: a new entry needs a reason here. */
        val ALLOWED: Map<String, String> = mapOf(
            // Token and theme definitions: where colours are supposed to be written.
            "$SHARED_THEME/theme/SharedMaterialTheme.kt" to "the brand scheme and its custom colours",
            "$SHARED_THEME/theme/PresetThemeColors.kt" to "the colour presets' schemes",
            "$SHARED_THEME/theme/tokens/LettaContextPalette.kt" to "the context breakdown's fixed per-section hues",
            "$DESIGN_THEME/ComponentTokens.kt" to "the voice wave's artwork gradient",
            // Identity and artwork: not theme roles, the same in every theme.
            "$SHARED_THEME/theme/AgentOrbPalette.kt" to "agent mascot identity gradients and the sphere's gloss",
            // Canvas content: colours a person (or a peer) picked for what they drew, stored as hex.
            "$SHARED_THEME/canvas/CanvasPaletteColor.kt" to "the canvas drawing palette",
            "$SHARED_THEME/canvas/CanvasLocalPresence.kt" to "canvas peer cursor colours",
            "$SHARED_THEME/canvas/CanvasSamples.kt" to "sample canvas documents' content colours",
            "$SHARED_THEME/canvas/CanvasNotesLayer.kt" to "the canvas's transparent 'no colour' sentinel",
        )
    }
}

/** Finds colour literals (`Color(0x...)` with a bare literal, `"#RRGGBB[AA]"`) in Kotlin sources. */
internal object HardcodedColourScan {
    private val COLOUR_LITERAL = Regex("""\bColor\(\s*0x[0-9A-Fa-f]{8}L?\s*\)""")
    private val HEX_STRING = Regex(""""#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?"""")

    /** `path:line` of every hit under [roots] (relative to [projectRoot]) outside [allowed] files. */
    fun hits(projectRoot: Path, roots: List<String>, allowed: Set<String>): List<String> = roots
        .map(projectRoot::resolve)
        .filter(Files::isDirectory)
        .flatMap { root -> Files.walk(root).use { walk -> walk.filter { it.toString().endsWith(".kt") }.toList() } }
        .map { file -> projectRoot.relativize(file).invariantSeparatorsPathString to file }
        .filterNot { (relative, _) -> relative in allowed }
        .sortedBy { it.first }
        .flatMap { (relative, file) -> lineHits(file).map { line -> "$relative:$line" } }

    /** 1-based numbers of the lines in [file] that name a colour, comment lines excepted. */
    fun lineHits(file: Path): List<Int> = file.readLines().withIndex()
        .filterNot { (_, line) -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
        .filter { (_, line) -> COLOUR_LITERAL.containsMatchIn(line) || HEX_STRING.containsMatchIn(line) }
        .map { it.index + 1 }
}

/**
 * Finds the old product names (Letta, Meridian) in user-visible text: Kotlin string literals and Android/Compose
 * string resources. Comment and KDoc lines are skipped, and phrases that name the Letta platform this app is a
 * client of ([PLATFORM_PHRASES]) are not the product's name.
 */
internal object ProductNameScan {
    private val OLD_NAME = Regex("""\b(Letta|Meridian)\b""")
    private val KOTLIN_STRING = Regex(""""(?:[^"\\]|\\.)*"""")
    private val RESOURCE_TEXT = Regex(""">([^<]*)<""")
    private val PLATFORM_PHRASES = listOf(
        "Letta Code", "Letta server", "Letta backend", "Letta agent", "Letta AI platform", "Letta models", "Letta-hosted",
    )

    fun hits(projectRoot: Path, roots: List<String>, allowed: Set<String>): List<String> = roots
        .map(projectRoot::resolve)
        .filter(Files::isDirectory)
        .flatMap { root ->
            Files.walk(root).use { walk -> walk.filter { it.toString().endsWith(".kt") || it.toString().endsWith(".xml") }.toList() }
        }
        .map { file -> projectRoot.relativize(file).invariantSeparatorsPathString to file }
        .filterNot { (relative, _) -> relative in allowed }
        .sortedBy { it.first }
        .flatMap { (relative, file) -> lineHits(file).map { line -> "$relative:$line" } }

    private fun lineHits(file: Path): List<Int> {
        val strings = if (file.toString().endsWith(".kt")) KOTLIN_STRING else RESOURCE_TEXT
        return file.readLines().withIndex()
            .filterNot { (_, line) -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
            .filter { (_, line) -> strings.findAll(line).any { names(it.value) } }
            .map { it.index + 1 }
    }

    private fun names(text: String): Boolean =
        OLD_NAME.containsMatchIn(PLATFORM_PHRASES.fold(text) { remaining, phrase -> remaining.replace(phrase, "") })
}
