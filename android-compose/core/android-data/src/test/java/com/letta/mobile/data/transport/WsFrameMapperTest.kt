package com.letta.mobile.data.transport

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Tag

/**
 * letta-mobile-wecy: lock the wire-frame to LettaMessage mapping against the
 * dedupe-pipeline contract documented in admin-shim/docs/MOBILE_WS_PROTOCOL.md.
 */
@Tag("unit")
class WsFrameMapperTest : WordSpec({

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    "WsFrameMapper" should {
        "map replayed user_message frames into user timeline messages" {
            val frame = json.decodeFromString(
                ServerFrameSerializer,
                """
                {"v":1,"type":"user_message","id":"user-msg-1","ts":"t","agent_id":"a","conversation_id":"c","run_id":"R","content":"hello local runtime","otid":"cm-local-1","seq":7}
                """.trimIndent(),
            ).shouldBeInstanceOf<ServerFrame.UserMessage>()

            val mapped = WsFrameMapper.toLettaMessage(frame)

            mapped.shouldBeInstanceOf<UserMessage>()
            mapped.id shouldBe "user-msg-1"
            mapped.content shouldBe "hello local runtime"
            mapped.messageType shouldBe "user_message"
            mapped.runId shouldBe "R"
            mapped.otid shouldBe "cm-local-1"
            mapped.seqId shouldBe 7
        }

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

        "letta-mobile-ipp8z maps assistant frames that omitted SDK-Transport metadata" {
            val frame = json.decodeFromString(
                ServerFrameSerializer,
                """
                {"v":1,"type":"assistant_message","id":"cm-stream-1","content":"hello"}
                """.trimIndent(),
            ).shouldBeInstanceOf<ServerFrame.AssistantMessage>()

            val mapped = WsFrameMapper.toLettaMessage(frame)

            mapped.shouldBeInstanceOf<AssistantMessage>()
            mapped.id shouldBe "cm-stream-1"
            mapped.content shouldBe "hello"
            mapped.date shouldBe ""
            mapped.runId shouldBe null
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
                toolReturn = JsonPrimitive("hello\n"),
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

        "preserve raw JSON tool_return image payloads from live frames" {
            val imagePayload = buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("image"))
                    put("source", buildJsonObject {
                        put("type", JsonPrimitive("letta"))
                        put("file_id", JsonPrimitive("file-live"))
                        put("media_type", JsonPrimitive("image/png"))
                        put("data", JsonPrimitive("LIVE_TOOL_IMAGE+/=="))
                    })
                })
            }
            val frame = ServerFrame.ToolReturnMessage(
                id = "toolreturn-tc-live-image",
                ts = "t",
                agentId = "a", conversationId = "c",
                turnId = "T", runId = "R",
                toolCallId = "tc-live-image",
                status = "success",
                toolReturn = imagePayload,
            )

            val mapped = WsFrameMapper.toLettaMessage(frame)

            mapped.shouldBeInstanceOf<ToolReturnMessage>()
            mapped.toolReturn.funcResponse.shouldBeNull()
            mapped.attachments.size shouldBe 1
            mapped.attachments.single().mediaType shouldBe "image/png"
            mapped.attachments.single().base64 shouldBe "LIVE_TOOL_IMAGE+/=="
        }

        "preserve generate_image tool return metadata and inline image from live frames" {
            val imagePayload = buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive("""
                        {
                          "path": "/tmp/generated-image.png",
                          "mime_type": "image/png",
                          "model": "gpt-image-2-medium",
                          "size": "1024x1024",
                          "quality": "medium",
                          "prompt": "a small brass robot"
                        }
                    """.trimIndent()))
                })
                add(buildJsonObject {
                    put("type", JsonPrimitive("image"))
                    put("source", buildJsonObject {
                        put("type", JsonPrimitive("base64"))
                        put("media_type", JsonPrimitive("image/png"))
                        put("data", JsonPrimitive("LIVE_GENERATED_IMAGE+/=="))
                    })
                })
            }
            val frame = ServerFrame.ToolReturnMessage(
                id = "toolreturn-tc-generate-image",
                ts = "t",
                agentId = "a", conversationId = "c",
                turnId = "T", runId = "R",
                toolCallId = "tc-generate-image",
                status = "success",
                toolReturn = imagePayload,
            )

            val mapped = WsFrameMapper.toLettaMessage(frame)

            mapped.shouldBeInstanceOf<ToolReturnMessage>()
            mapped.toolReturn.funcResponse.orEmpty().contains("gpt-image-2-medium") shouldBe true
            mapped.attachments.size shouldBe 1
            mapped.attachments.single().mediaType shouldBe "image/png"
            mapped.attachments.single().base64 shouldBe "LIVE_GENERATED_IMAGE+/=="
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

        "map wire seq into LettaMessage seqId for cursor persistence" {
            val assistant = WsFrameMapper.toLettaMessage(
                ServerFrame.AssistantMessage(
                    id = "cm-stream-letta-msg-3",
                    ts = "t",
                    agentId = "a", conversationId = "c",
                    turnId = "T", runId = "R",
                    content = "pong",
                    seq = 11L,
                )
            ).shouldBeInstanceOf<AssistantMessage>()
            val reasoning = WsFrameMapper.toLettaMessage(
                ServerFrame.ReasoningMessage(
                    id = "letta-reason-1",
                    ts = "t",
                    agentId = "a", conversationId = "c",
                    turnId = "T", runId = "R",
                    reasoning = "thinking",
                    seq = 12L,
                )
            ).shouldBeInstanceOf<ReasoningMessage>()
            val toolCall = WsFrameMapper.toLettaMessage(
                ServerFrame.ToolCallMessage(
                    id = "toolcall-tc-1",
                    ts = "t",
                    agentId = "a", conversationId = "c",
                    turnId = "T", runId = "R",
                    toolCall = ToolCallPayload(toolCallId = "tc-1", name = "Bash", arguments = "{}"),
                    seq = 13L,
                )
            ).shouldBeInstanceOf<ToolCallMessage>()
            val toolReturn = WsFrameMapper.toLettaMessage(
                ServerFrame.ToolReturnMessage(
                    id = "toolreturn-tc-1",
                    ts = "t",
                    agentId = "a", conversationId = "c",
                    turnId = "T", runId = "R",
                    toolCallId = "tc-1",
                    seq = 14L,
                )
            ).shouldBeInstanceOf<ToolReturnMessage>()

            assistant.seqId shouldBe 11
            reasoning.seqId shouldBe 12
            toolCall.seqId shouldBe 13
            toolReturn.seqId shouldBe 14
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
