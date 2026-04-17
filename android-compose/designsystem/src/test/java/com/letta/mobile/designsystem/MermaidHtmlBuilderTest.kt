package com.letta.mobile.designsystem

import android.util.Base64
import com.letta.mobile.ui.components.buildMermaidHtml
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests for the Mermaid HTML wrapper.
 *
 * These tests guard the invariants we rely on for offline rendering and
 * theme-aware output of [com.letta.mobile.ui.components.MermaidDiagram]:
 *
 * - The bundled `mermaid.min.js` is referenced via a relative URL so the
 *   WebView can intercept it and serve it from module assets without needing
 *   `file://` or network access.
 * - The mermaid source is Base64-embedded (not string-interpolated) so arbitrary
 *   user input — including backticks, quotes, newlines, `</script>` — cannot
 *   break out of the host HTML.
 * - The mermaid config `theme` token flips to `dark` in dark mode.
 * - The surface background color is passed through as CSS.
 */
class MermaidHtmlBuilderTest {

    @Before
    fun stubBase64() {
        // android.util.Base64 is a stub in unit-test classpath; route to JDK.
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder()
                .encodeToString(firstArg<ByteArray>())
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `references mermaid_min_js via relative URL so WebView intercept can serve it`() {
        val html = buildMermaidHtml(
            source = "graph TD\nA-->B",
            dark = false,
            backgroundArgb = 0xFFFFFFFF.toInt(),
            foregroundArgb = 0xFF000000.toInt(),
        )
        assertTrue(
            "Expected <script src=\"mermaid.min.js\"> (relative)",
            html.contains("src=\"mermaid.min.js\""),
        )
        assertFalse(
            "Should not embed mermaid from CDN or file://",
            html.contains("http://") || html.contains("https://cdn") || html.contains("file://"),
        )
    }

    @Test
    fun `passes dark theme token when isDark is true`() {
        val darkHtml = buildMermaidHtml(
            source = "graph TD\nA-->B",
            dark = true,
            backgroundArgb = 0xFF000000.toInt(),
            foregroundArgb = 0xFFFFFFFF.toInt(),
        )
        assertTrue(darkHtml.contains("theme: 'dark'"))
    }

    @Test
    fun `passes default theme token in light mode`() {
        val lightHtml = buildMermaidHtml(
            source = "graph TD\nA-->B",
            dark = false,
            backgroundArgb = 0xFFFFFFFF.toInt(),
            foregroundArgb = 0xFF000000.toInt(),
        )
        assertTrue(lightHtml.contains("theme: 'default'"))
    }

    @Test
    fun `source is base64 encoded - not interpolated - to prevent breakout`() {
        val adversarial = "graph TD\nA-->B`';</script><script>alert(1)</script>"
        val html = buildMermaidHtml(
            source = adversarial,
            dark = false,
            backgroundArgb = 0xFFFFFFFF.toInt(),
            foregroundArgb = 0xFF000000.toInt(),
        )
        assertFalse(
            "Raw adversarial source must not appear verbatim in HTML",
            html.contains("</script><script>alert(1)</script>"),
        )
        assertTrue(
            "Should still contain an atob() call that decodes the source",
            html.contains("atob("),
        )
    }

    @Test
    fun `uses strict security level to block network fetches and external resources`() {
        val html = buildMermaidHtml(
            source = "graph TD\nA-->B",
            dark = false,
            backgroundArgb = 0xFFFFFFFF.toInt(),
            foregroundArgb = 0xFF000000.toInt(),
        )
        assertTrue(html.contains("securityLevel: 'strict'"))
    }

    @Test
    fun `background and foreground argbs are emitted as CSS hex`() {
        val html = buildMermaidHtml(
            source = "graph TD\nA-->B",
            dark = false,
            // ARGB 0xFFAABBCC → rgb(aa, bb, cc) → CSS #aabbcc
            backgroundArgb = 0xFFAABBCC.toInt(),
            foregroundArgb = 0xFF112233.toInt(),
        )
        assertTrue("Expected background CSS #aabbcc", html.contains("background: #aabbcc"))
        assertTrue("Expected foreground CSS #112233", html.contains("color: #112233"))
    }
}
