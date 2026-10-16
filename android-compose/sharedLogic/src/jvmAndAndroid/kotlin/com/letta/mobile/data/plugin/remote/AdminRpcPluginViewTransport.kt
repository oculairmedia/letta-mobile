package com.letta.mobile.data.plugin.remote

import com.letta.mobile.data.plugin.catalog.PluginAdminRpc
import com.letta.mobile.data.plugin.catalog.PluginCatalogResult
import com.letta.mobile.data.plugin.catalog.PluginPageWire
import com.letta.mobile.data.plugin.catalog.PluginViewActionWire
import com.letta.mobile.data.plugin.catalog.PluginViewAnswerWire
import com.letta.mobile.data.plugin.view.PluginViewActionCall
import com.letta.mobile.data.plugin.view.PluginViewActionOutcome
import com.letta.mobile.data.plugin.view.PluginViewPageRef
import com.letta.mobile.data.plugin.view.PluginViewTransport
import com.letta.mobile.data.plugin.view.PluginViewUnavailableException
import com.letta.mobile.data.repository.IrohAdminRpcClient
import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlin.io.encoding.Base64

/**
 * letta-mobile-cdgpr.7: a live view's [PluginViewTransport] over the host's admin_rpc connection,
 * in place of the dedicated `meridian/plugin-view/1` ALPN (letta-mobile-s416w.32): admin_rpc already
 * carries the peer's authentication and capabilities. Pages come from `plugins.page.read`; actions
 * go to `plugins.view.action`, where the host checks them against the manifest again. On a host
 * without `plugins_v1` nothing is sent: pages are unavailable and actions answer Unavailable.
 */
class AdminRpcPluginViewTransport(private val link: PluginAdminLink) : PluginViewTransport {
    constructor(client: IrohAdminRpcClient) : this(client.asPluginAdminLink())

    /** [page]'s document with its manifest entry and the actions its view may call. */
    suspend fun page(page: PluginViewPageRef): PluginCatalogResult<PluginPageWire> {
        val params = buildJsonObject {
            put(PluginAdminRpc.PLUGIN_ID, page.pluginId)
            put(PluginAdminRpc.PAGE_ID, page.pageId)
            put(PluginAdminRpc.VERSION, page.version)
        }
        return link.call(PluginAdminRpc.PAGE_READ, params)
            .read { LettaJson.wire.decodeFromJsonElement(PluginPageWire.serializer(), it) }
    }

    override suspend fun readPage(page: PluginViewPageRef): ByteArray = when (val read = page(page)) {
        is PluginCatalogResult.Refused -> throw PluginViewUnavailableException("$page: ${read.reason}")
        is PluginCatalogResult.Ok -> Base64.decode(read.value.document)
    }

    override suspend fun action(call: PluginViewActionCall): PluginViewActionOutcome {
        if (!link.supportsPlugins) return PluginViewActionOutcome.Unavailable(PLUGINS_UNSUPPORTED)
        val wire = PluginViewActionWire(call.pluginId, call.canvasId, call.elementId, call.action, call.input)
        val params = LettaJson.wire.encodeToJsonElement(PluginViewActionWire.serializer(), wire).jsonObject
        val answer = link.call(PluginAdminRpc.VIEW_ACTION, params)
            .read { LettaJson.wire.decodeFromJsonElement(PluginViewAnswerWire.serializer(), it) }
        return when (answer) {
            is PluginCatalogResult.Refused -> PluginViewActionOutcome.Failed(answer.reason)
            is PluginCatalogResult.Ok -> outcome(answer.value)
        }
    }

    private fun outcome(answer: PluginViewAnswerWire): PluginViewActionOutcome = when (answer.status) {
        PluginViewAnswerWire.DONE -> PluginViewActionOutcome.Done(answer.text.orEmpty(), answer.structured)
        PluginViewAnswerWire.UNAVAILABLE -> PluginViewActionOutcome.Unavailable(answer.message ?: "the plugin is not running")
        else -> PluginViewActionOutcome.Failed(answer.message ?: "the action failed")
    }
}
