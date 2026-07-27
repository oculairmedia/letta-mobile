package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared test fixtures and specifications for real-time tool timeline lifecycles.
 *
 * Freezes the 11 key tool lifecycle contracts across stream reduction,
 * history hydration, UI message projection, and render item grouping:
 * 1. One call receiving argument deltas
 * 2. Running -> success
 * 3. Running -> explicit error
 * 4. Approval pending -> approved -> running -> success
 * 5. Rejection (approval pending -> rejected)
 * 6. Two calls arriving incrementally
 * 7. Return-before-call
 * 8. Live vs hydrated history equivalence
 * 9. Reconnect / reconcile
 * 10. Truncated result (pointer diet)
 * 11. Blank or duplicate tool_call_id
 */
object ToolTimelineFixtures {

    const val TEST_CONVERSATION_ID = "conv-tool-fixtures-1"
    const val TEST_RUN_ID = "run-tool-fixtures-1"
    const val TEST_AGENT_ID = "agent-tool-fixtures-1"

    object ArgumentDeltas {
        val initialCall = ToolCallMessage(
            id = "msg-tool-delta",
            runId = TEST_RUN_ID,
            toolCall = ToolCall(id = "call-delta-1", name = "Bash", arguments = """{"command":"ls"""),
            seqId = 1,
            otid = "otid-delta-1",
        )
        val updatedCall = ToolCallMessage(
            id = "msg-tool-delta",
            runId = TEST_RUN_ID,
            toolCall = ToolCall(id = "call-delta-1", name = "Bash", arguments = """{"command":"ls -la"}"""),
            seqId = 2,
            otid = "otid-delta-1",
        )
    }

    object RunningToSuccess {
        val callFrame = ToolCallMessage(
            id = "msg-tool-succ",
            runId = TEST_RUN_ID,
            toolCall = ToolCall(id = "call-succ-1", name = "read_file", arguments = """{"path":"a.txt"}"""),
            seqId = 1,
            otid = "otid-succ-1",
        )
        val returnFrame = ToolReturnMessage(
            id = "ret-succ-1",
            toolCallId = "call-succ-1",
            status = "success",
            toolReturnRaw = JsonPrimitive("file content ok"),
            runId = TEST_RUN_ID,
            seqId = 2,
        )
    }

    object RunningToExplicitError {
        val callFrame = ToolCallMessage(
            id = "msg-tool-err",
            runId = TEST_RUN_ID,
            toolCall = ToolCall(id = "call-err-1", name = "exec_cmd", arguments = """{"cmd":"invalid"}"""),
            seqId = 1,
            otid = "otid-err-1",
        )
        val returnFrame = ToolReturnMessage(
            id = "ret-err-1",
            toolCallId = "call-err-1",
            status = "error",
            isErr = true,
            toolReturnRaw = JsonPrimitive("permission denied"),
            runId = TEST_RUN_ID,
            seqId = 2,
        )
    }

    object ApprovalPendingApprovedRunningSuccess {
        val requestFrame = ApprovalRequestMessage(
            id = "appr-req-1",
            runId = TEST_RUN_ID,
            toolCall = ToolCall(id = "call-appr-1", name = "delete_file", arguments = """{"path":"old.txt"}"""),
            seqId = 1,
            otid = "otid-appr-1",
        )
        val responseFrame = ApprovalResponseMessage(
            id = "appr-resp-1",
            approvalRequestId = "appr-req-1",
            approve = true,
            runId = TEST_RUN_ID,
            seqId = 2,
        )
        val returnFrame = ToolReturnMessage(
            id = "ret-appr-1",
            toolCallId = "call-appr-1",
            status = "success",
            toolReturnRaw = JsonPrimitive("file deleted successfully"),
            runId = TEST_RUN_ID,
            seqId = 3,
        )
    }

    object Rejection {
        val requestFrame = ApprovalRequestMessage(
            id = "appr-req-rej",
            runId = TEST_RUN_ID,
            toolCall = ToolCall(id = "call-rej-1", name = "wipe_database", arguments = "{}"),
            seqId = 1,
            otid = "otid-rej-1",
        )
        val responseFrame = ApprovalResponseMessage(
            id = "appr-resp-rej",
            approvalRequestId = "appr-req-rej",
            approve = false,
            reason = "User denied action",
            runId = TEST_RUN_ID,
            seqId = 2,
        )
    }

    object TwoCallsArrivingIncrementally {
        val call1Frame = ToolCallMessage(
            id = "msg-batch-inc",
            runId = TEST_RUN_ID,
            toolCalls = listOf(
                ToolCall(id = "call-inc-1", name = "step1", arguments = """{"arg":1}"""),
            ),
            seqId = 1,
            otid = "otid-inc-batch",
        )
        val call1And2Frame = ToolCallMessage(
            id = "msg-batch-inc",
            runId = TEST_RUN_ID,
            toolCalls = listOf(
                ToolCall(id = "call-inc-1", name = "step1", arguments = """{"arg":1}"""),
                ToolCall(id = "call-inc-2", name = "step2", arguments = """{"arg":2}"""),
            ),
            seqId = 2,
            otid = "otid-inc-batch",
        )
        val return1Frame = ToolReturnMessage(
            id = "ret-inc-1",
            toolCallId = "call-inc-1",
            status = "success",
            toolReturnRaw = JsonPrimitive("result 1"),
            runId = TEST_RUN_ID,
            seqId = 3,
        )
        val return2Frame = ToolReturnMessage(
            id = "ret-inc-2",
            toolCallId = "call-inc-2",
            status = "success",
            toolReturnRaw = JsonPrimitive("result 2"),
            runId = TEST_RUN_ID,
            seqId = 4,
        )
    }

