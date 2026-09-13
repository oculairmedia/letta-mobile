package com.letta.mobile.debug

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RiveDebugStartupTest {
    @Test
    fun nativeInitializationPrecedesComposeWorkerCreation() {
        // JVM tests cannot load the Android JNI renderer. Guard the startup ordering here;
        // the device bench verifies the native renderer and its authored state machine.
        val source = File("src/debug/java/com/letta/mobile/debug/MascotDebugActivity.kt").readText()
        val initialization = source.indexOf("app.rive.runtime.kotlin.core.Rive.init(applicationContext)")
        val composition = source.indexOf("setContent {")
        assertTrue("Rive JNI must be initialized before the first Compose worker", initialization >= 0 && composition > initialization)
    }
}
