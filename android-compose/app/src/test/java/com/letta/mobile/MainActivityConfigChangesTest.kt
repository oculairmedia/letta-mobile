package com.letta.mobile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * letta-mobile-09m53: a rotation, fold or window resize must not recreate MainActivity. Recreation
 * reloaded the mascot and replayed composition state; Compose recomposes for these changes itself.
 */
class MainActivityConfigChangesTest {
    @Test
    fun mainActivityHandlesRotationAndResizeItself() {
        val declared = mainActivity().getAttributeNS(ANDROID_NS, "configChanges").split('|').toSet()
        assertEquals("MainActivity android:configChanges", emptySet<String>(), HANDLED - declared)
    }

    private fun mainActivity(): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File(MANIFEST))
        val activities = manifest.getElementsByTagName("activity")
        return (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(ANDROID_NS, "name") == MainActivity::class.java.name }
    }

    private companion object {
        /** Gradle runs the module's unit tests from the module directory. */
        const val MANIFEST = "src/main/AndroidManifest.xml"
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        val HANDLED = setOf("orientation", "screenSize", "smallestScreenSize", "screenLayout")
    }
}
