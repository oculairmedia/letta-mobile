package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
}
