package com.letta.mobile.desktop.avatar.rive

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class RiveDesktopBridgeContractTest {
    @Test
    fun settlementExportIsAdditiveAndCapabilityDetected() {
        val kotlinSource = source("android-compose/desktop/src/main/kotlin/com/letta/mobile/desktop/avatar/rive/RiveDesktopBridge.kt")
        val nativeSource = source("android-compose/avatar/renderer-rive/native/desktop/rive_desktop_bridge.cpp")

        assertEquals("rive_bridge_advance_is_active", BridgeExport.ADVANCE_ACTIVITY.symbol)
        assertContains(kotlinSource, "fun rive_bridge_advance(bridge: Pointer, seconds: Float)")
        assertContains(kotlinSource, "fun riveBridgeAdvanceIsActive(bridge: Pointer, seconds: Float): Int")
        assertContains(kotlinSource, "Library.OPTION_FUNCTION_MAPPER to ADVANCE_ACTIVITY_MAPPER")
        assertContains(kotlinSource, "BridgeExport.ADVANCE_ACTIVITY.symbol else method.name")
        assertContains(kotlinSource, "exports(BridgeExport.ADVANCE_ACTIVITY)")
        assertContains(kotlinSource, "else native.rive_bridge_advance(handle, step.seconds)")
        assertContains(nativeSource, "__declspec(dllexport) void rive_bridge_advance(RiveBridge* bridge, float seconds)")
        assertContains(nativeSource, "__declspec(dllexport) int rive_bridge_advance_is_active(RiveBridge* bridge, float seconds)")
        assertContains(nativeSource, "return bridge->stateMachine && bridge->stateMachine->advanceAndApply(seconds) ? 1 : 0;")
    }

    private fun source(path: String): String {
        val start = File(System.getProperty("user.dir")).absoluteFile
        val file = generateSequence(start) { it.parentFile }
            .map { File(it, path) }
            .firstOrNull(File::isFile)
        return checkNotNull(file) { "Couldn't find $path from ${start.path}" }.readText()
    }
}
