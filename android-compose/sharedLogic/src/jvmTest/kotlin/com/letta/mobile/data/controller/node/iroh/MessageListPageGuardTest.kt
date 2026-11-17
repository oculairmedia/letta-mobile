package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * letta-mobile-c4igq.9: the message.list page-size guard must keep every page
 * under the frame cap and NEVER produce a response_too_large — the serve path
 * paginates a huge (synthetic) transcript instead of shipping one giant frame.
 */
class MessageListPageGuardTest {

    @Test
    fun singleReturnBeyondLogicalCapKeepsExplicitTooLargeImage() {
        val returned = imageReturn(33_554_432)
        val result = MessageListPageGuard.bound(JsonArray(listOf(returned)))
        assertTrue(byteLen(result) < 33_554_432, "logical cap must strip inline bytes, not lose the page")
        val kept = pageRows(result).single().jsonObject
        assertEquals("image-result", kept.getValue("id").jsonPrimitive.content)
        assertTrue(kept.toString().contains("too_large"), "explicit unavailable marker must survive")
    }

    @Test
    fun peerWithoutFramePartsGetsUsableImagePageFromHandler() = kotlinx.coroutines.test.runTest {
        val page = JsonArray(listOf(imageCall(), imageReturn(2_000_000)))
        val client = object : com.letta.mobile.data.transport.appserver.AppServerClient {
            override val events = kotlinx.coroutines.flow.emptyFlow<com.letta.mobile.data.transport.appserver.AppServerReceivedFrame>()
            override suspend fun runtimeStart(command: com.letta.mobile.data.transport.appserver.AppServerCommand.RuntimeStart) = error("unused")
            override suspend fun input(command: com.letta.mobile.data.transport.appserver.AppServerCommand.Input) = error("unused")
            override suspend fun sync(command: com.letta.mobile.data.transport.appserver.AppServerCommand.Sync) = error("unused")
            override suspend fun abort(command: com.letta.mobile.data.transport.appserver.AppServerCommand.AbortMessage) = error("unused")
            override suspend fun adminRpc(command: com.letta.mobile.data.transport.appserver.AppServerCommand.AdminRpc) = error("unused")
            override suspend fun sendExternalToolResponse(command: com.letta.mobile.data.transport.appserver.AppServerCommand.ExternalToolCallResponse) = error("unused")
            override suspend fun conversationMessagesList(command: com.letta.mobile.data.transport.appserver.AppServerCommand.ConversationMessagesList): com.letta.mobile.data.transport.appserver.AppServerInboundFrame.ConversationMessagesListResponse {
                val owners = mapOf("image-conversation" to "image-agent")
                val loadedAgents = setOf("image-agent")
                if (owners[command.conversationId] !in loadedAgents) {
                    return com.letta.mobile.data.transport.appserver.AppServerInboundFrame.ConversationMessagesListResponse(
                        command.requestId, false, JsonArray(emptyList()), "Conversation or loaded agent not found",
                    )
                }
                assertEquals("50", command.query?.get("limit")?.jsonPrimitive?.content)
                assertTrue(command.query?.keys.orEmpty().all { it == "limit" }, "fixture is a first page in default order")
                return com.letta.mobile.data.transport.appserver.AppServerInboundFrame.ConversationMessagesListResponse(
                    command.requestId, true, page,
                )
            }
        }
        val router = AdminRpcRouter().also { ConversationAdminHandlers.register(it, NativeReadTiers(nativeClient = client)) }
        for (frameParts in listOf(false, true)) {
            val envelope = kotlinx.serialization.json.Json.parseToJsonElement(router.dispatch(
                requestId = "image-page", method = "message.list",
                params = buildJsonObject { put("conversation_id", "image-conversation") },
                context = AdminRpcRequestContext(authenticated = true, supportsFrameParts = frameParts),
            )).jsonObject
            assertEquals("true", envelope.getValue("success").jsonPrimitive.content)
            val rows = pageRows(envelope.getValue("result"))
            assertEquals(listOf("image-request", "image-result"), rows.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            if (frameParts) {
                assertEquals(page.toList(), rows, "negotiated peer retains the inline image")
            } else {
                assertTrue(byteLen(envelope) <= 921_600, "capability-off peer must receive a usable page")
                assertTrue(rows.last().toString().contains("too_large"))
            }
        }
    }

    @Test
    fun framePartPageKeepsLargeImagePayloadAndCall() {
        val rows = listOf(imageCall(), imageReturn(2_000_000))
        assertEquals(rows, pageRows(MessageListPageGuard.bound(JsonArray(rows))))
    }

    private fun imageCall() = buildJsonObject {
        put("id", "image-request")
        put("message_type", "tool_call_message")
        put("tool_call", buildJsonObject { put("tool_call_id", "image-call"); put("name", "ViewImage"); put("arguments", "{}") })
    }

    private fun imageReturn(size: Int) = buildJsonObject {
        put("id", "image-result")
        put("message_type", "tool_return_message")
        put("tool_call_id", "image-call")
        put("tool_return", buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "image result") })
            add(buildJsonObject { put("type", "image"); put("data", "x".repeat(size)); put("mimeType", "image/png") })
        })
    }

    @Test
    fun oversizedToolOutputKeepsItsCallAcrossBothPageOrders() {
        val call = buildJsonObject {
            put("id", "image-request")
            put("message_type", "tool_call_message")
            put("tool_call", buildJsonObject { put("tool_call_id", "image-call") })
        }
        val returned = buildJsonObject {
            put("id", "image-result")
            put("message_type", "tool_return_message")
            put("tool_call_id", "image-call")
            put("tool_return", "x".repeat(MessageListPageGuard.MAX_PAGE_BYTES))
        }
        for (ascending in listOf(true, false)) {
            val rows = listOf(msg("older", 100), call, returned).let { if (ascending) it else it.reversed() }
            val bounded = MessageListPageGuard.bound(JsonArray(rows), newestLast = ascending).jsonObject
            val kept = bounded.getValue("messages").jsonArray
            assertEquals(if (ascending) listOf(call, returned) else listOf(returned, call), kept.toList())
            assertEquals("image-request", bounded.getValue("next_before").jsonPrimitive.content)
            assertTrue(bounded.getValue("has_more").jsonPrimitive.content.toBoolean())
        }
    }

    @Test
    fun duplicateRowsOutsideTheKeptWindowDoNotExpandIt() {
        val duplicate = msg("duplicate", 20)
        val rows = JsonArray(listOf(duplicate, msg("middle", 1000), duplicate))
        val result = MessageListPageGuard.bound(rows, maxPageBytes = 200)
        assertEquals(listOf(duplicate), pageRows(result))
    }

    @Test
    fun oversizedDependencyWindowFallsBackToNewestReturn() {
        val call = buildJsonObject {
            put("id", "call")
            put("tool_call", buildJsonObject { put("tool_call_id", "c") })
        }
        val returned = buildJsonObject {
            put("id", "return")
            put("tool_call_id", "c")
        }
        val gap = msg("gap", 33_554_432)
        val result = MessageListPageGuard.bound(JsonArray(listOf(call, gap, returned)), maxPageBytes = 100)
        assertEquals(listOf(returned), pageRows(result))
    }

    @Test
    fun callOutsideProjectedPageCannotBeRestored() {
        val returned = buildJsonObject {
            put("id", "return")
            put("tool_call_id", "outside-page")
        }
        val result = MessageListPageGuard.bound(JsonArray(listOf(msg("older", 500), returned)), maxPageBytes = 100)
        assertEquals(listOf(returned), result.jsonObject.getValue("messages").jsonArray.toList())
    }

    private fun pageRows(result: kotlinx.serialization.json.JsonElement): List<kotlinx.serialization.json.JsonElement> =
        if (result is JsonArray) result.toList() else result.jsonObject.getValue("messages").jsonArray.toList()

    /** A synthetic message with a body of [bodyBytes] filler. */
    private fun msg(id: String, bodyBytes: Int): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("message_type", JsonPrimitive("assistant_message"))
        put("content", JsonPrimitive("x".repeat(bodyBytes)))
    }

    private fun byteLen(el: kotlinx.serialization.json.JsonElement) = el.toString().encodeToByteArray().size

    @Test
    fun largeSyntheticConversationIsBoundedUnderTheCapWithCursor() {
        // ~5MB synthetic transcript: 500 messages x ~10KB each — like a long history.
        val messages = buildJsonArray {
            repeat(500) { i -> add(msg("m-%04d".format(i), bodyBytes = 10_000)) }
        }
        assertTrue(byteLen(messages) > MessageListPageGuard.MAX_PAGE_BYTES, "fixture must exceed the page budget")

        val bounded = MessageListPageGuard.bound(messages, newestLast = true)

        // The result must be a wrapped object with has_more + a cursor, under the cap.
        val obj = bounded as JsonObject
        assertTrue(byteLen(obj) <= MessageListPageGuard.MAX_PAGE_BYTES, "bounded page must be under the frame budget (no response_too_large)")
        assertEquals(true, obj["has_more"]?.jsonPrimitive?.contentOrNull?.toBoolean())
        val kept = (obj["messages"] as JsonArray)
        assertTrue(kept.isNotEmpty() && kept.size < 500, "must trim to a bounded newest window")
        // Kept the NEWEST rows (highest indices), so the tail is present.
        val keptIds = kept.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
        assertTrue(keptIds.contains("m-0499"), "the newest message must be kept (chat opens at the tail)")
        assertFalse(keptIds.contains("m-0000"), "the oldest message must be trimmed to the next page")
        // The continuation cursor is the oldest KEPT id, for a `before` query.
        assertEquals(keptIds.first(), obj["next_before"]?.jsonPrimitive?.content)
    }

    @Test
    fun `newestLast false cursor points at the oldest kept row not the newest - P0_2`() {
        // Input is newest->oldest (newest at index 0). The guard keeps the newest
        // window (the front) and trims the oldest (the tail); next_before must be
        // the OLDEST KEPT id so the next `before` query walks strictly older.
        val messages = buildJsonArray {
            repeat(500) { i -> add(msg("m-%04d".format(i), bodyBytes = 10_000)) }
        }
        val obj = MessageListPageGuard.bound(messages, newestLast = false) as JsonObject
        val kept = (obj["messages"] as JsonArray).map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
        assertTrue(kept.contains("m-0000"), "newest (index 0) kept")
        assertFalse(kept.contains("m-0499"), "oldest trimmed to the next page")
        val cursor = obj["next_before"]?.jsonPrimitive?.content
        // The oldest kept id is the LAST element in this mode — NOT kept.first() (the newest).
        assertEquals(kept.last(), cursor, "cursor must be the oldest kept id")
        assertFalse(cursor == kept.first(), "regression: cursor must not be the newest kept id")
    }

    @Test
    fun `descending handler page keeps newest rows when byte bounded`() = kotlinx.coroutines.test.runTest {
        val page = buildJsonArray {
            repeat(500) { index -> add(msg("m-%04d".format(index), bodyBytes = 10_000)) }
        }
        val client = object : com.letta.mobile.data.transport.appserver.AppServerClient {
            override val events = kotlinx.coroutines.flow.emptyFlow<com.letta.mobile.data.transport.appserver.AppServerReceivedFrame>()
            override suspend fun runtimeStart(command: com.letta.mobile.data.transport.appserver.AppServerCommand.RuntimeStart) = error("unused")
            override suspend fun input(command: com.letta.mobile.data.transport.appserver.AppServerCommand.Input) = error("unused")
            override suspend fun sync(command: com.letta.mobile.data.transport.appserver.AppServerCommand.Sync) = error("unused")
            override suspend fun abort(command: com.letta.mobile.data.transport.appserver.AppServerCommand.AbortMessage) = error("unused")
            override suspend fun adminRpc(command: com.letta.mobile.data.transport.appserver.AppServerCommand.AdminRpc) = error("unused")
            override suspend fun sendExternalToolResponse(command: com.letta.mobile.data.transport.appserver.AppServerCommand.ExternalToolCallResponse) = error("unused")
            override suspend fun conversationMessagesList(command: com.letta.mobile.data.transport.appserver.AppServerCommand.ConversationMessagesList) =
                com.letta.mobile.data.transport.appserver.AppServerInboundFrame.ConversationMessagesListResponse(
                    command.requestId,
                    true,
                    page,
                )
        }
        val router = AdminRpcRouter().also {
            ConversationAdminHandlers.register(it, NativeReadTiers(nativeClient = client))
        }
        val envelope = kotlinx.serialization.json.Json.parseToJsonElement(
            router.dispatch(
                requestId = "desc-page",
                method = "message.list",
                params = buildJsonObject {
                    put("conversation_id", JsonPrimitive("conv-1"))
                    put("order", JsonPrimitive("desc"))
                },
            ),
        ).jsonObject
        val result = envelope.getValue("result").jsonObject
        val kept = result.getValue("messages").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

        assertEquals("m-0000", kept.first(), "descending page must retain its newest row")
        assertFalse("m-0499" in kept, "descending page must trim the oldest tail")
        assertEquals(kept.last(), result.getValue("next_before").jsonPrimitive.content)
    }

    @Test
    fun smallConversationIsReturnedUnchanged() {
        val messages = buildJsonArray { repeat(5) { i -> add(msg("m-$i", bodyBytes = 100)) } }
        val bounded = MessageListPageGuard.bound(messages)
        // Byte-for-byte identical: small hydration is untouched.
        assertEquals(messages, bounded)
    }

    @Test
    fun objectStringFieldsAreBoundedForOversizedContext() {
        // agent.context shape: counts + a huge memory string.
        val context = buildJsonObject {
            put("num_messages", JsonPrimitive(42))
            put("core_memory", JsonPrimitive("m".repeat(2_000_000))) // 2MB memory block
        }
        assertTrue(byteLen(context) > MessageListPageGuard.MAX_PAGE_BYTES)

        val bounded = MessageListPageGuard.boundObjectStringFields(context) as JsonObject
        assertTrue(byteLen(bounded) <= MessageListPageGuard.MAX_PAGE_BYTES, "context must be bounded under the cap")
        assertEquals(42, bounded["num_messages"]?.jsonPrimitive?.content?.toInt(), "counts untouched")
        assertTrue(bounded["core_memory"]!!.jsonPrimitive.content.contains("[truncated"), "oversized string truncated with marker")
    }

    @Test
    fun smallContextIsReturnedUnchanged() {
        val context = buildJsonObject { put("num_messages", JsonPrimitive(1)); put("system_prompt", JsonPrimitive("short")) }
        assertEquals(context, MessageListPageGuard.boundObjectStringFields(context))
    }

    @Test
    fun `dropField removes the heavy agent-context messages array but keeps the counts`() {
        // Mirror the live /context shape: a giant inline `messages` array (the 96MB
        // killer) alongside the counts the client actually reads.
        val context = buildJsonObject {
            put("messages", buildJsonArray { repeat(5000) { add(buildJsonObject { put("id", JsonPrimitive("m-$it")) }) } })
            put("num_messages", JsonPrimitive(5000))
            put("context_window_size_current", JsonPrimitive(395000))
            put("system_prompt", JsonPrimitive("you are meridian"))
        }
        val before = context.toString().encodeToByteArray().size

        val stripped = MessageListPageGuard.dropField(context, "messages").jsonObject
        assertTrue("messages" !in stripped, "messages array must be dropped")
        assertEquals(5000, stripped["num_messages"]!!.jsonPrimitive.content.toInt(), "count preserved")
        assertEquals(395000, stripped["context_window_size_current"]!!.jsonPrimitive.content.toInt())
        assertEquals("you are meridian", stripped["system_prompt"]!!.jsonPrimitive.content)
        val after = stripped.toString().encodeToByteArray().size
        assertTrue(after < before / 100, "dropping messages must massively shrink the response ($before -> $after)")
    }

    @Test
    fun `dropField is a no-op when the field is absent or the response is not an object`() {
        val obj = buildJsonObject { put("num_messages", JsonPrimitive(3)) }
        assertEquals(obj, MessageListPageGuard.dropField(obj, "messages"))
        val arr = buildJsonArray { add(JsonPrimitive(1)) }
        assertEquals(arr, MessageListPageGuard.dropField(arr, "messages"))
    }

    /**
     * letta-mobile-w9k3f: an unrecognised body (neither a bare array nor
     * { messages: [...] }) must still pass through UNCHANGED — the guard bounds
     * pages, it does not reshape or reject. This pins that contract while the
     * diagnostic added alongside it reports the shape, so a future change cannot
     * quietly start rewriting or dropping an unknown response.
     */
    @Test
    fun unrecognisedResponseShapePassesThroughUnchanged() {
        val errorEnvelope = buildJsonObject {
            put("error", JsonPrimitive("boom"))
            put("code", JsonPrimitive(500))
        }
        assertEquals(errorEnvelope, MessageListPageGuard.bound(errorEnvelope))

        val altKeyEnvelope = buildJsonObject {
            put("data", buildJsonArray { add(msg("m1", 10)) })
        }
        assertEquals(altKeyEnvelope, MessageListPageGuard.bound(altKeyEnvelope))
    }
}
