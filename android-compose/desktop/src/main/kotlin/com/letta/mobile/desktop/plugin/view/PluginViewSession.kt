package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.data.plugin.view.ViewBridge
import com.letta.mobile.data.plugin.view.ViewElement
import kotlinx.coroutines.Job
import java.awt.Component
import java.util.concurrent.atomic.AtomicBoolean

/** The native side of one live view: the component the board shows, and how to release it. */
internal interface PluginBrowserHandle {
    val component: Component

    /** Closes the browser and frees what it holds; called once, after the bridge's teardown. */
    fun dispose()
}

/**
 * One live view from open to close: the [bridge] reads the page for as long as the view lives,
 * and [close] ends it in order: `host.teardown` to a ready page (it gets the bridge's 2 s to
 * answer), then the port stops reading, then the browser is disposed. Closing twice does nothing.
 */
internal class PluginViewSession(
    private val bridge: ViewBridge,
    private val port: JcefPostMessagePort,
    private val handle: PluginBrowserHandle,
) {
    private val closed = AtomicBoolean(false)
    private var reader: Job? = null

    val component: Component get() = handle.component

    val isClosed: Boolean get() = closed.get()

    /** Starts reading the page's messages as [work]. */
    fun start(work: PluginViewWork) {
        if (reader == null && !closed.get()) reader = work.launch { bridge.run() }
    }

    /** Tells a ready page its element changed (`host.element.changed`); nothing once the view is closing. */
    suspend fun elementChanged(element: ViewElement): Boolean = !closed.get() && bridge.elementChanged(element)

    /**
     * Ends the view for [reason] (`closed`, …): teardown, then the port, then the browser. True when a
     * ready page acknowledged the teardown in time; false when it did not, or the view was closed already.
     */
    suspend fun close(reason: String): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        try {
            return bridge.teardown(reason)
        } finally {
            port.close()
            reader?.cancel()
            handle.dispose()
        }
    }
}
