package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-fe51r (P2b pointer diet): client-side decode tolerance for
 * server-projected tool-return messages, and the fold rule that keeps
 * projected previews from clobbering full bodies already in the timeline.
 */
class ProjectedToolReturnTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val projectedJson = """
        {
          "id": "msg-1",
          "message_type": "tool_return_message",
          "tool_call_id": "call-1",
          "status": "success",
          "tool_return": "preview text\n… [truncated: 30000 bytes total — expand to load the full output]",
          "tool_return_truncated": true,
          "tool_return_byte_len": 30000,
          "tool_return_pointer": {
            "method": "tool_return.get",
            "conversation_id": "conv-1",
            "message_id": "msg-1"
          }
        }
    """.trimIndent()

    @Test
    fun projectedToolReturnMessageDecodes() {
        val message = json.decodeFromString(LettaMessage.serializer(), projectedJson)

        val toolReturn = assertIs<ToolReturnMessage>(message)
        assertEquals(true, toolReturn.toolReturnTruncated)
        assertEquals(30_000L, toolReturn.toolReturnByteLen)
        assertTrue(toolReturn.toolReturn.funcResponse.orEmpty().startsWith("preview text"))
        assertEquals("call-1", toolReturn.toolCallId)
    }

    @Test
    fun fullToolReturnMessageDecodesWithoutTruncationMarkers() {
        val message = json.decodeFromString(
            LettaMessage.serializer(),
            """{"id":"msg-2","message_type":"tool_return_message","tool_call_id":"call-2","status":"success","tool_return":"full body"}""",
        )

        val toolReturn = assertIs<ToolReturnMessage>(message)
        assertNull(toolReturn.toolReturnTruncated)
        assertNull(toolReturn.toolReturnByteLen)
        assertNull(toolReturn.toolReturnPointer)
        assertEquals("full body", toolReturn.toolReturn.funcResponse)
    }

    @Test
    fun mixedProjectedPageDecodes() {
        val page = """
            [
              {"id":"msg-user","message_type":"user_message","content":"hi"},
              $projectedJson,
              {"id":"msg-3","message_type":"tool_return_message","tool_call_id":"call-3","status":"success","tool_return":"small"}
            ]
        """.trimIndent()

        val messages = json.decodeFromString(ListSerializer(LettaMessage.serializer()), page)

        assertEquals(3, messages.size)
        assertIs<UserMessage>(messages[0])
        assertEquals(true, assertIs<ToolReturnMessage>(messages[1]).toolReturnTruncated)
        val small = assertIs<ToolReturnMessage>(messages[2])
        assertNull(small.toolReturnTruncated)
        assertEquals("small", small.toolReturn.funcResponse)
    }

    @Test
    fun projectedPreviewNeverClobbersFullBodyAlreadyHeld() {
        val preview = json.decodeFromString(LettaMessage.serializer(), projectedJson) as ToolReturnMessage
        val fold = foldToolReturnBodies(
            existingContent = mapOf("call-1" to "the full body from the live stream"),
            existingTruncation = emptyMap(),
            matchingReturns = listOf("call-1" to preview),
        )

        assertEquals("the full body from the live stream", fold.contentByCallId.getValue("call-1"))
        assertTrue(fold.truncationByCallId.isEmpty())
    }

    @Test
    fun oversizedFullToolReturnBodyIsBoundedAtIngestWithMarker() {
        // c4igq.2: a server that does NOT pre-project can deliver an arbitrarily
        // large untruncated body. Folding it in full has wedged a turn mid-stream.
        val cap = 1024
        val hugeBody = "x".repeat(cap * 4) // 4 KiB, well over the 1 KiB test cap
        val full = json.decodeFromString(
            LettaMessage.serializer(),
            """{"id":"msg-huge","message_type":"tool_return_message","tool_call_id":"call-h","status":"success","tool_return":"$hugeBody"}""",
        ) as ToolReturnMessage

        val fold = foldToolReturnBodies(
            existingContent = emptyMap(),
            existingTruncation = emptyMap(),
            matchingReturns = listOf("call-h" to full),
            maxInboundBodyBytes = cap,
        )

        val stored = fold.contentByCallId.getValue("call-h")
        assertTrue(stored.length < hugeBody.length, "oversized body must be truncated at ingest, not stored whole")
        assertTrue(stored.contains("truncated at ingest"), "truncation marker must be present")
        val marker = fold.truncationByCallId["call-h"]
        assertTrue(marker != null, "a ToolReturnTruncation marker must be attached so the full body stays fetchable")
        assertEquals((cap * 4).toLong(), marker.byteLen, "marker must record the original byte length")
    }

    @Test
    fun withinCapFullToolReturnBodyIsStoredWholeWithNoMarker() {
        val full = json.decodeFromString(
            LettaMessage.serializer(),
            """{"id":"msg-ok","message_type":"tool_return_message","tool_call_id":"call-ok","status":"success","tool_return":"small body"}""",
        ) as ToolReturnMessage

        val fold = foldToolReturnBodies(
            existingContent = emptyMap(),
            existingTruncation = emptyMap(),
            matchingReturns = listOf("call-ok" to full),
            maxInboundBodyBytes = 1024,
        )

        assertEquals("small body", fold.contentByCallId.getValue("call-ok"))
        assertTrue(fold.truncationByCallId.isEmpty(), "a within-cap body must not be marked truncated")
    }

    @Test
    fun projectedPreviewAppliesWhenNothingHeldAndFullBodyClearsMarker() {
        val preview = json.decodeFromString(LettaMessage.serializer(), projectedJson) as ToolReturnMessage
        val afterPreview = foldToolReturnBodies(
            existingContent = emptyMap(),
            existingTruncation = emptyMap(),
            matchingReturns = listOf("call-1" to preview),
        )
        assertTrue(afterPreview.contentByCallId.getValue("call-1").startsWith("preview text"))
        assertEquals(
            ToolReturnTruncation(messageId = "msg-1", byteLen = 30_000L),
            afterPreview.truncationByCallId.getValue("call-1"),
        )

        val full = json.decodeFromString(
            LettaMessage.serializer(),
            """{"id":"msg-1","message_type":"tool_return_message","tool_call_id":"call-1","status":"success","tool_return":"the full body"}""",
        ) as ToolReturnMessage
        val afterFull = foldToolReturnBodies(
            existingContent = afterPreview.contentByCallId,
            existingTruncation = afterPreview.truncationByCallId,
            matchingReturns = listOf("call-1" to full),
        )
        assertEquals("the full body", afterFull.contentByCallId.getValue("call-1"))
        assertTrue(afterFull.truncationByCallId.isEmpty())
    }

    @Test
    fun projectedPreviewRefreshesAnEarlierPreview() {
        val preview = json.decodeFromString(LettaMessage.serializer(), projectedJson) as ToolReturnMessage
        val fold = foldToolReturnBodies(
            existingContent = mapOf("call-1" to "older preview"),
            existingTruncation = mapOf("call-1" to ToolReturnTruncation("msg-1", 10_000L)),
            matchingReturns = listOf("call-1" to preview),
        )

        assertTrue(fold.contentByCallId.getValue("call-1").startsWith("preview text"))
        assertEquals(30_000L, fold.truncationByCallId.getValue("call-1").byteLen)
    }
}
