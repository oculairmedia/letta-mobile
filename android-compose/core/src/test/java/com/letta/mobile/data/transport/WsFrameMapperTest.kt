package com.letta.mobile.data.transport

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Tag

/**
 * letta-mobile-wecy: lock the wire-frame → LettaMessage mapping
 * against the dedupe-pipeline contract documented in
 * `admin-shim/docs/MOBILE_WS_PROTOCOL.md` §4 + §6.
 */
@Tag("unit")
class WsFrameMapperTest : WordSpec({

    "WsFrameMapper" should {
        "preserve the cm-stream- prefix on assistant_message ids (spec §4.2)" {
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
        }

        "preserve toolcall- prefix on tool_call ids (spec §4.2)" {
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
        }

        "preserve toolreturn- prefix and route stdout/stderr (spec §2.2)" {
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
        }

        "map reasoning_message and propagate signature when set" {
            val frame = ServerFrame.ReasoningMessage(
                id = "letta-reason-1",
                ts = "t",
                agentId = "a", conversationId = "c",
                turnId = "T", runId = "R",
                reasoning = "thinking…",
                signature = "sig-7",
            )
            val mapped = WsFrameMapper.toLettaMessage(frame)
            mapped.shouldBeInstanceOf<ReasoningMessage>()
            mapped.reasoning shouldBe "thinking…"
            mapped.signature shouldBe "sig-7"
        }

        "return null for non-message frames so the bridge can route them elsewhere" {
            WsFrameMapper.toLettaMessage(
                ServerFrame.Welcome(id = "f", ts = "t", serverId = "S", sessionId = "sess")
            ).shouldBeNull()
            WsFrameMapper.toLettaMessage(
                ServerFrame.TurnStarted(id = "f", ts = "t", agentId = "a", conversationId = "c", turnId = "T")
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
