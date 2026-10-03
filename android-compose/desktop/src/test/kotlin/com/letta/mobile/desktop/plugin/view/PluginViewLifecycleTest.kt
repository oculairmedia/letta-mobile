package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.data.plugin.view.ViewBridge
import com.letta.mobile.data.plugin.view.ViewBridgeOptions
import com.letta.mobile.data.plugin.view.ViewBridgeServices
import com.letta.mobile.data.plugin.view.ViewAuditSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Message routing and lifecycle of a desktop live view without a browser (letta-mobile-s416w.14):
 * the page's router queries reach the bridge through the port, the bridge's answers reach the page
 * as `__lettaViewReceive` scripts, only the main frame showing the page may post, and closing
 * tears the bridge down before the browser is disposed, once.
 */
class PluginViewLifecycleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The page side: every script the host ran in it, decoded back to the JSON-RPC message it delivers. */
    private val toPage = Channel<JsonObject>(Channel.UNLIMITED)
    private val port = JcefPostMessagePort(PageScriptRunner { script -> toPage.trySend(messageIn(script)) })
    private val router = PluginViewQueryRouter(PluginViewTestFixtures.pageRef, port)
    private val host = RecordingViewHost()
    private val bridge = ViewBridge(
        PluginViewTestFixtures.spec(),
        port,
        ViewBridgeServices(host, FakePageTransport()),
        ViewBridgeOptions(audit = ViewAuditSink { }, teardownTimeoutMs = TEARDOWN_MS),
    )

    @AfterTest
    fun cancelScope() = scope.cancel()

    @Test
    fun aDeliveryScriptCarriesTheMessageAsAStringLiteral() {
        val script = JcefPostMessagePort.deliveryScript("""{"a":"</script>â€¨\"x"}""")
        assertTrue(script.startsWith("window.__lettaViewReceive&&window.__lettaViewReceive(\""), script)
        assertEquals("""{"a":"</script>â€¨\"x"}""", Json.decodeFromString<String>(script.substringAfter("(").substringBeforeLast(")")))
    }

    @Test
    fun theReadyHandshakeRoundTripsThroughTheRouterAndTheScripts() = runBlocking {
        val session = PluginViewSession(bridge, port, FakeBrowserHandle()).also { it.start(scope) }
        post("""{"jsonrpc":"2.0","id":1,"method":"view.ready","params":{"pageId":"widget","viewVersion":"1"}}""")
        val answer = next()
        assertEquals("1", answer.getValue("id").jsonPrimitive.content)
        assertEquals("el-1", answer.getValue("result").jsonObject.getValue("element").jsonObject.getValue("id").jsonPrimitive.content)

        post("""{"jsonrpc":"2.0","method":"view.log","params":{"level":"info","message":"hello"}}""")
        withTimeout(WAIT_MS) { while (host.logs.isEmpty()) delay(POLL_MS) }
        assertEquals(listOf("hello"), host.logs)

        assertTrue(session.elementChanged(PluginViewTestFixtures.element))
        assertEquals("host.element.changed", next().getValue("method").jsonPrimitive.content)
    }

    @Test
    fun onlyTheMainFrameShowingThePageMayPost() {
        val ready = """{"jsonrpc":"2.0","id":1,"method":"view.ready","params":{}}"""
        assertEquals(PluginQueryOutcome.REFUSED_FRAME, router.route(mainFrame = false, frameUrl = PluginViewTestFixtures.pageUrl, request = ready))
        assertEquals(PluginQueryOutcome.REFUSED_FRAME, router.route(mainFrame = true, frameUrl = "https://embed.example.com/", request = ready))
        assertEquals(PluginQueryOutcome.REFUSED_FRAME, router.route(mainFrame = true, frameUrl = null, request = ready))
        assertEquals(PluginQueryOutcome.ACCEPTED, router.route(mainFrame = true, frameUrl = PluginViewTestFixtures.pageUrl, request = ready))
    }

    @Test
    fun aFullInboxRefusesTheOverflowAndAClosedOneEverything() {
        val small = JcefPostMessagePort(PageScriptRunner { }, capacity = 2)
        val smallRouter = PluginViewQueryRouter(PluginViewTestFixtures.pageRef, small)
        val url = PluginViewTestFixtures.pageUrl
        assertEquals(PluginQueryOutcome.ACCEPTED, smallRouter.route(true, url, "1"))
        assertEquals(PluginQueryOutcome.ACCEPTED, smallRouter.route(true, url, "2"))
        assertEquals(PluginQueryOutcome.REFUSED_FULL, smallRouter.route(true, url, "3"))
        small.close()
        assertFalse(small.post("4"))
    }

    @Test
    fun closingTearsTheBridgeDownBeforeTheBrowserIsDisposedAndOnlyOnce() = runBlocking {
        val events = mutableListOf<String>()
        val handle = FakeBrowserHandle { events += "dispose" }
        val session = PluginViewSession(bridge, port, handle).also { it.start(scope) }
        post("""{"jsonrpc":"2.0","id":1,"method":"view.ready","params":{"pageId":"widget","viewVersion":"1"}}""")
        next()

        // The page acknowledges the teardown the way the shim does, once it arrives.
        scope.launchAcknowledger(events)
        assertTrue(session.close(PluginViewTeardown.CLOSED), "the page acknowledged in time")
        assertEquals(listOf("teardown:closed", "dispose"), events)
        assertTrue(session.isClosed)

        assertFalse(session.close(PluginViewTeardown.CLOSED))
        assertEquals(1, handle.disposals)
        assertFalse(session.elementChanged(PluginViewTestFixtures.element))
        assertFalse(port.post("late"), "nothing more is read from the page")
    }

    @Test
    fun aPageThatNeverAnsweredIsDisposedWithoutWaitingForATeardown() = runBlocking {
        val handle = FakeBrowserHandle()
        val session = PluginViewSession(bridge, port, handle).also { it.start(scope) }
        assertFalse(session.close(PluginViewTeardown.CLOSED), "no handshake, so no teardown to acknowledge")
        assertEquals(1, handle.disposals)
        assertTrue(toPage.tryReceive().isFailure, "a page that never sent view.ready is sent nothing")
    }

    @Test
    fun aSilentReadyPageIsDisposedAfterTheTeardownTimeout() = runBlocking {
        val handle = FakeBrowserHandle()
        val session = PluginViewSession(bridge, port, handle).also { it.start(scope) }
        post("""{"jsonrpc":"2.0","id":1,"method":"view.ready","params":{"pageId":"widget","viewVersion":"1"}}""")
        next()
        assertFalse(session.close(PluginViewTeardown.CLOSED))
        assertEquals(1, handle.disposals)
    }

    private fun CoroutineScope.launchAcknowledger(events: MutableList<String>) = launch {
        val teardown = next()
        events += "teardown:" + teardown.getValue("params").jsonObject.getValue("reason").jsonPrimitive.content
        post("""{"jsonrpc":"2.0","id":${teardown.getValue("id")},"result":{}}""")
    }

    private fun post(json: String) {
        assertEquals(PluginQueryOutcome.ACCEPTED, router.route(mainFrame = true, frameUrl = PluginViewTestFixtures.pageUrl, request = json))
    }

    private suspend fun next(): JsonObject = withTimeout(WAIT_MS) { toPage.receive() }

    private companion object {
        const val WAIT_MS = 5_000L
        const val TEARDOWN_MS = 300L
        const val POLL_MS = 5L

        /** The message a delivery script hands to `__lettaViewReceive`. */
        fun messageIn(script: String): JsonObject {
            val literal = script.substringAfter("window.__lettaViewReceive(").removeSuffix(");")
            return Json.parseToJsonElement(Json.decodeFromString<String>(literal)).jsonObject
        }
    }
}
