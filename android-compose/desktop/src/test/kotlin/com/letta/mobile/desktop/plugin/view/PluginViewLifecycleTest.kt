package com.letta.mobile.desktop.plugin.view

import com.letta.mobile.data.plugin.view.ViewAuditSink
import com.letta.mobile.data.plugin.view.ViewBridgeOptions
import com.letta.mobile.data.plugin.view.ViewBridgeState
import com.letta.mobile.pluginview.PluginPagePolicy
import com.letta.mobile.ui.canvas.plugin.PluginPageChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Message routing and lifecycle of a desktop live view without a browser (letta-mobile-s416w.14,
 * letta-mobile-cdgpr.3): a shared `PluginViewSession` opened through [JcefLiveViews] on a fake
 * browser. The page's router queries reach the bridge through the session's page channel, the
 * bridge's answers reach the page as `__lettaViewReceive` scripts, only the main frame showing the
 * page may post, and closing tears the bridge down before the browser is disposed, once.
 */
class PluginViewLifecycleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val work = PluginViewWork(scope)
    private val runtime = BrowserRuntime(BrowserStarter { APP })

    /** The page side: every script the host ran in it, decoded back to the JSON-RPC message it delivers. */
    private val toPage = Channel<JsonObject>(Channel.UNLIMITED)
    private val events = CopyOnWriteArrayList<String>()
    private val faults = CopyOnWriteArrayList<String>()
    private val host = RecordingViewHost()
    private val session = PluginViewTestFixtures.session(host, options = ViewBridgeOptions(audit = ViewAuditSink { }, teardownTimeoutMs = TEARDOWN_MS))
    private val handle = FakeBrowserHandle(onScript = { toPage.trySend(messageIn(it)) }, onDispose = { events += "dispose" })
    private lateinit var wiring: JcefViewWiring
    private val views = JcefLiveViews(runtime, work, PluginBrowserFactory { app, wired -> check(app == APP); wiring = wired; handle }, WAIT_MS)

    @AfterTest
    fun cancelScope() = scope.cancel()

    @Test
    fun aDeliveryScriptCarriesTheMessageAsAStringLiteral() {
        val script = JcefPostMessagePort.deliveryScript("""{"a":"</script> \"x"}""")
        assertTrue(script.startsWith("window.__lettaViewReceive&&window.__lettaViewReceive(\""), script)
        assertEquals("""{"a":"</script> \"x"}""", Json.decodeFromString<String>(script.substringAfter("(").substringBeforeLast(")")))
    }

    @Test
    fun theBrowserLoadsTheSharedPolicysPageUrl() {
        views.open(APP, session) { faults += it }
        assertEquals(PluginPagePolicy(session.spec).pageUrl, wiring.server.url)
        assertEquals(PluginViewTestFixtures.pageUrl, wiring.server.url)
        assertTrue(wiring.server.url.startsWith("https://${PluginPagePolicy.PAGE_HOST}/"), wiring.server.url)
    }

    @Test
    fun theReadyHandshakeRoundTripsThroughTheRouterAndTheScripts() = runBlocking {
        views.open(APP, session) { faults += it }
        post(READY)
        val answer = next()
        assertEquals("1", answer.getValue("id").jsonPrimitive.content)
        assertEquals("el-1", answer.getValue("result").jsonObject.getValue("element").jsonObject.getValue("id").jsonPrimitive.content)

        post("""{"jsonrpc":"2.0","method":"view.log","params":{"level":"info","message":"hello"}}""")
        withTimeout(WAIT_MS) { while (host.logs.isEmpty()) delay(POLL_MS) }
        assertEquals(listOf("hello"), host.logs)

        assertTrue(session.bridge.elementChanged(PluginViewTestFixtures.element))
        assertEquals("host.element.changed", next().getValue("method").jsonPrimitive.content)
        assertEquals(emptyList(), faults)
    }

    @Test
    fun onlyTheMainFrameShowingThePageMayPost() {
        views.open(APP, session) { faults += it }
        val router = wiring.queries
        val page = PluginViewTestFixtures.pageUrl
        assertEquals(PluginQueryOutcome.REFUSED_FRAME, router.route(PluginPageQuery(false, page, READY)))
        assertEquals(PluginQueryOutcome.REFUSED_FRAME, router.route(PluginPageQuery(true, FRAMED, READY)))
        assertEquals(PluginQueryOutcome.REFUSED_FRAME, router.route(PluginPageQuery(true, "$page/other", READY)))
        assertEquals(PluginQueryOutcome.REFUSED_FRAME, router.route(PluginPageQuery(true, null, READY)))
        assertEquals(PluginQueryOutcome.ACCEPTED, router.route(PluginPageQuery(true, page, READY)))
    }

    @Test
    fun aFullInboxRefusesTheOverflowAndAClosedOneEverything() {
        val small = JcefPostMessagePort(PluginPageChannel(capacity = 2)) { }
        val url = PluginViewTestFixtures.pageUrl
        val smallRouter = PluginViewQueryRouter(url, small)
        assertEquals(PluginQueryOutcome.ACCEPTED, smallRouter.route(PluginPageQuery(true, url, "1")))
        assertEquals(PluginQueryOutcome.ACCEPTED, smallRouter.route(PluginPageQuery(true, url, "2")))
        assertEquals(PluginQueryOutcome.REFUSED_FULL, smallRouter.route(PluginPageQuery(true, url, "3")))
        small.close()
        assertFalse(small.post("4"))
    }

    @Test
    fun closingTearsTheBridgeDownBeforeTheBrowserIsDisposedAndOnlyOnce() = runBlocking {
        val view = views.open(APP, session) { faults += it }
        post(READY)
        next()

        // The page acknowledges the teardown the way the shim does, once it arrives.
        scope.launchAcknowledger()
        assertTrue(view.close(PluginViewTeardown.CLOSED), "the page acknowledged in time")
        assertEquals(listOf("teardown:closed", "dispose"), events.toList())
        assertTrue(view.isClosed)

        assertFalse(view.close(PluginViewTeardown.CLOSED))
        assertEquals(1, handle.disposals)
        assertFalse(session.page.postFromPage("late"), "the session's page channel is closed")
    }

    @Test
    fun aPageThatNeverAnsweredIsDisposedWithoutWaitingForATeardown() = runBlocking {
        val view = views.open(APP, session) { faults += it }
        assertFalse(view.close(PluginViewTeardown.CLOSED), "no handshake, so no teardown to acknowledge")
        assertEquals(1, handle.disposals)
        assertTrue(toPage.tryReceive().isFailure, "a page that never sent view.ready is sent nothing")
    }

    @Test
    fun aSilentReadyPageIsDisposedAfterTheTeardownTimeout() = runBlocking {
        val view = views.open(APP, session) { faults += it }
        post(READY)
        next()
        assertFalse(view.close(PluginViewTeardown.CLOSED))
        assertEquals(1, handle.disposals)
    }

    @Test
    fun theHostClosesItsViewsAsItsWorkAndCountsThem() = runBlocking {
        val view = views.open(APP, session) { faults += it }
        assertEquals(1, views.openViews)
        views.close(view, PluginViewTeardown.CLOSED)
        withTimeout(WAIT_MS) { while (views.openViews > 0) delay(POLL_MS) }
        assertTrue(view.isClosed)
        assertEquals(1, handle.disposals)
    }

    @Test
    fun aPageThatNeverSendsReadyIsReportedStalledAndAReadyOneIsNot() = runBlocking {
        val stalled = CompletableDeferred<String>()
        val watched = JcefLiveViews(runtime, work, PluginBrowserFactory { _, _ -> FakeBrowserHandle() }, TEARDOWN_MS)
        val silent = watched.open(APP, PluginViewTestFixtures.session()) { stalled.complete(it) }
        assertEquals(PluginReadyWatch.STALLED, withTimeout(WAIT_MS) { stalled.await() })
        silent.close(PluginViewTeardown.CLOSED)

        val reports = CopyOnWriteArrayList<String>()
        val readySession = PluginViewTestFixtures.session()
        lateinit var readyWiring: JcefViewWiring
        val readyViews = JcefLiveViews(runtime, work, PluginBrowserFactory { _, wired -> readyWiring = wired; FakeBrowserHandle() }, READY_WATCH_MS)
        val ready = readyViews.open(APP, readySession) { reports += it }
        assertEquals(PluginQueryOutcome.ACCEPTED, readyWiring.queries.route(PluginPageQuery(true, PluginViewTestFixtures.pageUrl, READY)))
        withTimeout(READY_WATCH_MS) { readySession.bridge.state.first { it == ViewBridgeState.READY } }
        delay(READY_WATCH_MS + POLL_MS)
        ready.close(PluginViewTeardown.CLOSED)
        assertEquals(emptyList(), reports.toList())
    }

    private fun CoroutineScope.launchAcknowledger() = launch {
        val teardown = next()
        events += "teardown:" + teardown.getValue("params").jsonObject.getValue("reason").jsonPrimitive.content
        post("""{"jsonrpc":"2.0","id":${teardown.getValue("id")},"result":{}}""")
    }

    private fun post(json: String) {
        assertEquals(PluginQueryOutcome.ACCEPTED, wiring.queries.route(PluginPageQuery(true, PluginViewTestFixtures.pageUrl, json)))
    }

    private suspend fun next(): JsonObject = withTimeout(WAIT_MS) { toPage.receive() }

    private companion object {
        const val APP = "app"
        const val WAIT_MS = 5_000L
        const val TEARDOWN_MS = 300L
        const val READY_WATCH_MS = 1_000L
        const val POLL_MS = 5L
        const val FRAMED = "https://embed.example.com/"
        const val READY = """{"jsonrpc":"2.0","id":1,"method":"view.ready","params":{"pageId":"widget","viewVersion":"1"}}"""

        /** The message a delivery script hands to `__lettaViewReceive`. */
        fun messageIn(script: String): JsonObject {
            val literal = script.substringAfter("window.__lettaViewReceive(").removeSuffix(");")
            return Json.parseToJsonElement(Json.decodeFromString<String>(literal)).jsonObject
        }
    }
}
