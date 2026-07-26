package com.letta.mobile.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import dev.nucleusframework.core.runtime.Platform
import java.awt.Window

/**
 * Windows 11 chrome polish for the undecorated main window: ask DWM for the
 * standard rounded corners (which also brings the system hairline outline)
 * that decorated windows get automatically. Best-effort no-op on other
 * platforms and on Windows 10, where DWM ignores the attribute.
 */
internal object DesktopWindowsChrome {
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWCP_ROUND = 2

    fun applyStandardChrome(window: Window) {
        if (Platform.Current != Platform.Windows) return
        runCatching {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            DwmApi.INSTANCE.DwmSetWindowAttribute(
                hwnd,
                DWMWA_WINDOW_CORNER_PREFERENCE,
                IntByReference(DWMWCP_ROUND),
                Int.SIZE_BYTES,
            )
        }.onFailure { error ->
            // Cosmetic-only, so never fatal — but silence made the Windows 10
            // no-op indistinguishable from a broken JNA binding.
            DesktopCrashReporter.logCrash(error, context = "window corner preference")
        }
    }

    @Suppress("FunctionName")
    private interface DwmApi : StdCallLibrary {
        fun DwmSetWindowAttribute(
            hwnd: WinDef.HWND,
            attribute: Int,
            value: IntByReference,
            size: Int,
        ): Int

        companion object {
            val INSTANCE: DwmApi = Native.load("dwmapi", DwmApi::class.java)
        }
    }
}
