package com.letta.mobile.desktop.startup

/**
 * Startup work that needs no UI thread, begun from main() on its own daemon thread so it overlaps
 * creating the window and its graphics device instead of running inside the first composition on
 * the Swing EDT: the vault-encrypted settings and config, the bundled-runtime host, the notebook
 * store, and (Windows) the touch keyboard's COM setup. Each [steps] entry touches a lazy value or
 * an object initialiser the first composition would otherwise run itself; done here first, the EDT
 * finds it ready, or waits only for what is left of it. Nothing about what is built changes, only
 * the thread and the moment.
 */
internal class DesktopStartupPrewarm(private vararg val steps: () -> Unit) : java.lang.Runnable {
    fun start() {
        Thread(this, "startup-prewarm").apply { isDaemon = true }.start()
    }

    override fun run() {
        // A failure here is not reported here: the app meets it where it first uses that piece.
        steps.forEach { step -> runCatching(step) }
    }
}
