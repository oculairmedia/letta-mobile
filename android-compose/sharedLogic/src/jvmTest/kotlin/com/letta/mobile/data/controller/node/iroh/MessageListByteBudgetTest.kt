package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-vzn89: `message.list` pages are bounded by bytes as well as count, and a client paging
 * on from the last message it received sees every stored message exactly once, in both orders.
 *
 * The App Server here is [LettaJsMessagePaging], a copy of letta.js 0.34.39's local store paging.
 */
class MessageListByteBudgetTest {
    /** letta.js paging behind the AppServerClient interface; every other native call is unexpected. */
    private class LettaJsLocalStore(conversations: Map<String, List<JsonObject>>) : AppServerClient {
        val paging = LettaJsMessagePaging(conversations)
        val calls get() = paging.calls
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()
        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun sync(command: AppServerCommand.Sync) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
        override suspend fun conversationMessagesList(command: AppServerCommand.ConversationMessagesList) = paging.list(command)
    }

    private data class Walk(val ids: List<String>, val pageBytes: List<Int>, val appServerCalls: Int)

    /** Pages [conversation] the way IrohAdminRpcTimelineTransport does: on from the last message each page delivered. */
    private suspend fun walk(store: LettaJsLocalStore, ascending: Boolean, framePart: Boolean): Walk {
        val router = AdminRpcRouter().also { ConversationAdminHandlers.register(it, NativeReadTiers(nativeClient = store)) }
        val before = store.calls
        val ids = ArrayList<String>()
        val bytes = ArrayList<Int>()
        var cursor: String? = null
        do {
            val params = buildJsonObject {
                put("conversation_id", CONVERSATION)
                put("limit", PAGE_LIMIT.toString())
                put("order", if (ascending) "asc" else "desc")
                cursor?.let { put(if (ascending) "after" else "before", it) }
            }
            val envelope = Json.parseToJsonElement(
                router.dispatch(
                    "walk", "message.list", params,
                    AdminRpcRequestContext(authenticated = true, supportsFrameParts = framePart),
                ),
            ).jsonObject
            assertEquals(true, envelope.getValue("success").jsonPrimitive.booleanOrNull, envelope.toString().take(300))
            val result = envelope.getValue("result")
            val rows = rowsOf(result)
            bytes += result.toString().encodeToByteArray().size
            ids += rows.map { it.rowId() }
            val hasMore = (result as? JsonObject)?.get("has_more")?.jsonPrimitive?.booleanOrNull ?: (rows.size >= PAGE_LIMIT)
            val lastDelivered = rows.lastOrNull()?.rowId()
            val explicit = (result as? JsonObject)?.get("next_before")?.jsonPrimitive?.contentOrNull.takeUnless { ascending }
            cursor = if (hasMore) explicit ?: lastDelivered else null
            assertTrue(ids.size <= STORED_ROWS * 2, "walk must terminate")
        } while (cursor != null)
        return Walk(if (ascending) ids else ids.reversed(), bytes, store.calls - before)
    }

    @Test
    fun pagesOfLargeToolOutputsStayUnderTheByteBudgetAndLoseNoMessage() = runTest {
        val stored = conversation()
        val store = LettaJsLocalStore(mapOf(CONVERSATION to stored))
        for (framePart in listOf(true, false)) {
            for (ascending in listOf(false, true)) {
                val walk = walk(store, ascending, framePart)
                println(
                    "message.list walk order=${if (ascending) "asc" else "desc"} framePart=$framePart: " +
                        "pages=${walk.pageBytes.size} appServerCalls=${walk.appServerCalls} " +
                        "maxPageBytes=${walk.pageBytes.max()} totalBytes=${walk.pageBytes.sum()} rows=${walk.ids.size}/${stored.size}",
                )
                assertEquals(stored.map { it.rowId() }, walk.ids, "every stored message exactly once, in order")
                walk.pageBytes.forEach { size ->
                    assertTrue(size <= MessageListPageGuard.MAX_PAGE_BYTES, "page of $size bytes exceeds the byte budget")
                }
            }
        }
    }

