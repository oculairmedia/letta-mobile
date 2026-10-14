package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasRelayHost
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasCaller
import com.letta.mobile.data.canvas.HostCanvasEntry
import com.letta.mobile.data.canvas.InMemoryCanvasRelayStore
import com.letta.mobile.data.canvas.InMemoryHostCanvasDirectory
import com.letta.mobile.data.canvas.plugin.InMemoryPluginKindCatalog
import com.letta.mobile.data.canvas.plugin.PluginKindSpec
import com.letta.mobile.data.plugin.SecretScrubber
import com.letta.mobile.data.plugin.wire.LogParams
import com.letta.mobile.data.storage.InMemoryAssetStore
import com.letta.mobile.plugin.api.ElementFallback
import com.letta.mobile.plugin.api.PlaceElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A real host canvas backend over an in-memory relay, one board, and the handlers of [PLUGIN]. */
class PluginHostRig(
    val limits: PluginRateLimits = PluginRateLimits(),
    val quota: PluginAssetQuota = PluginAssetQuota(),
    secrets: List<String> = emptyList(),
) {
    val store = InMemoryCanvasRelayStore()
    val backend = HostCanvasBackend(CanvasRelayHost(store, hostId = { "host-1" }), store, InMemoryHostCanvasDirectory(), kinds = catalog)
    val assets = InMemoryAssetStore()
    val refusals = PluginRefusals()
    val logged = mutableListOf<Pair<String, LogParams>>()
    var board: HostCanvasEntry? = null
    private var nextId = 0

    val placement = PlacementCompiler(PLUGIN, catalog, backend, PluginCanvasAccess { board }, refusals, newIdSuffix = { "e${++nextId}" })
    val handlers = HostLcpHandlers(PLUGIN, placement, assets, SecretScrubber(secrets), limits, quota, refusals) { id, line -> logged += id to line }

    suspend fun openBoard(): HostCanvasEntry = backend.create(HostCanvasCaller("agent-1"), "Board").also { board = it }

    /** Every op in the board's log, batches flattened. */
    suspend fun written(): List<CanvasOp> = board?.let { entry ->
        store.readAfter(entry.topic, 0L).map { it.op }.flatMap { if (it is CanvasOp.BatchOp) listOf(it) + it.ops else listOf(it) }
    }.orEmpty()

    companion object {
        const val PLUGIN = "letta.chart"
        const val TYPE = "ext:letta.chart/chart"

        private val schema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","additionalProperties":false,"required":["title"],"properties":{"title":{"type":"string","maxLength":1024}}}""",
        ).jsonObject

        val catalog = InMemoryPluginKindCatalog(
            listOf(PluginKindSpec(TYPE, 1, schema), PluginKindSpec("ext:other.plugin/chart", 1, schema)),
        )

        fun props(title: String): JsonObject = buildJsonObject { put("title", title) }

        fun place(kind: String = "chart", props: JsonObject = props("Sales")) = PlaceElement(
            kind = kind,
            v = 1,
            props = props,
            fallback = ElementFallback(title = "Chart", openUrl = "https://example.com/chart"),
        )
    }
}
