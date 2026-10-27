package com.letta.mobile.desktop.avatar.rive

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class RiveDesktopBridgeContractTest {
    @Test
    fun settlementExportIsAdditiveAndCapabilityDetected() {
        val kotlinSource = source("android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/avatar/rive/RiveDesktopBridge.kt") +
            source("android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/avatar/rive/RiveDesktopScene.kt")
        val nativeSource = source("android-compose/avatar/renderer-rive/native/desktop/rive_desktop_bridge.cpp")

        assertEquals("rive_bridge_advance_is_active", BridgeExport.ADVANCE_ACTIVITY.symbol)
        assertContains(kotlinSource, "fun rive_bridge_advance(bridge: Pointer, seconds: Float)")
        assertContains(kotlinSource, "fun riveBridgeAdvanceIsActive(bridge: Pointer, seconds: Float): Int")
        assertContains(kotlinSource, "Library.OPTION_FUNCTION_MAPPER to ADVANCE_ACTIVITY_MAPPER")
        assertContains(kotlinSource, "BridgeExport.ADVANCE_ACTIVITY.symbol else method.name")
        assertContains(kotlinSource, "exports(BridgeExport.ADVANCE_ACTIVITY)")
        assertContains(kotlinSource, "else native.rive_bridge_advance(handle, step.seconds)")
        assertContains(nativeSource, "RIVE_BRIDGE_EXPORT void rive_bridge_advance(RiveBridge* bridge, float seconds)")
        assertContains(nativeSource, "RIVE_BRIDGE_EXPORT int rive_bridge_advance_is_active(RiveBridge* bridge, float seconds)")
        assertContains(nativeSource, "return bridge->stateMachine && bridge->stateMachine->advanceAndApply(seconds) ? 1 : 0;")
    }

    @Test
    fun linuxAndWindowsChooseTheirOwnLibraryAndExplicitOverridesAreAuthoritative() {
        val root = kotlin.io.path.createTempDirectory("rive-loader").toFile()
        try {
            val linux = File(root, "librive_desktop_bridge.so").apply { writeBytes(byteArrayOf(1)) }
            val windows = File(root, "rive_desktop_bridge.dll").apply { writeBytes(byteArrayOf(2)) }
            assertEquals(linux.absolutePath, RiveBridgeNative.findPath("Linux", null, root.path, "/missing"))
            assertEquals(windows.absolutePath, RiveBridgeNative.findPath("Windows 11", null, root.path, "/missing"))
            assertEquals(null, RiveBridgeNative.findPath("Mac OS X", null, root.path, root.path))
            assertEquals(null, RiveBridgeNative.findPath("Linux", null, File(root, "missing-package").path, root.path))
            kotlin.test.assertFailsWith<IllegalStateException> {
                RiveBridgeNative.findPath("Linux", File(root, "missing.so").path, root.path, root.path)
            }
            assertEquals(windows.absolutePath, RiveBridgeNative.findPath("Linux", windows.path, root.path, root.path))
            linux.delete()
            assertEquals(null, RiveBridgeNative.findPath("Linux", null, root.path, root.path))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun source(path: String): String {
        val start = File(System.getProperty("user.dir")).absoluteFile
        val file = generateSequence(start) { it.parentFile }
            .map { File(it, path) }
            .firstOrNull(File::isFile)
        return checkNotNull(file) { "Couldn't find $path from ${start.path}" }.readText()
    }
}
