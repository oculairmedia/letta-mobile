package com.letta.mobile.desktop.hidpi

import androidx.compose.ui.configureSwingGlobalsForCompose
import androidx.compose.ui.ExperimentalComposeUiApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxAwtScaleTest {
    private val properties = listOf("os.name", "sun.java2d.uiScale", "skiko.linux.autodpi")
    private lateinit var original: Map<String, String?>

    @BeforeTest
    fun saveProperties() {
        original = properties.associateWith(System::getProperty)
    }

    @AfterTest
    fun restoreProperties() {
        original.forEach { (key, value) ->
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `selected Linux scale survives Compose and Skiko initialization`() {
        System.setProperty("os.name", "Linux")
        System.setProperty("sun.java2d.uiScale", "2")
        System.setProperty("skiko.linux.autodpi", "true")

        LinuxAwtScale().run()
        // This is the production Compose startup path that loads Skia and used to
        // replace the selected scale with XWayland's Xft scale of 1.
        configureSwingGlobalsForCompose()

        assertEquals("false", System.getProperty("skiko.linux.autodpi"))
        assertEquals("2", System.getProperty("sun.java2d.uiScale"))
    }

    @Test
    fun `other platforms retain their scale configuration`() {
        System.setProperty("os.name", "Windows 11")
        System.setProperty("sun.java2d.uiScale", "1.5")
        System.setProperty("skiko.linux.autodpi", "true")

        LinuxAwtScale().run()

        assertEquals("true", System.getProperty("skiko.linux.autodpi"))
        assertEquals("1.5", System.getProperty("sun.java2d.uiScale"))
    }
}
