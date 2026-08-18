package com.letta.mobile.desktop

import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicReference

internal class DesktopWindowActivationHandler {
    private val windowRef = AtomicReference<Window?>()

    fun attach(window: Window) {
        windowRef.set(window)
    }

    fun showUserThatAppIsRunning() {
        EventQueue.invokeLater {
            val window = windowRef.get() ?: return@invokeLater
            window.isVisible = true
            if (window is Frame) {
                window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
            }
            window.toFront()
            window.requestFocus()
        }
    }

    /**
     * Hides the window to the tray (background agents/schedules keep running;
     * Quit remains available from the tray menu — see Main.kt).
     *
     * letta-mobile-scedm: previously this set `isVisible = false` immediately,
     * which made the window vanish with no transition — part of what read as
     * "does not animate down nicely" even after native decoration restored
     * real minimize/restore animations elsewhere. Now that the window has a
     * genuine native frame (DesktopJewelWindow.kt), iconifying first lets DWM
     * play its standard minimize-to-taskbar animation; the window is hidden
     * only once that transition actually completes (`windowIconified`),
     * matching how native close-to-tray apps (e.g. Slack, Discord) animate
     * away instead of snapping to nothing.
     */
    fun hideWindow() {
        EventQueue.invokeLater {
            val window = windowRef.get() ?: return@invokeLater
            val frame = window as? Frame
            if (frame != null && frame.extendedState and Frame.ICONIFIED == 0) {
                frame.addWindowListener(
                    object : WindowAdapter() {
                        override fun windowIconified(e: WindowEvent) {
                            frame.removeWindowListener(this)
                            frame.isVisible = false
                        }
                    },
                )
                frame.extendedState = frame.extendedState or Frame.ICONIFIED
            } else {
                window.isVisible = false
            }
        }
    }
}
