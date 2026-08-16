package com.letta.mobile.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class DesktopJewelWindowTest {
    @Test
    fun `window host retains Nucleus native decoration contract`() {
        val project = desktopProjectDirectory()
        val source = project.resolve(
            "src/main/kotlin/com/letta/mobile/desktop/DesktopJewelWindow.kt",
        ).readText()
        val build = project.resolve("build.gradle.kts").readText()

        assertContains(source, "DecoratedWindow(")
        assertContains(source, "TitleBar(style = titleBarStyle)")
        assertFalse(source.lineSequence().any { it.trim().startsWith("undecorated = true") })
        assertContains(build, "implementation(\"dev.nucleusframework:nucleus.decorated-window-core:")
        assertContains(build, "implementation(\"dev.nucleusframework:nucleus.decorated-window-awt:")
        assertContains(build, "implementation(\"dev.nucleusframework:nucleus.decorated-window-jni:")
    }
}

internal fun desktopProjectDirectory(): File {
    val workingDirectory = File(System.getProperty("user.dir"))
    return listOf(workingDirectory, workingDirectory.resolve("desktop"))
        .first { candidate -> candidate.resolve("build.gradle.kts").isFile }
}
