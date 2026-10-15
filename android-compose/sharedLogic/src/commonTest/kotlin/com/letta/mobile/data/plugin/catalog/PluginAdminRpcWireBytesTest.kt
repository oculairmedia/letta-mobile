package com.letta.mobile.data.plugin.catalog

import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginHealth
import com.letta.mobile.data.plugin.PluginPage
import com.letta.mobile.data.plugin.PluginState
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-by4vm.76: the exact bytes the `plugins.*` admin RPCs put on the wire today, pinned
 * before anyone moves [PluginAdminRpc.json] to a canonical `LettaJson` instance. None of the
 * three matches it: they keep the default `"type"` discriminator, and only `persisted` writes
 * defaults, but it also writes nulls. A host and a client of different builds talk through these
 * shapes, so a change here is a protocol change, not a refactor.
 *
 * Pinned: states and health as `{"kind": ...}`, every default written (`"health":{"kind":"unknown"}`,
 * `"consented":false`, empty lists and maps, the page's default `csp` and `displayModes`), and
 * nulls left out (`last_fault`, `manifest`, an answer's unset `text`/`structured`).
 */
class PluginAdminRpcWireBytesTest {
    @Test
    fun aSummaryWritesItsDefaultsAndLeavesOutItsNulls() {
        assertBytes(
            """{"id":"letta.chart","name":"Chart","version":"1.2.0","state":{"kind":"faulted","reason":"exit 1"},""" +
                """"health":{"kind":"unknown"},"consented":false}""",
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
                """"runtime_kind":"process","capabilities":["canvas:place"],"origins":[],"pages":[]},""" +
                """"plugins":[$activeJson],"newly_installed":false}""",
            PluginInspectWire.serializer(),
            PluginInspectWire(active, request, listOf(active)),
        )
    }

    @Test
    fun aPageWritesItsManifestEntryWithEveryDefault() {
        assertBytes(
            """{"plugin_id":"letta.chart","version":"1.2.0","page_id":"main","document":"PGh0bWw+",""" +
                """"page":{"html":"main.html","csp":{"connectDomains":[],"resourceDomains":[],"frameDomains":[]},""" +
                """"permissions":[],"displayModes":["inline"]},"view_actions":{}}""",
            PluginPageWire.serializer(),
            PluginPageWire("letta.chart", "1.2.0", "main", "PGh0bWw+", PluginPage("main.html")),
        )
    }

    @Test
    fun aViewActionWritesAnEmptyInput() {
        assertBytes(
            """{"plugin_id":"letta.chart","canvas_id":"canvas-1","element_id":"p-letta.chart-e1","action":"refresh","input":{}}""",
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

    /** What a future switch to `encodeDefaults = false` relies on: a peer that leaves defaults out is read the same. */
    @Test
    fun aSummaryWithoutItsDefaultsDecodesToTheSameValue() {
        val bare = """{"id":"letta.chart","name":"Chart","version":"1.2.0","state":{"kind":"installed"}}"""
        assertEquals(summary(PluginState.Installed), PluginAdminRpc.json.decodeFromString(PluginSummaryWire.serializer(), bare))
    }

    private fun summary(state: PluginState) = PluginSummaryWire("letta.chart", "Chart", "1.2.0", state)

    private fun <T> assertBytes(expected: String, serializer: KSerializer<T>, value: T) {
        assertEquals(expected, PluginAdminRpc.json.encodeToString(serializer, value))
        assertEquals(value, PluginAdminRpc.json.decodeFromString(serializer, expected))
    }
}
