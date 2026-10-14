package com.letta.mobile.data.plugin.wire

import com.letta.mobile.data.plugin.PluginSecrets
import com.letta.mobile.plugin.api.ActionCall
import com.letta.mobile.plugin.api.ActionContext
import com.letta.mobile.plugin.api.ActionOrigin
import com.letta.mobile.plugin.api.ActionResult
import com.letta.mobile.plugin.api.HostInfo
import com.letta.mobile.plugin.api.LogLevel
import com.letta.mobile.plugin.api.SnapshotSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The reference plugin `plugins/chart` end to end over its real stdio (letta-mobile-cdgpr.2): the
 * host session, holding the plugin to its manifest's capabilities, initializes and activates it,
 * invokes `plot` (the plugin uploads a PNG with `host.putAsset.*` and places a chart with
 * `host.emit`), invokes `toggle` from the element's view (a second upload and an update with the
 * kind flipped) and deactivates it, after which the process exits 0. The session is the golden
 * transcript `session-chart.jsonl`, the PNG-dependent values masked. Skipped without `node`.
 */
class ChartPluginEndToEndTest {
    @Test
    fun plotThenToggleOverStdioMatchesTheGoldenTranscript() {
        if (!ChartPluginProcess.nodeAvailable) {
            println("skipped: node is not on PATH")
            return
        }
        val host = RecordingHost()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ChartPluginProcess.start().use { plugin ->
            try {
                runBlocking { withTimeout(30.seconds) { runSession(LcpHostSession(plugin.transport, binding(host), scope), host) } }
            } finally {
                scope.cancel()
            }
            assertEquals(0, plugin.awaitExit(10), "the plugin exits cleanly after plugin.deactivate")
            assertPlotAndToggle(host)
            assertMatchesGolden(plugin.transcript())
        }
    }

    private suspend fun runSession(session: LcpHostSession, host: RecordingHost) {
        assertEquals(1, session.initialize(INITIALIZE).contractVersion)
        session.activate()
        assertEquals(LogLevel.INFO, host.logged.receive().level)
        assertEquals(structured("el-1", "bar"), assertIs<ActionResult.Ok>(session.invoke(PLOT)).structured)
        assertEquals(structured("el-1", "line"), assertIs<ActionResult.Ok>(session.invoke(TOGGLE)).structured)
        session.deactivate()
        assertEquals(LcpSessionState.CLOSED, session.state.value)
    }

    private fun assertPlotAndToggle(host: RecordingHost) {
        assertEquals(2, host.assets.size, "one upload per render")
        host.assets.forEach { asset ->
            assertEquals("image/png", asset.mediaType)
            assertTrue(asset.bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE), "a PNG")
        }
        assertNotEquals(host.assets[0].sha256, host.assets[1].sha256, "toggle re-rendered the picture")
        val placed = host.emits[0].place.single()
        assertEquals("chart", placed.kind)
        assertEquals("bar", placed.props.getValue("kind").jsonPrimitive.content)
        assertEquals("[3,5,2,6]", placed.props.getValue("values").jsonPrimitive.content)
        assertEquals(SnapshotSource.Asset("sha256:${host.assets[0].sha256}"), placed.snapshot)
        val updated = host.emits[1].update.single()
        assertEquals("el-1", updated.elementId)
        assertEquals("line", updated.props?.getValue("kind")?.jsonPrimitive?.content)
        assertEquals(SnapshotSource.Asset("sha256:${host.assets[1].sha256}"), updated.snapshot)
    }

    private fun assertMatchesGolden(recorded: List<JsonObject>) {
        File("build/chart-plugin").apply { mkdirs() }.resolve(GOLDEN).writeText(recorded.joinToString("\n", postfix = "\n"))
        val golden = WireTranscripts.raw(GOLDEN).map(WireTranscripts::masked)
        assertEquals(golden, recorded.map(WireTranscripts::masked), "the session drifted from $GOLDEN (actual in build/chart-plugin/)")
    }

    private companion object {
        const val GOLDEN = "session-chart.jsonl"
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

        val INITIALIZE = InitializeParams(1, listOf(1), "letta.chart", JsonObject(emptyMap()), HostInfo("letta-host", "test"))

        val PLOT = ActionCall(
            "plot",
            buildJsonObject {
                put("kind", "bar")
                put("title", "Revenue")
                put("labels", JsonArray(listOf("Q1", "Q2", "Q3", "Q4").map(::JsonPrimitive)))
                put("values", JsonArray(listOf(3, 5, 2, 6).map(::JsonPrimitive)))
            },
            ActionContext(ActionOrigin.Agent("agent-1", "conv-1", "call-1")),
        )

        val TOGGLE = ActionCall(
            "toggle",
            JsonObject(emptyMap()),
            ActionContext(ActionOrigin.View("el-1", "peer-1"), canvasId = "canvas-1", elementId = "el-1"),
        )

        /** The host holds the plugin to the capabilities its manifest declares (the consented set). */
        fun binding(host: RecordingHost) =
            LcpHostBinding(ChartPluginProcess.manifest().capabilities.toSet(), PluginSecrets().scrubber(), host)

        fun structured(elementId: String, kind: String) = buildJsonObject {
            put("elementId", elementId)
            put("kind", kind)
        }
    }
}