    object ReturnBeforeCall {
        val returnFrameEarly = ToolReturnMessage(
            id = "ret-early-1",
            toolCallId = "call-early-1",
            status = "success",
            toolReturnRaw = JsonPrimitive("early output arrived first"),
            runId = TEST_RUN_ID,
            seqId = 1,
        )
        val callFrameLate = ToolCallMessage(
            id = "msg-call-late",
            runId = TEST_RUN_ID,
            toolCall = ToolCall(id = "call-early-1", name = "fetch_data", arguments = "{}"),
            seqId = 2,
            otid = "otid-early-1",
        )
    }

    object LiveVsHydrated {
        val messagesSequence: List<LettaMessage> = listOf(
            UserMessage(id = "user-prompt", contentRaw = JsonPrimitive("Run tool"), runId = TEST_RUN_ID, otid = "otid-user-1"),
            ToolCallMessage(
                id = "msg-tool-live",
                runId = TEST_RUN_ID,
                toolCall = ToolCall(id = "call-live-1", name = "search", arguments = """{"q":"test"}"""),
                seqId = 1,
                otid = "otid-tool-live-1",
            ),
            ToolReturnMessage(
                id = "ret-tool-live",
                toolCallId = "call-live-1",
                status = "success",
                toolReturnRaw = JsonPrimitive("found 2 items"),
                runId = TEST_RUN_ID,
                seqId = 2,
            ),
            AssistantMessage(
                id = "msg-assistant-final",
                contentRaw = JsonPrimitive("Search completed."),
                runId = TEST_RUN_ID,
                seqId = 3,
                otid = "otid-assistant-final-1",
            ),
        )
    }

    object ReconnectReconcile {
        // The synthetic live body must be a genuine PREFIX of the reconciled body:
        // a real reconnect replays the same assistant text the stream had already
        // accumulated, so mergeStreamText classifies it CUMULATIVE and replaces.
        // Unrelated text would instead fall through to APPEND (correct for a
        // forward token delta) and concatenate the two bodies, which no real
        // reconcile produces.
        val syntheticLiveFrame = AssistantMessage(
            id = "msg-reconcile-1",
            contentRaw = JsonPrimitive("Full complete"),
            runId = "iroh-run-synthetic-101",
            seqId = 1,
            otid = "otid-reconcile-1",
        )
        val realReconciledFrame = AssistantMessage(
            id = "msg-reconcile-1",
            contentRaw = JsonPrimitive("Full complete answer."),
            runId = "run-real-server-101",
            seqId = 2,
            otid = "otid-reconcile-1",
        )
    }

    object TruncatedResult {
        val callFrame = ToolCallMessage(
            id = "msg-tool-trunc",
            runId = TEST_RUN_ID,
            toolCall = ToolCall(id = "call-trunc-1", name = "get_large_log", arguments = "{}"),
            seqId = 1,
            otid = "otid-trunc-1",
        )
        val returnFrame = ToolReturnMessage(
            id = "ret-trunc-1",
            toolCallId = "call-trunc-1",
            status = "success",
            toolReturnRaw = JsonPrimitive("Preview of log data..."),
            toolReturnTruncated = true,
            toolReturnByteLen = 250000L,
            runId = TEST_RUN_ID,
            seqId = 2,
        )
    }

    object BlankAndDuplicateToolCallId {
        val blankCallFrame = ToolCallMessage(
            id = "msg-tool-blank",
            runId = TEST_RUN_ID,
            toolCall = ToolCall(id = "", toolCallId = "", name = "synthetic_blank", arguments = "{}"),
            seqId = 1,
            otid = "otid-blank-1",
        )
        val blankReturnFrame = ToolReturnMessage(
            id = "ret-blank-1",
            toolCallId = "",
            status = "error",
            toolReturnRaw = JsonPrimitive("should not attach to blank call id"),
            runId = TEST_RUN_ID,
            seqId = 2,
        )
        val duplicateCallFrame = ToolCallMessage(
            id = "msg-tool-dup",
            runId = TEST_RUN_ID,
            toolCalls = listOf(
                ToolCall(id = "call-dup-shared", name = "tool_alpha", arguments = """{"x":1}"""),
                ToolCall(id = "call-dup-shared", name = "tool_beta", arguments = """{"x":2}"""),
            ),
            seqId = 1,
            otid = "otid-dup-1",
        )
        val duplicateReturnFrame = ToolReturnMessage(
            id = "ret-dup-1",
            toolCallId = "call-dup-shared",
            status = "success",
            toolReturnRaw = JsonPrimitive("shared result"),
            runId = TEST_RUN_ID,
            seqId = 2,
        )
    }
}
