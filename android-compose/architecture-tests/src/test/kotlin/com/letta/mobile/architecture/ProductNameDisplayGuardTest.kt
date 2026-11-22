package com.letta.mobile.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.streams.asSequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * letta-mobile-z0ftp: the product is called Baken. No shipped display string may still say "Meridian".
 *
 * Identities that carry the old name stay as they are, because changing them breaks updates or loses data:
 * the Android applicationId, the desktop package/launcher name (`packageName`, which also derives the Windows
 * installer GUID and the single-instance lock), the Linux StartupWMClass, the macOS bundle id, the Windows MSI
 * UpgradeCode, the `meridian://` scheme and the host service names. They are lowercase or listed in
 * [KEPT_IDENTITY_LINES] with the reason, and the identity test below pins them so a display rename cannot move them.
 */
class ProductNameDisplayGuardTest {
    private val root: Path = Path.of(requireNotNull(System.getProperty("architecture.projectRoot")))
    private val compose: Path = root.resolve("android-compose")

    @Test
    fun `Android launcher labels name the product Baken`() {
        assertEquals("Baken", appName("main"))
        assertEquals("Baken Dev", appName("debug"))
        assertEquals("Baken Sideload", appName("sideloadDebug"))
    }

    @Test
    fun `no shipped string resource says Meridian`() {
        val resourceDirs = sourceSets(compose.resolve("app/src")).map { it.resolve("res") } +
            compose.resolve("sharedUI/src/commonMain/composeResources")
        val hits = resourceDirs.flatMap { dir ->
            files(dir) { it.extension == "xml" && it.parent.name.startsWith("values") }
                .flatMap { file -> OLD_NAME.findAll(withoutXmlComments(file.readText())).map { "${relative(file)}: ${it.value}" } }
        }
        check(hits.isEmpty()) { "string resources still say Meridian: $hits" }
    }

    @Test
    fun `no shipped UI string literal says Meridian`() {
        val sourceRoots = listOf(
            "sharedUI/src/commonMain",
            "sharedUI/src/jvmMain",
            "sharedUI/src/androidMain",
            "sharedUI/src/wasmJsMain",
            "desktop/src/main",
            "web/src/wasmJsMain",
            "app/src/main",
            "sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/pairing",
        ).map(compose::resolve)
        val hits = sourceRoots.flatMap { dir ->
            files(dir) { it.extension == "kt" }.flatMap { file -> literalHits(file) }
        }
        check(hits.isEmpty()) { "shipped UI strings still say Meridian: $hits" }
    }

    @Test
    fun `web page title and install manifest name the product Baken`() {
        val web = compose.resolve("web/src/wasmJsMain/resources")
        val title = Regex("<title>(.*)</title>").find(web.resolve("index.html").readText())?.groupValues?.get(1)
        assertEquals("Baken", title)
        val manifest = web.resolve("manifest.webmanifest").readText()
        assertEquals("Baken", jsonString(manifest, "name"))
        assertEquals("Baken", jsonString(manifest, "short_name"))
    }

    @Test
    fun `desktop packaging displays Baken`() {
        val gradle = compose.resolve("desktop/build.gradle.kts")
        val text = gradle.readText()
        assertEquals("Baken", gradleString(text, "appName"))
        assertEquals("Baken", gradleString(text, "dockName"))
        check(gradleString(text, "artifactName")?.startsWith("baken-") == true) {
            "installer/AppImage file names must start with baken-, found ${gradleString(text, "artifactName")}"
        }
        val hits = gradle.readLines().withIndex()
            .filter { (_, line) -> line.trim() !in KEPT_IDENTITY_LINES && !isComment(line) }
            .filter { (_, line) -> STRING_LITERAL.findAll(line).any { OLD_NAME.containsMatchIn(it.value) } }
            .map { (index, line) -> "desktop/build.gradle.kts:${index + 1}: ${line.trim()}" }
        check(hits.isEmpty()) { "desktop packaging still displays Meridian: $hits" }
        val release = root.resolve(".github/workflows/release.yml").readText()
        check("name=\"baken-\${ARTIFACT_TAG}-linux-x64.AppImage\"" in release) {
            "the released Linux AppImage must be named baken-<tag>-linux-x64.AppImage"
        }
    }

    @Test
    fun `desktop install and update identities keep the old name`() {
        val text = compose.resolve("desktop/build.gradle.kts").readText()
        assertEquals("Meridian Desktop", gradleString(text, "packageName"))
        assertEquals("Meridian Desktop", gradleString(text, "startupWMClass"))
        assertEquals("44e25263-67d4-443c-b85c-655a41118add", gradleString(text, "upgradeUuid"))
        assertEquals("ca.oculair.meridian.desktop", gradleString(text, "bundleID"))
        check("-Dnucleus.app.id=ca.oculair.meridian.desktop" in text) { "the Linux Nucleus app id must not move" }
        check("protocol(\"Baken\", \"meridian\")" in text) { "the deep-link scheme stays meridian://" }
    }

    private fun appName(sourceSet: String): String? {
        val strings = compose.resolve("app/src/$sourceSet/res/values/strings.xml").readText()
        return Regex("""<string name="app_name">([^<]*)</string>""").find(strings)?.groupValues?.get(1)
    }

    private fun literalHits(file: Path): List<String> = file.readLines().withIndex()
        .filterNot { (_, line) -> isComment(line) }
        .flatMap { (index, line) ->
            STRING_LITERAL.findAll(line).filter { OLD_NAME.containsMatchIn(it.value) }
                .map { "${relative(file)}:${index + 1}: ${it.value}" }
        }

    private fun sourceSets(src: Path): List<Path> =
        Files.list(src).use { listing -> listing.filter { it.isDirectory() && !it.name.contains("test", ignoreCase = true) }.toList() }

    private fun files(dir: Path, accept: (Path) -> Boolean): List<Path> =
        if (!dir.isDirectory()) {
            emptyList()
        } else {
            Files.walk(dir).use { walk -> walk.asSequence().filter { Files.isRegularFile(it) && accept(it) }.toList() }
        }

    private fun relative(file: Path): String = compose.relativize(file).invariantSeparatorsPathString

    private fun isComment(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private fun withoutXmlComments(xml: String): String = xml.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private fun jsonString(json: String, key: String): String? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)

    private fun gradleString(gradle: String, key: String): String? =
        Regex("""(?m)^\s*${Regex.escape(key)}\s*=\s*(?:\$\$)?"((?:[^"\\]|\\.)*)"""").find(gradle)?.groupValues?.get(1)

    private companion object {
        val OLD_NAME = Regex("""\bMeridian\b""")
        val STRING_LITERAL = Regex(""""(?:[^"\\]|\\.)*"""")

        /** Identity, not display: renaming either orphans existing installs (see the class KDoc). */
        val KEPT_IDENTITY_LINES = setOf(
            // jpackage launcher/.cfg name, electron-builder appId (Windows NSIS install GUID), npm name, single-instance lock.
            "packageName = \"Meridian Desktop\"",
            // Ties running windows to the installed Linux launcher.
            "startupWMClass = \"Meridian Desktop\"",
        )
    }
}
