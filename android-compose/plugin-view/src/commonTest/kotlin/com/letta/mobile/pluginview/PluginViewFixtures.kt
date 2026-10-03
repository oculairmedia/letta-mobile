package com.letta.mobile.pluginview

import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import com.letta.mobile.data.canvas.plugin.CanvasPluginFallback
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginContract
import com.letta.mobile.data.plugin.PluginDisplayMode
import com.letta.mobile.data.plugin.PluginElementKind
import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.PluginPage
import com.letta.mobile.data.plugin.PluginPageCsp
import com.letta.mobile.data.plugin.PluginRuntime
import com.letta.mobile.data.plugin.view.PluginViewActionCall
import com.letta.mobile.data.plugin.view.PluginViewActionOutcome
import com.letta.mobile.data.plugin.view.PluginViewPageRef
import com.letta.mobile.data.plugin.view.PluginViewSpec
import com.letta.mobile.data.plugin.view.PluginViewTransport
import com.letta.mobile.data.plugin.view.ViewElement
import com.letta.mobile.data.plugin.view.ViewHostContext
import com.letta.mobile.data.plugin.view.ViewPlatform
import com.letta.mobile.data.plugin.view.ViewTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One plugin with a live page, its elements and a transport for the live-view tests (letta-mobile-s416w.13). */
internal object PluginViewFixtures {
    const val WIDGET_HTML = "<!doctype html><html><head><title>Widget</title></head><body><script>lettaView.ready()</script></body></html>"

    val page = PluginPage(
        html = "pages/widget.html",
        csp = PluginPageCsp(
            connectDomains = listOf("https://api.example.test", "ws://127.0.0.1:8188", "not an origin"),
            resourceDomains = listOf("https://cdn.example.test"),
            frameDomains = listOf("https://*.embed.example.test"),
        ),
        permissions = listOf(PluginCapability.UI_CAMERA, PluginCapability.UI_GEOLOCATION),
        displayModes = listOf(PluginDisplayMode.INLINE),
    )

    val manifest = PluginManifest(
        manifestVersion = 1,
        id = "letta.example",
        name = "Example",
        version = "1.2.0",
        publisher = "oculairmedia",
        contract = PluginContract(1),
        runtime = PluginRuntime.Jvm("plugin.jar", "com.example.ExamplePlugin"),
        settings = emptyMap(),
        capabilities = listOf(PluginCapability.UI_PAGES),
        elements = mapOf(
            "widget" to PluginElementKind(schemaVersion = 1, props = JsonObject(emptyMap()), page = "widget"),
            "plain" to PluginElementKind(schemaVersion = 1, props = JsonObject(emptyMap())),
            "orphan" to PluginElementKind(schemaVersion = 1, props = JsonObject(emptyMap()), page = "missing"),
        ),
        pages = mapOf("widget" to page),
    )

    val element = CanvasPluginElement(
        id = "el-1",
        type = "ext:letta.example/widget",
        frame = CanvasDocumentFrame(10f, 20f, 320f, 240f),
        props = JsonObject(mapOf("status" to JsonPrimitive("idle"))),
        fallback = CanvasPluginFallback(title = "Widget"),
    )

    val spec: PluginViewSpec = checkNotNull(PluginViewSpec.of(manifest, "widget", "canvas-1", ViewElement.of(element)))

    val context = ViewHostContext(
        theme = ViewTheme.DARK,
        cssVariables = mapOf("--md-sys-color-primary" to "#6750a4"),
        element = ViewElement.of(element),
        settingsPublic = JsonObject(emptyMap()),
        displayMode = PluginDisplayMode.INLINE,
        locale = "en-CA",
        platform = ViewPlatform.ANDROID,
    )
}

/** A host that serves [html] for every page and records what it was asked. */
internal class FakePluginViewTransport(private val html: String = PluginViewFixtures.WIDGET_HTML) : PluginViewTransport {
    val pagesRead = mutableListOf<PluginViewPageRef>()

    override suspend fun readPage(page: PluginViewPageRef): ByteArray {
        pagesRead += page
        return html.encodeToByteArray()
    }

    override suspend fun action(call: PluginViewActionCall): PluginViewActionOutcome = PluginViewActionOutcome.Done("ok")
}
