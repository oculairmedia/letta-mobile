package com.letta.mobile.data.plugin

import com.letta.mobile.data.plugin.PluginRegistryFixtures.SHA_A
import com.letta.mobile.data.plugin.PluginRegistryFixtures.json
import com.letta.mobile.data.plugin.PluginRegistryFixtures.manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The package layout (plan section 3.1): manifest at the root, the runtime's files, pages, paths inside. */
class PluginPackagesTest {
    private val REF = PluginPackageRef(SHA_A)

    private fun files(manifest: PluginManifest, vararg paths: String): Map<String, ByteArray> =
        mapOf(PluginPackagePaths.MANIFEST to PluginManifestParser.encode(manifest).encodeToByteArray()) + paths.associateWith { it.encodeToByteArray() }

    private fun codes(result: PluginPackageResult): List<String> =
        assertIs<PluginPackageResult.Refused>(result).problems.map { "${it.code} ${it.entry}" }

    @Test
    fun aJvmPackageHasItsJarAndPages() {
        val pkg = assertIs<PluginPackageResult.Read>(PluginPackages.assemble(REF, files(PluginRegistryFixtures.example, "plugin.jar", "pages/widget.html"))).pkg
        assertEquals(PluginPayload.Jar("plugin.jar"), pkg.layout.payload)
        assertEquals(mapOf("widget" to "pages/widget.html"), pkg.layout.pages)
        assertEquals("sha256:$SHA_A", pkg.ref.toString())
        assertEquals("pages/widget.html", pkg.file("pages/widget.html")?.decodeToString())
        assertTrue(pkg.toString().startsWith("PluginPackage(letta.example 1.2.0"))
    }

    @Test
    fun missingFilesAreRefusedByName() {
        assertEquals(
            listOf("MISSING_FILE pages/widget.html", "MISSING_FILE plugin.jar"),
            codes(PluginPackages.assemble(REF, files(PluginRegistryFixtures.example))),
        )
        assertEquals(listOf("NO_MANIFEST null"), codes(PluginPackages.assemble(REF, mapOf("plugin.jar" to ByteArray(1)))))
    }

    @Test
    fun aProcessPackageCarriesEverythingOutsideTheManifestAndPages() {
        val process = manifest("/runtime" to json("""{"kind":"process","command":"./bin/run","cwd":"bin"}"""))
        val pkg = assertIs<PluginPackageResult.Read>(PluginPackages.assemble(REF, files(process, "bin/run", "bin/lib.js", "pages/widget.html"))).pkg
        assertEquals(PluginPayload.ProcessFiles(listOf("bin/lib.js", "bin/run")), pkg.layout.payload)
        assertEquals(
            listOf("MISSING_FILE bin/run", "MISSING_FILE bin/"),
            codes(PluginPackages.assemble(REF, files(process, "lib.js", "pages/widget.html"))),
        )
    }

    @Test
    fun aServicePackageCarriesOnlyItsManifestAndPages() {
        val service = manifest("/runtime" to json("""{"kind":"service","url":"wss://h.example.test/lcp"}"""))
        assertEquals(PluginPayload.None, assertIs<PluginPackageResult.Read>(PluginPackages.assemble(REF, files(service, "pages/widget.html"))).pkg.layout.payload)
        assertEquals(listOf("UNEXPECTED_FILE plugin.jar"), codes(PluginPackages.assemble(REF, files(service, "pages/widget.html", "plugin.jar"))))
    }

    @Test
    fun anUnsafePathOrABadManifestIsRefused() {
        assertEquals(listOf("UNSAFE_PATH ../evil"), codes(PluginPackages.assemble(REF, files(PluginRegistryFixtures.example, "../evil"))))
        val bad = mapOf(PluginPackagePaths.MANIFEST to """{"id":"x"}""".encodeToByteArray())
        assertTrue(codes(PluginPackages.assemble(REF, bad)).all { it == "BAD_MANIFEST letta-plugin.json" })
    }

    @Test
    fun pathsStayInsideThePackage() {
        listOf("plugin.jar", "pages/a.html", "bin/run").forEach { assertTrue(PluginPackagePaths.isInside(it), it) }
        listOf("", "/etc/passwd", "../x", "a/../b", "a//b", "./a", "a\\b", "C:x", "a/./b").forEach { assertFalse(PluginPackagePaths.isInside(it), it) }
        assertTrue(PluginPackagePaths.isPage("pages/widget.html"))
        assertFalse(PluginPackagePaths.isPage("pages/widget.js"))
        assertFalse(PluginPackagePaths.isPage("widget.html"))
    }

    @Test
    fun packageRefsAreSha256() {
        assertEquals(PluginPackageRef(SHA_A), PluginPackageRef.parse("sha256:$SHA_A"))
        assertNull(PluginPackageRef.parse(SHA_A))
        assertNull(PluginPackageRef.parse("sha256:ABC"))
        assertEquals(50L * 1024 * 1024, PluginPackageLimits.MAX_PACKAGE_BYTES)
    }

    @Test
    fun originsAreSchemeHostAndPortOnly() {
        assertEquals(PluginOrigin("https", "api.example.test", null), PluginOrigin.parse("https://api.example.test"))
        assertEquals(PluginOrigin("ws", "127.0.0.1", 8188), PluginOrigin.parse("ws://127.0.0.1:8188"))
        assertTrue(PluginOrigin.parse("https://*.example.test")!!.isWildcard)
        assertEquals("wss://[::1]:9000", PluginOrigin.parse("wss://[::1]:9000").toString())
        listOf("https://a.test/", "https://a.test/x", "ftp://a.test", "https://*", "https://*.test", "https://a.*.test", "https://u@a.test", "https://a.test:0", "https://a.test:70000", "HTTPS://A.TEST")
            .forEach { assertNull(PluginOrigin.parse(it), it) }
    }

    @Test
    fun versionsCompareBySemverPrecedence() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.10.0", "2.0.0")
            .map { PluginSemVer.parse(it)!! }
        assertEquals(ordered, ordered.shuffled().sorted())
        assertEquals(0, PluginSemVer.parse("1.0.0+build.1")!!.compareTo(PluginSemVer.parse("1.0.0")!!))
        assertNull(PluginSemVer.parse("1.0"))
    }
}
