package com.letta.mobile.data.plugin.view

import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.plugin.CanvasPluginFallback
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginDisplayMode
import com.letta.mobile.data.plugin.PluginPage
import com.letta.mobile.data.plugin.PluginRegistryFixtures
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A page, a host and a transport for the view bridge tests (letta-mobile-s416w.31). */
internal object ViewBridgeFixtures {
    val element = ViewElement(
        id = "el-1",
        type = "ext:letta.example/widget",
        v = 2,
        frame = CanvasDocumentFrame(10f, 20f, 320f, 240f),
        props = buildJsonObject { put("status", "idle") },
        fallback = CanvasPluginFallback(title = "Widget"),
    )

    /** The example manifest's `widget` page, optionally with the `ui:openLink` permission. */
    fun spec(page: PluginPage? = null): PluginViewSpec {
        val manifest = PluginRegistryFixtures.example
        val base = checkNotNull(PluginViewSpec.of(manifest, "widget", "canvas-1", element))
        return if (page == null) base else base.copy(page = page)
    }

    fun linkPage(): PluginPage = spec().page.copy(permissions = listOf(PluginCapability.UI_OPEN_LINK))

    val context = ViewHostContext(
        theme = ViewTheme.DARK,
        cssVariables = mapOf("--md-sys-color-primary" to "#6750a4"),
        element = element,
        settingsPublic = buildJsonObject { put("quality", 80) },
        displayMode = PluginDisplayMode.INLINE,
        locale = "en-CA",
        platform = ViewPlatform.DESKTOP,
    )

    fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}

/** The page side of a [ViewBridge]: what the page posts, and what the bridge sent it. */
internal class FakeViewPort : PostMessagePort {
    private val fromPage = Channel<String>(Channel.UNLIMITED)
    private val toPage = Channel<String>(Channel.UNLIMITED)

    override suspend fun send(json: String) {
        toPage.send(json)
    }

    override val incoming: Flow<String> = fromPage.receiveAsFlow()

    /** The page posts [json]. */
    suspend fun post(json: String) = fromPage.send(json)

    /** The next message the bridge sent the page. */
    suspend fun next(): JsonObject = ViewBridgeFixtures.parse(toPage.receive())

    /** Whatever the bridge sent that the test has not read yet. */
    fun drain(): List<JsonObject> = generateSequence { toPage.tryReceive().getOrNull() }.map(ViewBridgeFixtures::parse).toList()
}

/** A host that records what the bridge asked of it. */
internal class FakeViewHost(var current: ViewHostContext = ViewBridgeFixtures.context) : ViewBridgeHost {
    val resizes = mutableListOf<Pair<Double, Double>>()
    val logs = mutableListOf<Pair<ViewLogLevel, String>>()
    val links = mutableListOf<String>()
    var grantMode: PluginDisplayMode? = null

    override fun context(): ViewHostContext = current

    override fun onResize(width: Double, height: Double) {
        resizes += width to height
    }

    override suspend fun requestDisplayMode(mode: PluginDisplayMode): PluginDisplayMode = grantMode ?: mode

    override suspend fun openLink(link: ViewLink) {
        links += link.url
    }

    override fun onLog(level: ViewLogLevel, message: String) {
        logs += level to message
    }
}

/** A transport that answers every action with [outcome] and records the calls. */
internal class FakeViewTransport(var outcome: PluginViewActionOutcome = PluginViewActionOutcome.Done("started")) : PluginViewTransport {
    val calls = mutableListOf<PluginViewActionCall>()

    override suspend fun readPage(page: PluginViewPageRef): ByteArray = "<html>$page</html>".encodeToByteArray()

    override suspend fun action(call: PluginViewActionCall): PluginViewActionOutcome {
        calls += call
        return outcome
    }
}

/** An audit that keeps every entry. */
internal class RecordingAudit : ViewAuditSink {
    val entries = mutableListOf<ViewAuditEntry>()

    override fun record(entry: ViewAuditEntry) {
        entries += entry
    }
}
