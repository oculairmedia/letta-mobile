package com.letta.mobile.data.transport

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Tag

/**
 * letta-mobile-wecy: lock the wire-frame to LettaMessage mapping against the
 * dedupe-pipeline contract documented in admin-shim/docs/MOBILE_WS_PROTOCOL.md.
 */
@Tag("unit")
class WsFrameMapperTest : WordSpec({

    "WsFrameMapper" should {
        "preserve the cm-stream- prefix on assistant_message ids" {
            val frame = ServerFrame.AssistantMessage(
                id = "cm-stream-letta-msg-3",
                ts = "t",
                agentId = "a", conversationId = "c",
                turnId = "T", runId = "R",
                content = "pong",
                otid = "cm-android-abc",
            )
            val mapped = WsFrameMapper.toLettaMessage(frame)
            mapped.shouldBeInstanceOf<AssistantMessage>()
            mapped.id shouldBe "cm-stream-letta-msg-3"
            mapped.content shouldBe "pong"
            mapped.otid shouldBe "cm-android-abc"
            mapped.runId shouldBe "R"
            mapped.date shouldBe "t"
        }

        "preserve toolcall- prefix on tool_call ids" {
            val frame = ServerFrame.ToolCallMessage(
                id = "toolcall-tc-1",
                ts = "t",
                agentId = "a", conversationId = "c",
                turnId = "T", runId = "R",
                toolCall = ToolCallPayload(toolCallId = "tc-1", name = "Bash", arguments = "{}"),
            )
            val mapped = WsFrameMapper.toLettaMessage(frame)
            mapped.shouldBeInstanceOf<ToolCallMessage>()
            mapped.id shouldBe "toolcall-tc-1"
            mapped.toolCall?.effectiveId shouldBe "tc-1"
            mapped.toolCall?.name shouldBe "Bash"
            mapped.date shouldBe "t"
        }

        "map approval_request_message tool frames to approval requests" {
            val frame = ServerFrame.ToolCallMessage(
                type = "approval_request_message",
                id = "toolcall-tc-approval",
                ts = "t",
                agentId = "a", conversationId = "c",
                turnId = "T", runId = "R",
                toolCalls = listOf(ToolCallPayload(toolCallId = "tc-approval", name = "Bash", arguments = "{}")),
            )
            val mapped = WsFrameMapper.toLettaMessage(frame)
            mapped.shouldBeInstanceOf<ApprovalRequestMessage>()
            mapped.id shouldBe "toolcall-tc-approval"
            mapped.effectiveToolCalls.single().effectiveId shouldBe "tc-approval"
            mapped.effectiveToolCalls.single().name shouldBe "Bash"
            mapped.messageType shouldBe "approval_request_message"
            mapped.runId shouldBe "R"
            mapped.date shouldBe "t"
        }

        "preserve toolreturn- prefix and route stdout stderr" {
            val frame = ServerFrame.ToolReturnMessage(
                id = "toolreturn-tc-1",
                ts = "t",
                agentId = "a", conversationId = "c",
                turnId = "T", runId = "R",
                toolCallId = "tc-1",
                status = "success",
                toolReturn = "hello\n",
                stdout = listOf("hello"),
                stderr = null,
            )
            val mapped = WsFrameMapper.toLettaMessage(frame)
            mapped.shouldBeInstanceOf<ToolReturnMessage>()
            mapped.id shouldBe "toolreturn-tc-1"
            mapped.status shouldBe "success"
            mapped.stdout shouldBe listOf("hello")
            mapped.toolReturn.funcResponse shouldBe "hello\n"
            mapped.date shouldBe "t"
        }

        "map reasoning_message and propagate signature when set" {
            val frame = ServerFrame.ReasoningMessage(
                id = "letta-reason-1",
                ts = "t",
                agentId = "a", conversationId = "c",
                turnId = "T", runId = "R",
                reasoning = "thinking...",
                signature = "sig-7",
            )
            val mapped = WsFrameMapper.toLettaMessage(frame)
            mapped.shouldBeInstanceOf<ReasoningMessage>()
            mapped.reasoning shouldBe "thinking..."
            mapped.signature shouldBe "sig-7"
            mapped.date shouldBe "t"
        }

        "return null for non-message frames so the bridge can route them elsewhere" {
            WsFrameMapper.toLettaMessage(
                ServerFrame.Welcome(id = "f", ts = "t", serverId = "S", sessionId = "sess")
            ).shouldBeNull()
            WsFrameMapper.toLettaMessage(
                ServerFrame.TurnStarted(id = "f", ts = "t", agentId = "a", conversationId = "c", turnId = "T", runId = "R")
            ).shouldBeNull()
            WsFrameMapper.toLettaMessage(
                ServerFrame.StopReason(id = "f", ts = "t", turnId = "T", runId = "R", stopReason = "end_turn")
            ).shouldBeNull()
            WsFrameMapper.toLettaMessage(
                ServerFrame.TurnDone(id = "f", ts = "t", turnId = "T", runId = "R", status = "completed")
            ).shouldBeNull()
        }
    }
})
