package com.letta.mobile.desktop

import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window
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

    fun hideWindow() {
        EventQueue.invokeLater { windowRef.get()?.isVisible = false }
    }
}
