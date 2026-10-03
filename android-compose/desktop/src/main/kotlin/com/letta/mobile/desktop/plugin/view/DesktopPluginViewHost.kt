package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.view.PluginViewSpec
import com.letta.mobile.data.plugin.view.ViewBridge
import com.letta.mobile.data.plugin.view.ViewBridgeServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.cef.CefApp
import org.cef.browser.CefBrowser
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
 * [JcefPostMessagePort], and is torn down (bridge first, browser after) on [close], which runs in
 * the host's own scope so it finishes after the board that showed it is gone.
 */
internal class DesktopPluginViewHost(
    val runtime: BrowserRuntime<CefApp>,
    private val scope: CoroutineScope,
) {
    fun open(app: CefApp, live: PluginLiveView, onFault: (String) -> Unit): PluginViewSession {
        val server = PluginPageServer(live.spec, live.services.transport, live.consented)
        val target = AtomicReference<CefBrowser?>()
        val port = JcefPostMessagePort { script -> target.get()?.executeJavaScript(script, server.url, 0) }
        val wiring = JcefViewWiring(
            server = server,
            policy = PluginViewRequestPolicy(live.spec.pageRef, live.spec.page.csp),
            queries = PluginViewQueryRouter(live.spec.pageRef, port),
            scope = scope,
            onFault = onFault,
        )
        val browser = JcefPluginBrowser.open(app, wiring)
        target.set(browser.browser)
        return PluginViewSession(ViewBridge(live.spec, port, live.services), port, browser).also { it.start(scope) }
    }

    fun close(session: PluginViewSession, reason: String) {
        scope.launch { session.close(reason) }
    }
}

/** The desktop app's live-view host: one per process, its browser started only when a live view first shows. */
internal object DesktopPluginViews {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val host: DesktopPluginViewHost by lazy {
        val config = JcefConfig.fromSystem()
        DesktopPluginViewHost(BrowserRuntime(JcefAppStarter(config), scope, disabledReason = config.disabledReason), scope)
    }
}

/** Why a view was torn down, as `host.teardown { reason }` tells the page. */
internal object PluginViewTeardown {
    /** The element left the board, or the board was closed. */
    const val CLOSED: String = "closed"
}
