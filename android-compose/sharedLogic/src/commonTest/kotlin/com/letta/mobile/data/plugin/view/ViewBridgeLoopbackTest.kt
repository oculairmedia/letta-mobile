package com.letta.mobile.data.plugin.view

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A page session end to end (letta-mobile-s416w.31): a fake page that behaves as the shim does
 * (numbered requests, host messages as events, teardown acknowledged) ↔ the bridge ↔ a fake host
 * and transport.
 */
class ViewBridgeLoopbackTest {
    /** The page as the shim makes it: requests with their own ids, host messages collected as events. */
    private class FakePage(private val port: FakeViewPort, scope: CoroutineScope) {
        private var nextId = 0
        val events = mutableListOf<JsonObject>()
        private val answers = mutableMapOf<Int, CompletableDeferred<JsonObject>>()

        init {
            scope.launch { while (true) onHostMessage(port.next()) }
        }

        private suspend fun onHostMessage(message: JsonObject) {
            val method = message["method"]?.jsonPrimitive?.content
            if (method == null) {
                answers.remove(message["id"]!!.jsonPrimitive.content.toInt())?.complete(message)
                return
            }
            events += message
            if (method == HostMethod.TEARDOWN) port.post(ViewRpc.result(message["id"] as JsonPrimitive, JsonObject(emptyMap())).toString())
        }

        suspend fun request(method: String, params: JsonObject): JsonObject {
            val id = ++nextId
            val answer = CompletableDeferred<JsonObject>().also { answers[id] = it }
            port.post(ViewRpc.request(JsonPrimitive(id), method, params).toString())
            return answer.await()
        }

        suspend fun notify(method: String, params: JsonObject) = port.post(ViewRpc.notification(method, params).toString())
    }

    @Test
    fun aPageLivesItsWholeSessionThroughTheBridge() = runTest {
        val port = FakeViewPort()
        val host = FakeViewHost()
        val transport = FakeViewTransport(PluginViewActionOutcome.Done("job started", buildJsonObject { put("jobId", "j1") }))
        val audit = RecordingAudit()
        val links = ViewLinkPolicy.allowlistOrConfirm(listOf(PluginOrigin.parse("https://example.test")!!)) { false }
        val bridge = ViewBridge(
            ViewBridgeFixtures.spec(ViewBridgeFixtures.linkPage()),
            port,
            ViewBridgeServices(host, transport, links, consent = { it == PluginCapability.UI_OPEN_LINK }),
            ViewBridgeOptions(audit = audit, clockMs = { testScheduler.currentTime }),
        )
        val running = backgroundScope.launch { bridge.run() }
        val page = FakePage(port, backgroundScope)

        val ready = page.request("view.ready", buildJsonObject { put("pageId", "widget"); put("viewVersion", LcpViewShim.VERSION) })
        assertEquals("dark", ready["result"]!!.jsonObject["theme"]!!.jsonPrimitive.content)

        val action = page.request("view.action", buildJsonObject { put("action", "start"); put("input", buildJsonObject { put("label", "go") }) })
        assertEquals("job started", action["result"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        page.notify("view.resize", buildJsonObject { put("width", 400); put("height", 300) })
        val mode = page.request("view.displayMode", buildJsonObject { put("mode", "fullscreen") })
        assertEquals("fullscreen", mode["result"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertTrue("result" in page.request("view.openLink", buildJsonObject { put("url", "https://example.test/docs") }))
        assertTrue("error" in page.request("view.openLink", buildJsonObject { put("url", "https://elsewhere.test/") }))

        bridge.elementChanged(ViewBridgeFixtures.element.copy(props = buildJsonObject { put("status", "running") }))
        assertTrue(async { bridge.teardown("closed") }.await(), "the page acknowledged the teardown")
        running.join()

        assertEquals(listOf(HostMethod.ELEMENT_CHANGED, HostMethod.TEARDOWN), page.events.map { it["method"]!!.jsonPrimitive.content })
        assertEquals(listOf(400.0 to 300.0), host.resizes)
        assertEquals(listOf("https://example.test/docs"), host.links)
        assertEquals("start", transport.calls.single().action)
        assertEquals(ViewBridgeState.CLOSED, bridge.state.value)
        assertEquals(
            listOf("view.ready", "view.action", "view.resize", "view.displayMode", "view.openLink", "view.openLink", HostMethod.ELEMENT_CHANGED, HostMethod.TEARDOWN, HostMethod.TEARDOWN),
            audit.entries.map { it.method },
        )
        assertEquals(listOf(ViewErrorCode.DENIED), audit.entries.mapNotNull { it.refusal })
    }
}
