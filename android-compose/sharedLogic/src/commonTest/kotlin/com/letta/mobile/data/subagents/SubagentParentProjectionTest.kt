package com.letta.mobile.data.subagents

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubagentParentProjectionTest {
    @Test
    fun hiddenReasoningPromptAndToolTrafficNeverBecomeParentActivity() {
        assertNull(SubagentParentProjection.activityLine(buildJsonObject {
            put("message_type", "reasoning_message")
            put("reasoning", "private chain")
        }))
        assertNull(SubagentParentProjection.activityLine(buildJsonObject {
            put("message_type", "tool_call_message")
            put("content", "raw envelope")
        }))
        assertNull(SubagentParentProjection.activityLine(buildJsonObject {
            put("message_type", "assistant_message")
            put("content", "Prompt: secret")
        }))
    }

    @Test
    fun publicActivityIsSingleLineAndUtf8Bounded() {
        val line = SubagentParentProjection.activityLine(buildJsonObject {
            put("message_type", "progress_message")
            put("status_text", "€".repeat(500) + "\nnever project history")
        })
        assertNotNull(line)
        assertTrue(line.encodeToByteArray().size <= 240)
        assertFalse(line.contains("never project history"))
    }

    @Test
    fun activityByteLimitNeverSplitsEmojiSurrogatePair() {
        val line = SubagentParentProjection.activityLine(buildJsonObject {
            put("message_type", "progress_message")
            put("status_text", "a".repeat(239) + "😀")
        })

        assertEquals("a".repeat(239), line)
        assertFalse(line.orEmpty().contains('\uFFFD'))
    }

    @Test
    fun agentReturnDropsTranscriptBodyAndKeepsSummaryPointer() {
        val sentinel = "CHILD_TRANSCRIPT_MUST_NOT_REACH_PARENT"
        val body = """
            <task-notification>
              <status>completed</status>
              <summary>Reviewed the transport</summary>
              <result>$sentinel ${"x".repeat(20_000)}</result>
              <transcript>/tmp/task-1.log</transcript>
            </task-notification>
        """.trimIndent()
        val projected = SubagentParentProjection.sanitizedAgentReturn(
            buildJsonObject {
                put("message_type", "tool_return_message")
                put("tool_call_id", "call-1")
                put("status", "success")
                put("tool_return", body)
                put("stdout", sentinel)
            },
            conversationId = "conv-1",
            messageId = "msg-1",
        )

        assertEquals("Reviewed the transport", projected.getValue("tool_return").jsonPrimitive.content)
        assertFalse(projected.toString().contains(sentinel))
        assertNull(projected["stdout"])
        val pointer = projected.getValue("subagent_transcript_pointer").jsonObject
        assertEquals("/tmp/task-1.log", pointer.getValue("uri").jsonPrimitive.content)
        assertEquals("msg-1", pointer.getValue("message_id").jsonPrimitive.content)
    }

    @Test
    fun failedAgentReturnNeverCopiesTranscriptIntoErrorTail() {
        val sentinel = "PRIVATE_CHILD_FAILURE_TRAJECTORY"
        val projected = SubagentParentProjection.sanitizedAgentReturn(
            buildJsonObject {
                put("message_type", "tool_return_message")
                put("tool_call_id", "call-failed")
                put("status", "error")
                put("tool_return", sentinel.repeat(100))
            },
            conversationId = "conv-1",
        )

        assertEquals("Sub-agent dispatch failed", projected.getValue("subagent_error_tail").jsonPrimitive.content)
        assertFalse(projected.toString().contains(sentinel))
    }
}
