package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MessageListWireProjectionTest {
    private val threshold = MessageListWireProjection.TOOL_RETURN_PROJECTION_THRESHOLD_BYTES
    private val previewBytes = MessageListWireProjection.TOOL_RETURN_PREVIEW_BYTES

    private fun toolReturnMessage(body: String, id: String = "msg-1"): JsonObject = buildJsonObject {
        put("id", id)
        put("message_type", "tool_return_message")
        put("tool_call_id", "call-1")
        put("status", "success")
        put("tool_return", body)
    }

    @Test
    fun bodyAtThresholdIsNotProjected() {
        val body = "a".repeat(threshold)
        val projected = MessageListWireProjection.projectMessage(toolReturnMessage(body), "conv-1")

        assertEquals(body, projected.getValue("tool_return").jsonPrimitive.content)
        assertNull(projected["tool_return_truncated"])
        assertNull(projected["tool_return_byte_len"])
        assertNull(projected["tool_return_pointer"])
    }

    @Test
    fun bodyOneByteOverThresholdIsProjectedWithPreviewAndPointer() {
        val body = "a".repeat(threshold + 1)
        val projected = MessageListWireProjection.projectMessage(toolReturnMessage(body), "conv-1")

        val preview = projected.getValue("tool_return").jsonPrimitive.content
        assertTrue(preview.startsWith("a".repeat(previewBytes)))
        assertTrue(preview.contains("[truncated"))
        assertTrue(
            MessageListWireProjection.utf8ByteLength(preview) < threshold,
            "preview (+marker) must be well under the projection threshold",
        )
        assertTrue(projected.getValue("tool_return_truncated").jsonPrimitive.boolean)
        assertEquals((threshold + 1).toLong(), projected.getValue("tool_return_byte_len").jsonPrimitive.long)
        val pointer = projected.getValue("tool_return_pointer").jsonObject
        assertEquals("tool_return.get", pointer.getValue("method").jsonPrimitive.content)
        assertEquals("conv-1", pointer.getValue("conversation_id").jsonPrimitive.content)
        assertEquals("msg-1", pointer.getValue("message_id").jsonPrimitive.content)
    }

    @Test
    fun previewNeverSplitsAMultiByteCodePoint() {
        // 3-byte UTF-8 chars; 2048 is not a multiple of 3 so a naive cut splits one.
        val body = "€".repeat(threshold) // euro sign, 3 bytes each
        val projected = MessageListWireProjection.projectMessage(toolReturnMessage(body), "conv-1")
        val preview = projected.getValue("tool_return").jsonPrimitive.content
        val kept = preview.substringBefore("\n…")
        assertTrue(MessageListWireProjection.utf8ByteLength(kept) <= previewBytes)
        assertTrue(kept.all { it == '€' })
    }

    @Test
    fun structuredToolReturnBodyIsProjectedFromItsSerializedForm() {
        val big = buildJsonObject { put("payload", "x".repeat(threshold * 2)) }
        val message = buildJsonObject {
            put("id", "msg-2")
            put("message_type", "tool_return_message")
            put("tool_return", big)
        }
        val projected = MessageListWireProjection.projectMessage(message, "conv-1")

        assertTrue(projected.getValue("tool_return").jsonPrimitive.isString)
        assertTrue(projected.getValue("tool_return_truncated").jsonPrimitive.boolean)
    }

    @Test
    fun toolReturnsEntriesAndStdoutAreProjected() {
        val message = buildJsonObject {
            put("id", "msg-3")
            put("message_type", "tool_return_message")
            put(
                "tool_returns",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("tool_call_id", "call-1")
                            put("status", "success")
                            put("func_response", "b".repeat(threshold * 3))
                        },
                    )
                },
            )
            put("stdout", buildJsonArray { add(JsonPrimitive("line ".repeat(threshold))) })
        }
        val projected = MessageListWireProjection.projectMessage(message, "conv-1")

        val entry = projected.getValue("tool_returns").jsonArray.single().jsonObject
        assertTrue(entry.getValue("func_response").jsonPrimitive.content.contains("[truncated"))
        val stdout = projected.getValue("stdout").jsonArray
        assertEquals(1, stdout.size)
        assertTrue(stdout.single().jsonPrimitive.content.contains("[truncated"))
        assertTrue(projected.getValue("tool_return_truncated").jsonPrimitive.boolean)
        assertEquals(
            (threshold * 3 + "line ".length * threshold).toLong(),
            projected.getValue("tool_return_byte_len").jsonPrimitive.long,
        )
    }

    @Test
    fun mixedPageOnlyProjectsHeavyToolReturns() {
        val smallBody = "small output"
        val bigBody = "z".repeat(threshold * 4)
        val page = buildJsonArray {
            add(
                buildJsonObject {
                    put("id", "msg-user")
                    put("message_type", "user_message")
                    put("content", "hello")
                },
            )
            add(toolReturnMessage(smallBody, id = "msg-small"))
            add(toolReturnMessage(bigBody, id = "msg-big"))
            add(
                buildJsonObject {
                    put("id", "msg-assistant")
                    put("message_type", "assistant_message")
                    put("content", "done")
                },
            )
        }

        val projected = MessageListWireProjection.projectMessageList(page, "conv-9") as JsonArray

        assertEquals(4, projected.size)
        assertEquals("hello", projected[0].jsonObject.getValue("content").jsonPrimitive.content)
        val small = projected[1].jsonObject
        assertEquals(smallBody, small.getValue("tool_return").jsonPrimitive.content)
        assertNull(small["tool_return_truncated"])
        val big = projected[2].jsonObject
        assertTrue(big.getValue("tool_return_truncated").jsonPrimitive.boolean)
        assertEquals("msg-big", big.getValue("tool_return_pointer").jsonObject.getValue("message_id").jsonPrimitive.content)
        assertEquals("done", projected[3].jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun wrappedMessagesObjectShapeIsProjected() {
        val response = buildJsonObject {
            put("messages", buildJsonArray { add(toolReturnMessage("q".repeat(threshold * 2))) })
            put("next_cursor", "cursor-1")
        }
        val projected = MessageListWireProjection.projectMessageList(response, "conv-1") as JsonObject

        assertEquals("cursor-1", projected.getValue("next_cursor").jsonPrimitive.content)
        val message = projected.getValue("messages").jsonArray.single().jsonObject
        assertTrue(message.getValue("tool_return_truncated").jsonPrimitive.boolean)
    }

    @Test
    fun inlineBase64AttachmentDataShipsUnmodifiedInListResponses() {
        // Regression: the projection must NOT strip inline attachment data.
        // Clients have no refetch path for omitted attachments — parsing
        // drops blank-data image parts — so stripping silently loses images
        // on every hydrate. Oversized pages ride frame_part chunking instead.
        val imageMessage = buildJsonObject {
            put("id", "msg-img")
            put("message_type", "user_message")
            put(
                "content",
                buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", "look at this") })
                    add(
                        buildJsonObject {
                            put("type", "image")
                            put(
                                "source",
                                buildJsonObject {
                                    put("type", "base64")
                                    put("media_type", "image/png")
                                    put("data", "A".repeat(64))
                                },
                            )
                        },
                    )
                },
            )
        }
        val dataUrlMessage = buildJsonObject {
            put("id", "msg-img2")
            put("message_type", "assistant_message")
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", "data:image/png;base64," + "B".repeat(50)) })
                        },
                    )
                },
            )
        }
        val page = buildJsonArray {
            add(imageMessage)
            add(dataUrlMessage)
        }

        val projected = MessageListWireProjection.projectMessageList(page, "conv-1") as JsonArray

        assertSame(imageMessage, projected[0])
        assertSame(dataUrlMessage, projected[1])
        val source = projected[0].jsonObject.getValue("content").jsonArray[1].jsonObject.getValue("source").jsonObject
        assertEquals("A".repeat(64), source.getValue("data").jsonPrimitive.content)
        assertNull(source["data_omitted"])
    }

    @Test
    fun toolReturnBodyContainingImagePartsUnderThresholdIsNotMutated() {
        // Regression: attachment stripping used to recurse into structured
        // tool_return bodies and blank image data without setting the
        // tool_return_truncated marker — mutated output with no signal.
        val message = buildJsonObject {
            put("id", "msg-genimg")
            put("message_type", "tool_return_message")
            put(
                "tool_return",
                buildJsonObject {
                    put("type", "image")
                    put(
                        "source",
                        buildJsonObject {
                            put("type", "base64")
                            put("media_type", "image/png")
                            put("data", "C".repeat(128))
                        },
                    )
                },
            )
        }
        val projected = MessageListWireProjection.projectMessage(message, "conv-1")

        assertSame(message, projected)
        assertNull(projected["tool_return_truncated"])
    }

    @Test
    fun untouchedMessagesKeepTheirIdentity() {
        val message = buildJsonObject {
            put("id", "msg-plain")
            put("message_type", "assistant_message")
            put("content", "no images here")
        }
        val projected = MessageListWireProjection.projectMessage(message, "conv-1")
        assertSame(message, projected)
        assertFalse("tool_return_truncated" in projected)
    }
}
