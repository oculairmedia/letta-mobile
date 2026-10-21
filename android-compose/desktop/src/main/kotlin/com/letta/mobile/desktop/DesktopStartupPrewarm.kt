package com.letta.mobile.desktop

import com.letta.mobile.desktop.canvas.DesktopNotebookCanvasStore
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeHost
import com.letta.mobile.desktop.touch.DesktopWindowsTouchKeyboard
import dev.nucleusframework.core.runtime.Platform

/**
 * Startup work that needs no UI thread, begun from main() on its own daemon thread so it overlaps
 * creating the window and its graphics device instead of running inside the first composition on
 * the Swing EDT. Each step is a lazy value or an object initialiser the first composition would
 * otherwise run itself; done here first, the EDT finds it ready, or waits only for what is left of
 * it. Nothing about what is built changes, only the thread and the moment.
 */
internal object DesktopStartupPrewarm : java.lang.Runnable {
    fun start() {
        Thread(this, "startup-prewarm").apply { isDaemon = true }.start()
    }

    override fun run() {
        // A failure here is not reported here: the app meets it where it first uses that piece.
        runCatching { desktopConfigStore }
        runCatching { DesktopLocalRuntimeHost.appServer }
        runCatching { DesktopNotebookCanvasStore.documents }
        if (Platform.Current == Platform.Windows) runCatching { DesktopWindowsTouchKeyboard }
    }
}
