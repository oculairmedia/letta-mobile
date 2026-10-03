package com.letta.mobile.data.plugin.view

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginDisplayMode
import com.letta.mobile.data.plugin.PluginOrigin
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bridge's state machine and method handling over a fake page, host and transport (letta-mobile-s416w.31). */
class ViewBridgeTest {
    private class Harness(
        scope: TestScope,
        spec: PluginViewSpec = ViewBridgeFixtures.spec(),
        transport: PluginViewTransport? = null,
        links: ViewLinkPolicy = ViewLinkPolicy.DenyAll,
        consent: PluginViewConsent = PluginViewConsent.DenyAll,
    ) {
        val port = FakeViewPort()
        val host = FakeViewHost()
        val fakeTransport = FakeViewTransport()
        val audit = RecordingAudit()
        val bridge = ViewBridge(
            spec,
            port,
            ViewBridgeServices(host, transport ?: fakeTransport, links, consent),
            ViewBridgeOptions(audit = audit, clockMs = { scope.testScheduler.currentTime }),
        )
        val running: Job = scope.backgroundScope.launch { bridge.run() }

        suspend fun ask(method: String, params: String, id: Int = 1): JsonObject {
            port.post("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
            return port.next()
        }

        suspend fun tell(method: String, params: String) = port.post("""{"jsonrpc":"2.0","method":"$method","params":$params}""")

        suspend fun ready(): JsonObject = ask("view.ready", """{"pageId":"widget","viewVersion":"1"}""", id = 0)
    }

    private fun JsonObject.errorCode(): Int = getValue("error").jsonObject.getValue("code").jsonPrimitive.int

    private fun JsonObject.result(): JsonObject = getValue("result").jsonObject

    @Test
    fun theHandshakeAnswersWithTheHostContext() = runTest {
        val h = Harness(this)
        assertEquals(ViewBridgeState.AWAITING_READY, h.bridge.state.value)
        val answer = h.ready()
        assertEquals(JsonPrimitive(0), answer["id"])
        assertEquals(ViewBridgeFixtures.context.toJson(), answer.result())
        assertEquals(ViewBridgeState.READY, h.bridge.state.value)
        assertEquals(listOf(ViewAuditEntry(h.bridge.spec, ViewDirection.VIEW_TO_HOST, "view.ready")), h.audit.entries)
    }

    @Test
    fun callsBeforeTheHandshakeAreRefused() = runTest {
        val h = Harness(this)
        assertEquals(ViewErrorCode.NOT_READY.code, h.ask("view.action", """{"action":"start","input":{"label":"x"}}""").errorCode())
        h.tell("view.resize", """{"width":10,"height":10}""")
        h.ready()
        assertEquals(emptyList(), h.host.resizes)
        assertEquals(emptyList(), h.fakeTransport.calls)
        assertEquals(listOf(ViewErrorCode.NOT_READY, ViewErrorCode.NOT_READY, null), h.audit.entries.map { it.refusal })
    }

    @Test
    fun theHandshakeNamesThisPageAndAKnownVersion() = runTest {
        val h = Harness(this)
        assertEquals(ViewErrorCode.FORBIDDEN.code, h.ask("view.ready", """{"pageId":"other","viewVersion":"1"}""").errorCode())
        assertEquals(ViewErrorCode.INVALID_PARAMS.code, h.ask("view.ready", """{"pageId":"widget","viewVersion":"2"}""").errorCode())
        assertEquals(ViewBridgeState.AWAITING_READY, h.bridge.state.value)
        h.ready()
        assertEquals(ViewErrorCode.INVALID_REQUEST.code, h.ready().errorCode(), "a second view.ready")
    }

    @Test
    fun anActionIsRelayedForThisViewOnly() = runTest {
        val h = Harness(this)
        h.fakeTransport.outcome = PluginViewActionOutcome.Done("started", buildJsonObject { put("jobId", "j1") })
        h.ready()
        val answer = h.ask("view.action", """{"action":"start","input":{"label":"render"}}""")
        assertEquals(buildJsonObject { put("text", "started"); put("structured", buildJsonObject { put("jobId", "j1") }) }, answer.result())
        val call = h.fakeTransport.calls.single()
        assertEquals(PluginViewActionCall("letta.example", "canvas-1", "el-1", "start", buildJsonObject { put("label", "render") }), call)
    }

    @Test
    fun actionsOutsideTheViewSetOrWithBadInputNeverReachTheTransport() = runTest {
        val spec = ViewBridgeFixtures.spec().let { it.copy(viewActions = it.viewActions - "cancel") }
        val h = Harness(this, spec = spec)
        h.ready()
        assertEquals(ViewErrorCode.FORBIDDEN.code, h.ask("view.action", """{"action":"cancel"}""").errorCode())
        assertEquals(ViewErrorCode.FORBIDDEN.code, h.ask("view.action", """{"action":"deleteEverything"}""").errorCode())
        val bad = h.ask("view.action", """{"action":"start","input":{"label":7}}""")
        assertEquals(ViewErrorCode.INVALID_PARAMS.code, bad.errorCode())
        assertEquals(emptyList(), h.fakeTransport.calls)
    }

    @Test
    fun failedAndUnreachableActionsAreErrors() = runTest {
        val h = Harness(this)
        h.fakeTransport.outcome = PluginViewActionOutcome.Failed("the job queue is full")
        h.ready()
        assertEquals(ViewErrorCode.ACTION_FAILED.code, h.ask("view.action", """{"action":"refresh"}""").errorCode())
        val offline = Harness(this, transport = PluginViewTransport.Unavailable)
        offline.ready()
        assertEquals(ViewErrorCode.UNAVAILABLE.code, offline.ask("view.action", """{"action":"refresh"}""").errorCode())
    }

    @Test
    fun resizeAndLogReachTheHostWithoutAnAnswer() = runTest {
        val h = Harness(this)
        h.ready()
        h.tell("view.resize", """{"width":480,"height":300.5}""")
        h.tell("view.log", """{"level":"warn","message":"low on memory"}""")
        h.tell("view.resize", """{"width":-1,"height":300}""")
        runCurrent()
        assertEquals(listOf(480.0 to 300.5), h.host.resizes)
        assertEquals(listOf(ViewLogLevel.WARN to "low on memory"), h.host.logs)
        assertEquals(emptyList(), h.port.drain())
    }

    @Test
    fun displayModesAreTheDeclaredOnesAndTheHostDecides() = runTest {
        val h = Harness(this)
        h.ready()
        assertEquals("fullscreen", h.ask("view.displayMode", """{"mode":"fullscreen"}""").result()["mode"]!!.jsonPrimitive.content)
        assertEquals(ViewErrorCode.FORBIDDEN.code, h.ask("view.displayMode", """{"mode":"pip"}""").errorCode())
        h.host.grantMode = PluginDisplayMode.INLINE
        assertEquals("inline", h.ask("view.displayMode", """{"mode":"fullscreen"}""").result()["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun aLinkNeedsThePermissionConsentAndThePolicy() = runTest {
        val undeclared = Harness(this, links = ViewLinkPolicy.confirm { true }, consent = { true })
        undeclared.ready()
        assertEquals(ViewErrorCode.FORBIDDEN.code, undeclared.ask("view.openLink", """{"url":"https://example.test"}""").errorCode())

        val page = ViewBridgeFixtures.spec(ViewBridgeFixtures.linkPage())
        val noConsent = Harness(this, spec = page, links = ViewLinkPolicy.confirm { true })
        noConsent.ready()
        assertEquals(ViewErrorCode.DENIED.code, noConsent.ask("view.openLink", """{"url":"https://example.test"}""").errorCode())

        val allowlist = ViewLinkPolicy.allowlist(listOf(PluginOrigin.parse("https://*.example.test")!!))
        val h = Harness(this, spec = page, links = allowlist, consent = { it == PluginCapability.UI_OPEN_LINK })
        h.ready()
        assertEquals(JsonObject(emptyMap()), h.ask("view.openLink", """{"url":"https://docs.example.test/a?b=1"}""").result())
        assertEquals(ViewErrorCode.DENIED.code, h.ask("view.openLink", """{"url":"https://evil.test/"}""").errorCode())
        assertEquals(ViewErrorCode.INVALID_PARAMS.code, h.ask("view.openLink", """{"url":"javascript:alert(1)"}""").errorCode())
        assertEquals(ViewErrorCode.INVALID_PARAMS.code, h.ask("view.openLink", """{"url":"https://user@docs.example.test/"}""").errorCode())
        assertEquals(listOf("https://docs.example.test/a?b=1"), h.host.links)
    }

    @Test
    fun aViewOverTheRateIsRefusedUntilTheWindowPasses() = runTest {
        val h = Harness(this)
        h.ready()
        repeat(LcpView.MAX_MESSAGES_PER_SECOND - 1) { h.tell("view.log", """{"level":"debug","message":"$it"}""") }
        assertEquals(ViewErrorCode.RATE_LIMITED.code, h.ask("view.displayMode", """{"mode":"inline"}""").errorCode())
        advanceTimeBy(ViewRateLimiter.WINDOW_MS)
        assertEquals("inline", h.ask("view.displayMode", """{"mode":"inline"}""").result()["mode"]!!.jsonPrimitive.content)
        assertTrue(h.audit.entries.any { it.refusal == ViewErrorCode.RATE_LIMITED })
    }

    @Test
    fun brokenMessagesAreAnsweredOnlyWhenTheyCanBe() = runTest {
        val h = Harness(this)
        h.ready()
        h.port.post("not json")
        assertEquals(ViewErrorCode.PARSE_ERROR.code, h.port.next().errorCode())
        h.tell("view.resize", """{"width":"wide","height":1}""")
        h.port.post("""{"jsonrpc":"2.0","id":"host-9","result":{}}""")
        runCurrent()
        assertEquals(emptyList(), h.port.drain(), "a bad notification and a stray answer get no reply")
        assertEquals(ViewErrorCode.INVALID_REQUEST, h.audit.entries.last().refusal)
    }

    @Test
    fun hostPushesReachOnlyAReadyView() = runTest {
        val h = Harness(this)
        assertFalse(h.bridge.pushContext())
        h.ready()
        h.host.current = ViewBridgeFixtures.context.copy(theme = ViewTheme.LIGHT)
        assertTrue(h.bridge.pushContext())
        val context = h.port.next()
        assertEquals(HostMethod.CONTEXT, context["method"]!!.jsonPrimitive.content)
        assertEquals("light", context["params"]!!.jsonObject["theme"]!!.jsonPrimitive.content)
        val moved = ViewBridgeFixtures.element.copy(props = buildJsonObject { put("status", "running") })
        assertTrue(h.bridge.elementChanged(moved))
        val changed = h.port.next()
        assertEquals(HostMethod.ELEMENT_CHANGED, changed["method"]!!.jsonPrimitive.content)
        assertEquals(ViewHostContext.elementJson(moved), changed["params"]!!.jsonObject["element"])
    }

    @Test
    fun teardownWaitsForThePageThenCloses() = runTest {
        val h = Harness(this)
        h.ready()
        val acknowledged = backgroundScope.async { h.bridge.teardown("collapsed") }
        val request = h.port.next()
        assertEquals(HostMethod.TEARDOWN, request["method"]!!.jsonPrimitive.content)
        assertEquals("collapsed", request["params"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
        assertEquals(ViewBridgeState.TEARING_DOWN, h.bridge.state.value)
        assertEquals(ViewErrorCode.CLOSED.code, h.ask("view.action", """{"action":"refresh"}""").errorCode())
        h.port.post("""{"jsonrpc":"2.0","id":${request["id"]},"result":{}}""")
        assertTrue(acknowledged.await())
        assertEquals(ViewBridgeState.CLOSED, h.bridge.state.value)
        runCurrent()
        assertTrue(h.running.isCompleted, "run() returns once the view is closed")
        assertFalse(h.bridge.teardown("again"))
    }

    @Test
    fun anUnansweredTeardownClosesAfterTheTimeout() = runTest {
        val h = Harness(this)
        h.ready()
        val started = testScheduler.currentTime
        assertFalse(h.bridge.teardown("closed"))
        assertEquals(LcpView.TEARDOWN_TIMEOUT_MS, testScheduler.currentTime - started)
        assertEquals(ViewBridgeState.CLOSED, h.bridge.state.value)
    }

    @Test
    fun aViewThatNeverGotReadyClosesWithoutAMessage() = runTest {
        val h = Harness(this)
        assertFalse(h.bridge.teardown("scrolled out"))
        assertEquals(ViewBridgeState.CLOSED, h.bridge.state.value)
        assertEquals(emptyList(), h.port.drain())
    }

    @Test
    fun pagesAreReadByTheirRefAndTheOfflineTransportHasNone() = runTest {
        val ref = ViewBridgeFixtures.spec().pageRef
        assertEquals(PluginViewPageRef("letta.example", "1.2.0", "widget"), ref)
        assertEquals("<html>letta.example@1.2.0/widget</html>", FakeViewTransport().readPage(ref).decodeToString())
        assertFailsWith<PluginViewUnavailableException> { PluginViewTransport.Unavailable.readPage(ref) }
    }

    @Test
    fun theTelemetryAuditAcceptsEveryEntry() {
        val spec = ViewBridgeFixtures.spec()
        ViewAuditSink.ToTelemetry.record(ViewAuditEntry(spec, ViewDirection.VIEW_TO_HOST, "view.ready"))
        ViewAuditSink.ToTelemetry.record(ViewAuditEntry(spec, ViewDirection.HOST_TO_VIEW, null, ViewErrorCode.TOO_LARGE))
        ViewAuditSink.None.record(ViewAuditEntry(spec, ViewDirection.VIEW_TO_HOST, null))
    }
}
