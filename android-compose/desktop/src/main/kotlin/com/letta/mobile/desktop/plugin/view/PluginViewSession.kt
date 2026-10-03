package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.data.plugin.view.ViewBridge
import com.letta.mobile.data.plugin.view.ViewBridgeState
import com.letta.mobile.data.plugin.view.ViewElement
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Component
import java.util.concurrent.atomic.AtomicBoolean

/** The native side of one live view: the component the board shows, and how to release it. */
internal interface PluginBrowserHandle {
    val component: Component

    /** Closes the browser and frees what it holds; called once, after the bridge's teardown. */
    fun dispose()
}

/**
 * How long a page has to send `view.ready` before its element goes back to the card, and who is
 * told. It catches every way a page can fail to start (a load that fails before it commits, a
 * script error, a page that never calls `ready()`) without telling them apart.
 */
internal class PluginReadyWatch(val timeoutMs: Long, val onStalled: (String) -> Unit) {
    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 15_000L
        const val STALLED: String = "the page did not start"
    }
}

/**
 * One live view from open to close: the [bridge] reads the page for as long as the view lives,
 * the [watch] gives it a deadline for `view.ready`, and [close] ends it in order: `host.teardown`
 * to a ready page (it gets the bridge's 2 s to answer), then the port stops reading, then the
 * browser is disposed. Closing twice does nothing.
 */
internal class PluginViewSession(
    private val bridge: ViewBridge,
    private val port: JcefPostMessagePort,
    private val handle: PluginBrowserHandle,
    private val watch: PluginReadyWatch? = null,
) {
    private val closed = AtomicBoolean(false)
    private val jobs = mutableListOf<Job>()

    val component: Component get() = handle.component

    val isClosed: Boolean get() = closed.get()

    /** Starts reading the page's messages, and the ready watch, as [work]. */
    fun start(work: PluginViewWork) {
        if (jobs.isNotEmpty() || closed.get()) return
        jobs += work.launch { bridge.run() }
        watch?.let { jobs += work.launch { watchReady(it) } }
    }

    private suspend fun watchReady(watch: PluginReadyWatch) {
        val settled = withTimeoutOrNull(watch.timeoutMs) { bridge.state.first { it != ViewBridgeState.AWAITING_READY } }
        if (settled == null && !closed.get()) watch.onStalled(PluginReadyWatch.STALLED)
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
            jobs.forEach(Job::cancel)
            handle.dispose()
        }
    }
}
