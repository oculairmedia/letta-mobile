package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.view.PluginViewSpec
import com.letta.mobile.data.plugin.view.ViewBridge
import com.letta.mobile.data.plugin.view.ViewBridgeServices
import org.cef.CefApp
import org.cef.browser.CefBrowser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * One element's live view as this desktop can show it: the bridge's [spec] and [services] (the
 * host, the transport, the link policy, consent) and the page permissions the person allowed here.
 */
class PluginLiveView(
    val spec: PluginViewSpec,
    val services: ViewBridgeServices,
    val consented: Set<PluginCapability> = emptySet(),
)

/**
 * Which plugin elements have a live view on this desktop: the element's plugin is enabled on the
 * host, its kind names a page, and the transport can reach it. Null draws the fallback card.
 */
fun interface PluginLiveViewSource {
    fun liveViewOf(element: CanvasPluginElement, canvasId: String): PluginLiveView?

    companion object {
        /** No live views: every element is its card. */
        val None: PluginLiveViewSource = PluginLiveViewSource { _, _ -> null }
    }
}

/**
 * Opens and closes the desktop's live views (letta-mobile-s416w.14) on the one shared [runtime].
 * Each view gets its own JCEF client and request context, a [ViewBridge] over a
 * [JcefPostMessagePort], and is torn down (bridge first, browser after) on [close], which runs as
 * the host's [work] so it finishes after the board that showed it is gone.
 */
internal class DesktopPluginViewHost(
    val runtime: BrowserRuntime<CefApp>,
    private val work: PluginViewWork,
) {
    private val sessions = ConcurrentHashMap.newKeySet<PluginViewSession>()

    /** How many views are open now (opened and not yet fully closed). */
    val openViews: Int get() = sessions.size

    fun open(app: CefApp, live: PluginLiveView, onFault: (String) -> Unit): PluginViewSession {
        val server = PluginPageServer(live.spec, live.services.transport, live.consented)
        val target = AtomicReference<CefBrowser?>()
        val port = JcefPostMessagePort(PageScriptRunner { script -> target.get()?.executeJavaScript(script, server.url, 0) })
        val wiring = JcefViewWiring(
            server = server,
            policy = PluginViewRequestPolicy(live.spec.pageRef, live.spec.page.csp),
            queries = PluginViewQueryRouter(live.spec.pageRef, port),
            work = work,
            onFault = onFault,
        )
        val browser = JcefPluginBrowser.open(app, wiring)
        target.set(browser.browser)
        val watch = PluginReadyWatch(PluginReadyWatch.DEFAULT_TIMEOUT_MS, onFault)
        val session = PluginViewSession(ViewBridge(live.spec, port, live.services), port, browser, watch)
        sessions += session
        session.start(work)
        return session
    }

    fun close(session: PluginViewSession, reason: String) {
        work.launch {
            try {
                session.close(reason)
            } finally {
                sessions -= session
            }
        }
    }
}

/** The process's embedded browser: one per process (CEF initialises once), started only when a live view first shows. */
internal object DesktopPluginViews {
    val runtime: BrowserRuntime<CefApp> by lazy {
        val config = JcefConfig.fromSystem()
        BrowserRuntime(JcefAppStarter(config), disabledReason = config.disabledReason)
    }
}

/** Why a view was torn down, as `host.teardown { reason }` tells the page. */
internal object PluginViewTeardown {
    /** The element left the board, or the board was closed. */
    const val CLOSED: String = "closed"
}
