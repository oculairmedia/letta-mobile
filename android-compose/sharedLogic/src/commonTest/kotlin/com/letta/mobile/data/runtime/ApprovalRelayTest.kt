package com.letta.mobile.data.runtime

import com.letta.mobile.data.chat.projection.requiresUserInput
import com.letta.mobile.data.chat.projection.withPendingApprovalDetails
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ServerFrameSerializer
import kotlinx.serialization.json.Json
import com.letta.mobile.runtime.ApprovalDiffPreview
import com.letta.mobile.runtime.PermissionSuggestion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** letta-mobile-bzvro.40: the host's `pending_approvals` relay, end to end in pure code. */
class ApprovalRelayTest {
    private val edit = PendingApprovalDetails(
        approvalId = "perm-call-1",
        toolCallId = "call-1",
        toolName = "Edit",
        suggestions = listOf(PermissionSuggestion("rule-1", "Allow Edit in /repo")),
        blockedPath = "/repo/a.kt",
        diffs = listOf(ApprovalDiffPreview(path = "/repo/a.kt", unifiedDiff = "@@ -1 +1 @@\n-a\n+b", note = null)),
    )

    @Test
    fun `a frame round-trips every field the card shows`() {
        val frame = ApprovalRelay.encodeFrame(RelayedPendingApprovals("agent-1", "conv-1", listOf(edit)), ts = "t0")

        val decoded = ApprovalRelay.decode(frame)

        assertEquals(RelayedPendingApprovals("agent-1", "conv-1", listOf(edit)), decoded)
    }

    @Test
    fun `the frame decodes as the client's typed push`() {
        val frame = ApprovalRelay.encodeFrame(RelayedPendingApprovals("agent-1", "conv-1", listOf(edit)), ts = "t0").toString()

        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString(ServerFrameSerializer, frame)

        val push = assertIs<ServerFrame.PendingApprovalsUpdated>(decoded)
        assertEquals(listOf(edit), push.decode().approvals)
    }

    @Test
    fun `malformed entries are skipped and hostile text is bounded`() {
        val frame = buildJsonObject {
            put(ApprovalRelay.CONVERSATION_ID, "conv-1")
            put(
                ApprovalRelay.APPROVALS,
                buildJsonArray {
                    add(JsonPrimitive("not an object"))
                    add(buildJsonObject { put("tool_call_id", "no-approval-id") })
                    add(
                        buildJsonObject {
                            put("approval_id", "perm-2")
                            put("tool_call_id", "call-2")
                            put("tool_name", "x".repeat(10_000))
                        },
                    )
                },
            )
        }

        val decoded = ApprovalRelay.decode(frame)

        assertEquals("", decoded?.agentId, "an emptied conversation may be replayed without its agent")
        val only = decoded?.approvals?.single()
        assertEquals("call-2", only?.toolCallId)
        assertEquals(ApprovalRequestPayloadParser.MAX_TOOL_NAME_CHARS, only?.toolName?.length)
    }

    @Test
    fun `a frame without a conversation is ignored`() {
        assertNull(ApprovalRelay.decode(JsonObject(mapOf(ApprovalRelay.APPROVALS to JsonArray(emptyList())))))
    }

    @Test
    fun `snapshots replace per conversation and an empty one clears it`() {
        val store = RelayedPendingApprovalStore()
        val other = edit.copy(approvalId = "perm-9", toolCallId = "call-9")

        store.apply(RelayedPendingApprovals("agent-1", "conv-1", listOf(edit)))
        store.apply(RelayedPendingApprovals("agent-2", "conv-2", listOf(other)))
        assertEquals(setOf("call-1", "call-9"), store.pending.value.keys)

        store.apply(RelayedPendingApprovals("", "conv-1", emptyList()))
        assertEquals(setOf("call-9"), store.pending.value.keys)
        store.resolve(com.letta.mobile.runtime.ToolCallId("call-9"))
        assertEquals(emptyMap(), store.pending.value)
    }

    @Test
    fun `a plain tool row whose call the host parked becomes an approval card`() {
        val toolRow = UiMessage(
            id = "toolcall-call-1",
            role = "assistant",
            content = "",
            timestamp = "t",
            toolCalls = listOf(UiToolCall(name = "Edit", arguments = "{\"file_path\":\"/repo/a.kt\"}", result = null, toolCallId = "call-1")),
        )

        val joined = withPendingApprovalDetails(listOf(toolRow), mapOf("call-1" to edit)).single()

        val request = joined.approvalRequest
        assertEquals("perm-call-1", request?.requestId, "answered against the control request's own id")
        assertEquals(edit, request?.details)
        assertEquals("Edit", request?.toolCalls?.single()?.name)
        assertTrue(request?.requiresUserInput() == true, "a parked request waits on the person whatever the tool")
    }

    @Test
    fun `a tool that already returned or nothing parked leaves the row alone`() {
        val returned = UiMessage(
            id = "toolcall-call-1",
            role = "assistant",
            content = "",
            timestamp = "t",
            toolCalls = listOf(UiToolCall(name = "Edit", arguments = "{}", result = "ok", toolCallId = "call-1")),
        )
        val messages = listOf(returned)

        assertSame(messages, withPendingApprovalDetails(messages, mapOf("call-1" to edit)))
        assertSame(messages, withPendingApprovalDetails(messages, emptyMap()))
    }
}
