package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.runtime.ConversationRowsSource
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference

/**
 * The turn identity ledger's rows source: `conversationMessagesList`, newest first. Pages of
 * [PAGE_LIMIT] walk back with the `before` cursor, because one oversized App Server frame kills
 * the shared connection (see [ConversationAdminHandlers.MESSAGE_GET_PAGE_LIMIT]).
 */
internal class AppServerConversationRows(private val client: AppServerClient) : ConversationRowsSource {
    override suspend fun newestRows(conversationId: String, limit: Int): List<JsonObject> {
        val collected = ArrayList<JsonObject>()
        var before: String? = null
        while (collected.size < limit) {
            val size = minOf(PAGE_LIMIT, limit - collected.size)
            val page = page(conversationId, size, before)
            // Rows message.list would not serve (synthetic skill envelopes) never open a turn for a client.
            collected += page.filter { MessageListWireProjection.projectMessage(it, conversationId) != null }
            before = page.lastOrNull()?.get("id")?.jsonPrimitive?.contentOrNull
            if (page.size < size || before == null) break
        }
        return collected
    }

    private suspend fun page(conversationId: String, size: Int, before: String?): List<JsonObject> {
        val response = client.conversationMessagesList(
            AppServerCommand.ConversationMessagesList(
                requestId = NativeAdmin.requestId(),
                conversationId = conversationId,
                query = NativeAdmin.queryOf("limit" to size.toString(), "order" to "desc", "before" to before),
            ),
        )
        if (!response.success) return emptyList()
        return (response.messages as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this?.toList() ?: emptyList()

    private companion object {
        const val PAGE_LIMIT = 100
    }
}

/**
 * A [ConversationRowsSource] whose App Server client arrives after the ledger is built: the Iroh
 * endpoint (and so the ledger) exists before the controller and its native client do.
 */
class LateBoundConversationRows : ConversationRowsSource {
    private val delegate = AtomicReference<ConversationRowsSource?>(null)

    fun bind(client: AppServerClient) {
        delegate.set(AppServerConversationRows(client))
    }

    override suspend fun newestRows(conversationId: String, limit: Int): List<JsonObject> =
        delegate.get()?.newestRows(conversationId, limit).orEmpty()
}
