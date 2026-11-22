package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The App Server's `conversation_messages_list` over its strict local store, copied from letta.js 0.34.39
 * (`listConversationMessagePage` over `LocalStore.listConversationMessages` / `applyListOptions`):
 *
 * - an unknown conversation fails with `Conversation <id> not found`;
 * - a `before` / `after` cursor it cannot find is ignored, a found one cuts the stored rows there;
 * - `order` is ascending only for `"asc"`; anything else, or none, lists newest first;
 * - a `limit` that is not a positive JSON number lists everything, and the page is then cut to 50.
 */
internal class LettaJsMessagePaging(private val conversations: Map<String, List<JsonObject>>) {
    var calls = 0
        private set

    fun list(command: AppServerCommand.ConversationMessagesList): AppServerInboundFrame.ConversationMessagesListResponse {
        calls++
        val stored = conversations[command.conversationId]
            ?: return AppServerInboundFrame.ConversationMessagesListResponse(
                command.requestId, false, JsonArray(emptyList()), "Conversation ${command.conversationId} not found",
            )
        val query = command.query ?: JsonObject(emptyMap())
        fun cursor(key: String) = (query[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        var items = stored
        cursor("before")?.let { before -> items.indexOfFirst { it.rowId() == before }.takeIf { it >= 0 }?.let { items = items.take(it) } }
        cursor("after")?.let { after -> items.indexOfFirst { it.rowId() == after }.takeIf { it >= 0 }?.let { items = items.drop(it + 1) } }
        if ((query["order"] as? JsonPrimitive)?.contentOrNull != "asc") items = items.reversed()
        val limit = (query["limit"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 }?.toInt()
        val page = items.take(limit ?: items.size).take(limit ?: APP_SERVER_PAGE_LIMIT)
        return AppServerInboundFrame.ConversationMessagesListResponse(command.requestId, true, JsonArray(page))
    }

    companion object {
        private const val APP_SERVER_PAGE_LIMIT = 50

        /**
         * The stored rows of a contract fixture's `host_state.messages` (`{conversation id: [row, ...]}`, oldest
         * first). A row `{"synthetic_image_return": n, "id", "tool_call_id"}` stands for the App Server's
         * projection of an image tool result whose base64 data is n bytes of `A` (the file would otherwise
         * be megabytes).
         */
        fun fromFixture(messages: JsonObject): LettaJsMessagePaging = LettaJsMessagePaging(
            messages.mapValues { (_, rows) -> (rows as JsonArray).map { expand(it.jsonObject) } },
        )

        /** [element] with every synthetic row expanded, for comparing a served page with a fixture's expected response. */
        fun expanded(element: JsonElement): JsonElement = when (element) {
            is JsonObject ->
                if ("synthetic_image_return" in element) expand(element) else JsonObject(element.mapValues { expanded(it.value) })
            is JsonArray -> JsonArray(element.map(::expanded))
            else -> element
        }

        /** A synthetic image return as the App Server projects it; any other field the row carries is kept. */
        private fun expand(row: JsonObject): JsonObject {
            val size = (row["synthetic_image_return"] as? JsonPrimitive)?.int ?: return row
            return JsonObject(
                row - "synthetic_image_return" + mapOf(
                    "message_type" to JsonPrimitive("tool_return_message"),
                    "status" to JsonPrimitive("success"),
                    "tool_return" to buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "viewed") })
                        add(buildJsonObject {
                            put("type", "image")
                            put(
                                "source",
                                buildJsonObject { put("type", "base64"); put("media_type", "image/png"); put("data", "A".repeat(size)) },
                            )
                        })
                    },
                ),
            )
        }
    }
}

internal fun JsonElement.rowId(): String = jsonObject.getValue("id").jsonPrimitive.content
