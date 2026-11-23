package com.letta.mobile.brand

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrandIdentityTest {
    private val brand = BrandIdentity.parse(
        """
        brand.name=Acme
        brand.label.debug=Dev
        brand.label.sideloadDebug=Sideload
        brand.icon.svg=branding/source/icon.svg
        brand.icon.png=branding/desktop/icon.png
        brand.icon.ico=branding/desktop/icon.ico
        brand.icon.icns=branding/desktop/icon.icns
        brand.android.res=branding/android
        brand.web.icons=branding/web
        """.trimIndent(),
    )

    @Test
    fun `launcher labels take the variant suffix over the build type suffix`() {
        assertEquals("Acme", brand.androidLabel("playRelease", "release"))
        assertEquals("Acme Dev", brand.androidLabel("rootDebug", "debug"))
        assertEquals("Acme Dev", brand.androidLabel("playDebug", "debug"))
        assertEquals("Acme Sideload", brand.androidLabel("sideloadDebug", "debug"))
        assertEquals("Acme", brand.androidLabel("playBenchmark", "benchmark"))
    }

    @Test
    fun `processed web resources get the name in place of the token`() {
        assertEquals(
            "<title>Acme</title> \"name\": \"Acme\"",
            brand.expand("<title>${BrandIdentity.NAME_TOKEN}</title> \"name\": \"${BrandIdentity.NAME_TOKEN}\""),
        )
    }

    @Test
    fun `the generated runtime object carries the name`() {
        val source = brand.kotlinSource("com.example.brand")
        assertTrue("package com.example.brand" in source, source)
        assertTrue("public const val NAME: String = \"Acme\"" in source, source)
    }

    @Test
    fun `a missing key or a name that would break generated code is rejected`() {
        assertThrows<IllegalArgumentException> { BrandIdentity.parse("brand.name=Acme") }
        assertThrows<IllegalArgumentException> { brand.copy(name = "Ac\"me") }
        assertThrows<IllegalArgumentException> { brand.copy(name = " ") }
    }

    @Test
    fun `the checked-in brand source names the product and every icon it points at exists`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { it.resolve(BrandPlugin.BRAND_FILE).isFile }
        val shipped = BrandIdentity.parse(root.resolve(BrandPlugin.BRAND_FILE).readText())
        assertEquals("Baken", shipped.name)
        assertEquals("Baken Dev", shipped.androidLabel("rootDebug", "debug"))
        assertEquals("Baken Sideload", shipped.androidLabel("sideloadDebug", "debug"))
        listOf(shipped.iconSvg, shipped.iconPng, shipped.iconIco, shipped.iconIcns).forEach { path ->
            assertTrue(root.resolve(path).isFile, "missing brand icon $path")
        }
        listOf("ic_launcher_background", "ic_launcher_foreground", "ic_launcher_monochrome", "ic_notification")
            .forEach { assertTrue(root.resolve("${shipped.androidRes}/drawable/$it.xml").isFile, "missing $it") }
        listOf("favicon.svg", "favicon-16.png", "favicon-32.png", "apple-touch-icon-180.png", "icon-192.png", "icon-512.png")
            .forEach { assertTrue(root.resolve("${shipped.webIcons}/$it").isFile, "missing web icon $it") }
    }
}
