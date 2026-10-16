package com.letta.mobile.data.plugin.catalog

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginContract
import com.letta.mobile.data.plugin.PluginHealth
import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.PluginPage
import com.letta.mobile.data.plugin.PluginRuntime
import com.letta.mobile.data.plugin.PluginState
import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-by4vm.102: the exact bytes the `plugins.*` admin RPCs put on the wire, written and
 * read back with [LettaJson.wire], the one Json both the host's handlers and the clients use.
 * Plugin states, health and runtimes say which they are with `"kind"`; defaults and nulls are left
 * out (a decoder fills them in). A host and a client talk through these shapes, so a change here
 * is a protocol change: the wrapper is redeployed with it.
 */
class PluginAdminRpcWireBytesTest {
    @Test
    fun aSummaryLeavesOutItsDefaultsAndNulls() {
        assertBytes(
            """{"id":"letta.chart","name":"Chart","version":"1.2.0","state":{"kind":"faulted","reason":"exit 1"}}""",
            PluginSummaryWire.serializer(),
            summary(PluginState.Faulted("exit 1")),
        )
    }

    @Test
    fun anInspectAnswerCarriesThePluginItsRequestAndTheRegistry() {
        val active = summary(PluginState.Active).copy(health = PluginHealth.Ok, consented = true, lastFault = "earlier crash")
        val request = PluginRequestWire("letta.chart", "Chart", "1.2.0", "Letta", "ab12", "process", listOf(PluginCapability.CANVAS_PLACE))
        val activeJson = """{"id":"letta.chart","name":"Chart","version":"1.2.0","state":{"kind":"active"},""" +
            """"health":{"kind":"ok"},"consented":true,"last_fault":"earlier crash"}"""
        assertBytes(
            """{"plugin":$activeJson,""" +
                """"request":{"id":"letta.chart","name":"Chart","version":"1.2.0","publisher":"Letta","sha256":"ab12",""" +
                """"runtime_kind":"process","capabilities":["canvas:place"]},"plugins":[$activeJson]}""",
            PluginInspectWire.serializer(),
            PluginInspectWire(active, request, listOf(active)),
        )
    }

    @Test
    fun aSummaryManifestNamesItsRuntimeByKind() {
        val manifest = PluginManifest(1, "letta.chart", "Chart", "1.2.0", "Letta", PluginContract(1), PluginRuntime.Process("sh"))
        assertBytes(
            """{"id":"letta.chart","name":"Chart","version":"1.2.0","state":{"kind":"installed"},"manifest":""" +
                """{"manifestVersion":1,"id":"letta.chart","name":"Chart","version":"1.2.0","publisher":"Letta",""" +
                """"contract":{"version":1},"runtime":{"kind":"process","command":"sh"}}}""",
            PluginSummaryWire.serializer(),
            summary(PluginState.Installed).copy(manifest = manifest),
        )
    }

    @Test
    fun aPageCarriesOnlyWhatDiffersFromTheManifestDefaults() {
        assertBytes(
            """{"plugin_id":"letta.chart","version":"1.2.0","page_id":"main","document":"PGh0bWw+","page":{"html":"main.html"}}""",
            PluginPageWire.serializer(),
            PluginPageWire("letta.chart", "1.2.0", "main", "PGh0bWw+", PluginPage("main.html")),
        )
    }

    @Test
    fun aViewActionLeavesOutAnEmptyInput() {
        assertBytes(
            """{"plugin_id":"letta.chart","canvas_id":"canvas-1","element_id":"p-letta.chart-e1","action":"refresh"}""",
            PluginViewActionWire.serializer(),
            PluginViewActionWire("letta.chart", "canvas-1", "p-letta.chart-e1", "refresh"),
        )
    }

    @Test
    fun anAnswerLeavesOutWhatItDoesNotCarry() {
        assertBytes(
            """{"status":"unavailable","message":"letta.chart is not running on the host"}""",
            PluginViewAnswerWire.serializer(),
            PluginViewAnswerWire(PluginViewAnswerWire.UNAVAILABLE, message = "letta.chart is not running on the host"),
        )
        assertBytes(
            """{"status":"done","text":"ok","structured":{"n":1}}""",
            PluginViewAnswerWire.serializer(),
            PluginViewAnswerWire(PluginViewAnswerWire.DONE, text = "ok", structured = JsonObject(mapOf("n" to JsonPrimitive(1)))),
        )
    }

    private fun summary(state: PluginState) = PluginSummaryWire("letta.chart", "Chart", "1.2.0", state)

    private fun <T> assertBytes(expected: String, serializer: KSerializer<T>, value: T) {
        assertEquals(expected, LettaJson.wire.encodeToString(serializer, value))
        assertEquals(value, LettaJson.wire.decodeFromString(serializer, expected))
    }
}
