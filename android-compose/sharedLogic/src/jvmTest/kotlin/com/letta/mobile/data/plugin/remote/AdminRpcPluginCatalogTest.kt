package com.letta.mobile.data.plugin.remote

import com.letta.mobile.data.plugin.PluginHealth
import com.letta.mobile.data.plugin.PluginState
import com.letta.mobile.data.plugin.catalog.PluginAdminRpc
import com.letta.mobile.data.plugin.catalog.PluginCatalogResult
import com.letta.mobile.data.plugin.catalog.PluginId
import com.letta.mobile.data.plugin.catalog.PluginSummary
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** letta-mobile-cdgpr.7: how [AdminRpcPluginCatalog] reads the host's answers into [PluginSummary]s. */
class AdminRpcPluginCatalogTest {
    private val listed = """
        {"plugins":[
          {"id":"letta.chart","name":"Chart","version":"1.2.0","state":{"kind":"active"},"health":{"kind":"ok"},"consented":true},
          {"id":"letta.maps","name":"Maps","version":"0.3.0","state":{"kind":"faulted","reason":"crashed 3 times"},
           "health":{"kind":"failed","reason":"crashed 3 times"},"consented":true,"last_fault":"crashed 3 times"},
          {"id":"letta.new","name":"New","version":"1.0.0","state":{"kind":"installed"},"some_future_field":1}
        ]}
    """.trimIndent()

    @Test
    fun theHostsRegistryBecomesSummaries() = runTest {
        val link = CannedLink(Json.parseToJsonElement(listed))
        val catalog = AdminRpcPluginCatalog(link) { _, _ -> error("unused") }

        assertEquals(PluginCatalogResult.Ok(Unit), catalog.refresh())

        assertEquals(
            listOf(
                PluginSummary(PluginId("letta.chart"), "Chart", "1.2.0", PluginState.Active, PluginHealth.Ok),
                PluginSummary(
                    PluginId("letta.maps"), "Maps", "0.3.0", PluginState.Faulted("crashed 3 times"),
                    PluginHealth.Failed("crashed 3 times"), lastFault = "crashed 3 times",
                ),
                PluginSummary(PluginId("letta.new"), "New", "1.0.0", PluginState.Installed),
            ),
            catalog.plugins.value,
        )
        assertEquals(listOf(true, false, false), catalog.plugins.value.map { it.enabled })
        assertEquals("crashed 3 times", catalog.plugins.value[1].faultReason)
        assertEquals(listOf(PluginAdminRpc.LIST), link.methods)
    }

    @Test
    fun aRefusalCarriesTheHostsReasonAndLeavesTheListAsItWas() = runTest {
        val link = CannedLink(Json.parseToJsonElement(listed))
        val catalog = AdminRpcPluginCatalog(link) { _, _ -> error("unused") }
        catalog.refresh()
        link.refusal = "refused: enable: letta.new has no consent"

        assertEquals(PluginCatalogResult.Refused("refused: enable: letta.new has no consent"), catalog.enable(PluginId("letta.new")))

        assertEquals(3, catalog.plugins.value.size)
        assertEquals("""{"plugin_id":"letta.new"}""", link.params.last().toString())
    }

    @Test
    fun anAnswerTheClientCannotReadIsARefusalNotACrash() = runTest {
        val catalog = AdminRpcPluginCatalog(CannedLink(Json.parseToJsonElement("""{"plugins":"nope"}"""))) { _, _ -> error("unused") }

        val refused = assertIs<PluginCatalogResult.Refused>(catalog.refresh())

        assertTrue(refused.reason.startsWith("the host's answer could not be read"), refused.reason)
    }

    @Test
    fun theManifestTheHostSendsReachesTheSummary() = runTest {
        val manifest = com.letta.mobile.data.plugin.PluginManifest(
            manifestVersion = 1, id = "letta.chart", name = "Chart", version = "1.2.0", publisher = "letta",
            contract = com.letta.mobile.data.plugin.PluginContract(1),
            runtime = com.letta.mobile.data.plugin.PluginRuntime.Process("node", listOf("server.mjs")),
            pages = mapOf("chart" to com.letta.mobile.data.plugin.PluginPage("pages/chart.html")),
        )
        val wire = com.letta.mobile.data.plugin.catalog.PluginPluginsWire(
            listOf(com.letta.mobile.data.plugin.catalog.PluginSummaryWire("letta.chart", "Chart", "1.2.0", PluginState.Active, manifest = manifest)),
        )
        val catalog = AdminRpcPluginCatalog(
            CannedLink(PluginAdminRpc.json.encodeToJsonElement(com.letta.mobile.data.plugin.catalog.PluginPluginsWire.serializer(), wire)),
        ) { _, _ -> error("unused") }

        catalog.refresh()

        assertEquals(manifest, catalog.plugins.value.single().manifest)
    }

    private class CannedLink(private val answer: kotlinx.serialization.json.JsonElement) : PluginAdminLink {
        override val hostCapabilities: Set<String> = setOf(PluginAdminRpc.CAPABILITY)
        val methods = mutableListOf<String>()
        val params = mutableListOf<JsonObject>()
        var refusal: String? = null

        override suspend fun adminRpc(method: String, params: JsonObject): AppServerInboundFrame.AdminRpcResponse {
            methods += method
            this.params += params
            return AppServerInboundFrame.AdminRpcResponse(requestId = "r", success = refusal == null, result = answer.takeIf { refusal == null }, error = refusal)
        }
    }
}
