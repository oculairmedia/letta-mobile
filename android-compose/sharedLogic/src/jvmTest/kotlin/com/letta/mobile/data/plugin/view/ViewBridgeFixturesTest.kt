package com.letta.mobile.data.plugin.view

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The `lcp-view/1` fixtures under `commonTest/resources/canvas/plugin/v1/view/` (letta-mobile-s416w.31):
 * `handshake.jsonl` replays a whole session against the bridge, every host line exactly as the
 * bridge sends it; every line of `malformed.jsonl` is refused with its code.
 */
class ViewBridgeFixturesTest {
    private fun lines(name: String): List<JsonObject> =
        checkNotNull(javaClass.getResource("$DIR/$name")) { "missing fixture $name" }.readText()
            .lines().filter { it.isNotBlank() }.map(ViewBridgeFixtures::parse)

    @Test
    fun theHandshakeSessionReplaysExactly() = runTest {
        val port = FakeViewPort()
        val transport = FakeViewTransport(PluginViewActionOutcome.Done("started", buildJsonObject { put("jobId", "j1") }))
        val bridge = ViewBridge(
            ViewBridgeFixtures.spec(),
            port,
            ViewBridgeServices(FakeViewHost(), transport),
            ViewBridgeOptions(audit = ViewAuditSink.None, clockMs = { testScheduler.currentTime }),
        )
        backgroundScope.launch { bridge.run() }
        val replay = Replay(this, bridge, port)
        lines("handshake.jsonl").forEach { replay.step(it) }
        assertTrue(checkNotNull(replay.teardown).await(), "the page acknowledged the teardown")
        assertEquals(ViewBridgeState.CLOSED, bridge.state.value)
    }

    private class Replay(private val scope: TestScope, private val bridge: ViewBridge, private val port: FakeViewPort) {
        var teardown: Deferred<Boolean>? = null

        suspend fun step(line: JsonObject) {
            when (line["from"]?.jsonPrimitive?.content ?: line.getValue("do").jsonPrimitive.content) {
                "view" -> port.post(line.getValue("message").toString())
                "host" -> assertEquals(line.getValue("message"), port.next())
                "pushContext" -> assertTrue(bridge.pushContext())
                "teardown" -> teardown = scope.async { bridge.teardown(line.getValue("reason").jsonPrimitive.content) }
            }
            scope.runCurrent()
        }
    }

    @Test
    fun everyMalformedLineIsRefusedWithItsCode() {
        val cases = lines("malformed.jsonl")
        assertTrue(cases.size >= 10)
        cases.forEach { case ->
            val raw = case.getValue("raw").jsonPrimitive.content
            val refused = assertIs<ViewInbound.Refused>(ViewMessageValidator.validate(raw), raw)
            assertEquals(case.getValue("code").jsonPrimitive.int, refused.error.code.code, raw)
        }
    }

    @Test
    fun theFixtureContextIsTheOneTheBridgeSends() {
        val sent = lines("handshake.jsonl")[READY_ANSWER_LINE]
        assertEquals(ViewBridgeFixtures.context.toJson(), sent.getValue("message").jsonObject.getValue("result"))
    }

    private companion object {
        const val DIR = "/canvas/plugin/v1/view"
        const val READY_ANSWER_LINE = 3
    }
}
