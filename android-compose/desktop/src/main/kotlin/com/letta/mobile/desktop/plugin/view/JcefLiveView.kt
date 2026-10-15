package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.data.plugin.view.ViewBridgeState
import com.letta.mobile.ui.canvas.plugin.PluginViewSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
 * One shared [PluginViewSession] shown in a JCEF browser, from open to close: the session's bridge
 * reads the page and the [port] delivers its answers for as long as the view lives, the [watch]
 * gives the page a deadline for `view.ready`, and [close] ends it in the order the shared host
 * contract asks: `host.teardown` to a ready page (it gets the bridge's 2 s to answer), then the
 * session's page channel, then the browser. Closing twice does nothing.
 */
internal class JcefLiveView(
    private val session: PluginViewSession,
    private val port: JcefPostMessagePort,
    private val browser: PluginBrowserHandle,
    private val watch: PluginReadyWatch? = null,
) {
    private val closed = AtomicBoolean(false)
    private val jobs = mutableListOf<Job>()

    val component: Component get() = browser.component

    val isClosed: Boolean get() = closed.get()

    /** Starts the bridge, the delivery to the page, and the ready watch, as [work]. */
    fun start(work: PluginViewWork) {
        if (jobs.isNotEmpty() || closed.get()) return
        jobs += work.launch { session.bridge.run() }
        jobs += work.launch { port.deliver() }
        watch?.let { jobs += work.launch { watchReady(it) } }
    }

    private suspend fun watchReady(watch: PluginReadyWatch) {
        val settled = withTimeoutOrNull(watch.timeoutMs) { session.bridge.state.first { it != ViewBridgeState.AWAITING_READY } }
        if (settled == null && !closed.get()) watch.onStalled(PluginReadyWatch.STALLED)
    }

    /**
     * Ends the view for [reason] (`closed`, …): teardown, then the channel, then the browser. True when
     * a ready page acknowledged the teardown in time; false when it did not, or the view was closed already.
     */
    suspend fun close(reason: String): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        try {
            return session.bridge.teardown(reason)
        } finally {
            port.close()
            jobs.forEach(Job::cancel)
            browser.dispose()
        }
    }
}

/**
 * Opens and closes the desktop's live views (letta-mobile-s416w.14) on the one shared [runtime].
 * Each view gets its own browser from [browsers] (in production its own JCEF client and request
 * context), wired to the session's page channel through a [JcefPostMessagePort], and is torn down
 * (bridge first, browser after) on [close], which runs as [work] so it finishes after the board
 * that showed it is gone.
 */
internal class JcefLiveViews<T : Any>(
    val runtime: BrowserRuntime<T>,
    private val work: PluginViewWork,
    private val browsers: PluginBrowserFactory<T>,
    private val readyTimeoutMs: Long = PluginReadyWatch.DEFAULT_TIMEOUT_MS,
) {
    private val views = ConcurrentHashMap.newKeySet<JcefLiveView>()

    /** How many views are open now (opened and not yet fully closed). */
    val openViews: Int get() = views.size

    /** Opens [session] in a new browser on [app] and starts it; [onFault] hears why the page cannot be shown. */
    fun open(app: T, session: PluginViewSession, onFault: (String) -> Unit): JcefLiveView {
        val server = PluginPageServer(session)
        val page = AtomicReference<PageScriptRunner?>()
        val port = JcefPostMessagePort(session.page) { script -> page.get()?.run(script) }
        val wiring = JcefViewWiring(server, PluginViewQueryRouter(server.url, port), work, onFault)
        val browser = browsers.open(app, wiring)
        page.set(browser)
        val view = JcefLiveView(session, port, browser, PluginReadyWatch(readyTimeoutMs, onFault))
        views += view
        view.start(work)
        return view
    }

    fun close(view: JcefLiveView, reason: String) {
        work.launch {
            try {
                view.close(reason)
            } finally {
                views -= view
            }
        }
    }
}