    @Test
    fun absentOrderIsTheAppServerDescendingDefault() = runTest {
        val stored = conversation()
        val store = LettaJsLocalStore(mapOf(CONVERSATION to stored))
        val router = AdminRpcRouter().also { ConversationAdminHandlers.register(it, NativeReadTiers(nativeClient = store)) }
        val result = Json.parseToJsonElement(
            router.dispatch("default-order", "message.list", buildJsonObject { put("conversation_id", CONVERSATION) }),
        ).jsonObject.getValue("result")
        val rows = rowsOf(result)
        assertEquals(stored.last().rowId(), rows.first().rowId(), "the newest message opens the default page")
        assertEquals(rows.last().rowId(), result.jsonObject.getValue("next_before").jsonPrimitive.content)
    }

    @Test
    fun unknownConversationFailsAsTheAppServerDoes() = runTest {
        val store = LettaJsLocalStore(emptyMap())
        val router = AdminRpcRouter().also { ConversationAdminHandlers.register(it, NativeReadTiers(nativeClient = store)) }
        val envelope = Json.parseToJsonElement(
            router.dispatch("missing", "message.list", buildJsonObject { put("conversation_id", "local-conv-missing") }),
        ).jsonObject
        assertEquals(false, envelope.getValue("success").jsonPrimitive.booleanOrNull)
    }

    private fun rowsOf(result: JsonElement): List<JsonObject> =
        (if (result is JsonArray) result else result.jsonObject.getValue("messages").jsonArray).map { it.jsonObject }

    /**
     * A conversation in the App Server's stored projection, oldest first: chat turns, single image tool
     * calls, a batch of three image calls whose returns follow all three calls, and a call that stays
     * open while two large assistant messages stream before it returns.
     */
    private fun conversation(): List<JsonObject> = buildList {
        var n = 0
        fun next(prefix: String) = "$prefix-${n++}"
        fun chat() {
            add(row(next("user"), "user_message") { put("content", "question ${n}") })
            add(row(next("assistant"), "assistant_message") { put("content", text("answer ".repeat(40))) })
        }
        fun call(id: String) = add(
            row(next("call"), "approval_request_message") {
                put("tool_call", buildJsonObject { put("tool_call_id", id); put("name", "ViewImage"); put("arguments", "{}") })
            },
        )
        fun imageReturn(id: String) = add(
            row(next("return"), "tool_return_message") {
                put("tool_call_id", id)
                put("status", "success")
                put("tool_return", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", "viewed") })
                    add(buildJsonObject {
                        put("type", "image")
                        put(
                            "source",
                            buildJsonObject { put("type", "base64"); put("media_type", "image/png"); put("data", "A".repeat(IMAGE_BYTES)) },
                        )
                    })
                })
            },
        )
        repeat(12) { chat() }
        repeat(4) { i -> call("single-$i"); imageReturn("single-$i"); chat() }
        listOf("batch-a", "batch-b", "batch-c").also { batch -> batch.forEach(::call); batch.forEach(::imageReturn) }
        chat()
        call("long")
        repeat(2) { add(row(next("assistant"), "assistant_message") { put("content", text("x".repeat(LARGE_ASSISTANT_BYTES))) }) }
        add(
            row(next("return"), "tool_return_message") {
                put("tool_call_id", "long"); put("status", "success"); put("tool_return", "done")
            },
        )
        repeat(2) { chat() }
        add(row(next("assistant"), "assistant_message") { put("content", text("y".repeat(LARGE_ASSISTANT_BYTES))) })
        repeat(20) { chat() }
    }.also { assertTrue(it.size <= STORED_ROWS) }

    private fun row(id: String, type: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("id", id)
        put("date", "2026-01-01T00:00:00Z")
        put("conversation_id", CONVERSATION)
        put("message_type", type)
        body()
    }

    private fun text(value: String) = buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", value) }) }

    private companion object {
        const val CONVERSATION = "local-conv-bytes"
        const val PAGE_LIMIT = 50
        const val DEFAULT_APP_SERVER_PAGE = 50
        const val STORED_ROWS = 200
        const val IMAGE_BYTES = 250_000
        const val LARGE_ASSISTANT_BYTES = 320_000
    }
}
