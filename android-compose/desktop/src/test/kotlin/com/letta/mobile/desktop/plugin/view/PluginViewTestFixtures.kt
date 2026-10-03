package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.plugin.CanvasPluginFallback
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginDisplayMode
import com.letta.mobile.data.plugin.PluginPage
import com.letta.mobile.data.plugin.PluginPageCsp
import com.letta.mobile.data.plugin.view.PluginViewActionCall
import com.letta.mobile.data.plugin.view.PluginViewActionOutcome
import com.letta.mobile.data.plugin.view.PluginViewPageRef
import com.letta.mobile.data.plugin.view.PluginViewSpec
import com.letta.mobile.data.plugin.view.PluginViewTransport
import com.letta.mobile.data.plugin.view.ViewBridgeHost
import com.letta.mobile.data.plugin.view.ViewElement
import com.letta.mobile.data.plugin.view.ViewHostContext
import com.letta.mobile.data.plugin.view.ViewLink
import com.letta.mobile.data.plugin.view.ViewLogLevel
import com.letta.mobile.data.plugin.view.ViewPlatform
import com.letta.mobile.data.plugin.view.ViewTheme
import kotlinx.serialization.json.JsonObject
import java.awt.Component
import java.awt.Panel
import java.util.concurrent.CopyOnWriteArrayList

/** A plugin page, a host and a transport for the desktop live-view tests (letta-mobile-s416w.14). */
internal object PluginViewTestFixtures {
    val element = ViewElement(
        id = "el-1",
        type = "ext:letta.example/widget",
        v = 1,
        frame = CanvasDocumentFrame(10f, 20f, 320f, 240f),
        fallback = CanvasPluginFallback(title = "Widget"),
    )

    val page = PluginPage(
        html = "pages/widget.html",
        csp = PluginPageCsp(
            connectDomains = listOf("https://api.example.com"),
            resourceDomains = listOf("https://*.cdn.example.com", "javascript:alert(1)"),
            frameDomains = listOf("https://embed.example.com"),
        ),
        permissions = listOf(PluginCapability.UI_CAMERA, PluginCapability.UI_CLIPBOARD_WRITE),
    )

    fun spec(page: PluginPage = this.page): PluginViewSpec = PluginViewSpec(
        pluginId = "letta.example",
        pluginVersion = "1.2.0+build.7",
        pageId = "widget",
        canvasId = "canvas-1",
        elementId = element.id,
        elementType = element.type,
        page = page,
        viewActions = emptyMap(),
    )

    val pageRef: PluginViewPageRef get() = spec().pageRef

    val pageUrl: String get() = PluginViewScheme.urlOf(pageRef)

    val context = ViewHostContext(
        theme = ViewTheme.DARK,
        cssVariables = mapOf("--md-sys-color-primary" to "#6750a4"),
        element = element,
        settingsPublic = JsonObject(emptyMap()),
        displayMode = PluginDisplayMode.INLINE,
        locale = "en-CA",
        platform = ViewPlatform.DESKTOP,
    )
}

/** A transport that serves [html] (or fails with [failure]) and answers every action done. */
internal class FakePageTransport(
    private val html: String = "<!doctype html><html><head><title>w</title></head><body>widget</body></html>",
    var failure: Throwable? = null,
) : PluginViewTransport {
    val reads = CopyOnWriteArrayList<PluginViewPageRef>()

    override suspend fun readPage(page: PluginViewPageRef): ByteArray {
        reads += page
        failure?.let { throw it }
        return html.encodeToByteArray()
    }

    override suspend fun action(call: PluginViewActionCall): PluginViewActionOutcome = PluginViewActionOutcome.Done("ok")
}

/** A host that answers the fixture context and keeps the page's log lines. */
internal class RecordingViewHost : ViewBridgeHost {
    val logs = CopyOnWriteArrayList<String>()

    override fun context(): ViewHostContext = PluginViewTestFixtures.context

    override fun onResize(width: Double, height: Double) = Unit

    override suspend fun requestDisplayMode(mode: PluginDisplayMode): PluginDisplayMode = mode

    override suspend fun openLink(link: ViewLink) = Unit

    override fun onLog(level: ViewLogLevel, message: String) {
        logs += message
    }
}

/** A browser handle that only counts its disposals. */
internal class FakeBrowserHandle(private val onDispose: () -> Unit = {}) : PluginBrowserHandle {
    var disposals = 0
        private set

    override val component: Component = Panel()

    override fun dispose() {
        disposals++
        onDispose()
    }
}
