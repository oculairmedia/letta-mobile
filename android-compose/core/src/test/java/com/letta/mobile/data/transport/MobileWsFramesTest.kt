package com.letta.mobile.data.transport

import com.letta.mobile.data.model.toJsonArray
import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.a2ui.LETTA_TOOL_APPROVAL_WIDGET_ID
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag

/**
 * letta-mobile-9vgk: lock the wire shapes of [ClientFrame] /
 * [ServerFrame] against the contracts in
 * `admin-shim/docs/MOBILE_WS_PROTOCOL.md`. Each test name cites the
 * spec section being defended.
 */
@Tag("unit")
class MobileWsFramesTest : WordSpec({

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    "ClientFrame serialization" should {
        "spec §2.1 hello — embeds token, device_id, client_version" {
            val frame = HelloFrame(
                id = "fid-1",
                ts = "2026-05-15T12:00:00Z",
                token = "secret",
                deviceId = "android-1",
                clientVersion = "letta-mobile/0.6.1",
            )
            val out = frame.encodeJson(json)
            out shouldContain "\"type\":\"hello\""
            out shouldContain "\"token\":\"secret\""
            out shouldContain "\"device_id\":\"android-1\""
            out shouldContain "\"client_version\":\"letta-mobile/0.6.1\""
            out shouldContain "\"a2ui_capability\""
            out shouldContain "\"a2ui_version\":\"v0.9\""
            out shouldContain "\"supported_catalogs\""
            out shouldContain LETTA_TOOL_APPROVAL_WIDGET_ID
        }

        "spec §2.1 send_message — round-trips otid via snake_case" {
            val frame = SendMessageFrame(
                id = "fid-2",
                ts = "2026-05-15T12:00:00Z",
                agentId = "agent-x",
                conversationId = "conv-default-agent-x",
                text = "hello",
                otid = "cm-android-abc",
            )
            val out = frame.encodeJson(json)
            out shouldContain "\"agent_id\":\"agent-x\""
            out shouldContain "\"conversation_id\":\"conv-default-agent-x\""
            out shouldContain "\"otid\":\"cm-android-abc\""
        }

        "lcp-dlj send_message — content_parts omitted when null" {
            val frame = SendMessageFrame(
                id = "fid-2a",
                ts = "2026-05-15T12:00:00Z",
                agentId = "agent-x",
                conversationId = "conv-default-agent-x",
                text = "hello",
                otid = "cm-android-abc",
                contentParts = null,
            )
            val out = frame.encodeJson(json)
            (out.contains("content_parts")) shouldBe false
        }

        "lcp-dlj send_message — content_parts serializes text-first then image with raw base64" {
            val parts = com.letta.mobile.data.model.buildContentParts(
                text = "look",
                images = listOf(
                    com.letta.mobile.data.model.MessageContentPart.Image(
                        base64 = "AAA=",
                        mediaType = "image/jpeg",
                    )
                ),
            ).toJsonArray()
            val frame = SendMessageFrame(
                id = "fid-2b",
                ts = "2026-05-15T12:00:00Z",
                agentId = "agent-x",
                conversationId = "conv-default-agent-x",
                text = "look",
                otid = "cm-android-def",
                contentParts = parts,
            )
            val out = frame.encodeJson(json)
            out shouldContain "\"content_parts\":["
            // Insertion order: text first, image second.
            val textIdx = out.indexOf("\"type\":\"text\"")
            val imageIdx = out.indexOf("\"type\":\"image\"")
            (textIdx in 0..<imageIdx) shouldBe true
            // Letta-shape source: base64 + media_type with raw base64 (no `data:` prefix).
            out shouldContain "\"media_type\":\"image/jpeg\""
            out shouldContain "\"data\":\"AAA=\""
            (out.contains("data:image")) shouldBe false
        }

        "spec §2.1 cancel — run_id is mandatory and snake_cased" {
            val frame = CancelFrame(
                id = "fid-3",
                ts = "2026-05-15T12:00:00Z",
                runId = "run-7",
            )
            val out = frame.encodeJson(json)
            out shouldContain "\"type\":\"cancel\""
            out shouldContain "\"run_id\":\"run-7\""
        }
    }

    "ServerFrame deserialization" should {
        "spec §2.2 welcome — exposes server_id / session_id / device_id" {
            val payload = """
                {"v":1,"type":"welcome","id":"f1","ts":"t",
                 "server_id":"S","session_id":"sess-1","device_id":"d-1"}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.Welcome>()
            parsed.serverId shouldBe "S"
            parsed.sessionId shouldBe "sess-1"
            parsed.deviceId shouldBe "d-1"
        }

        "letta-mobile-51xm.2 welcome — parses A2UI negotiation ack" {
            val payload = """
                {"v":1,"type":"welcome","id":"f1","ts":"t",
                 "server_id":"S","session_id":"sess-1",
                 "a2ui_negotiation":{"a2ui_enabled":true,"negotiated_catalog":"com.letta.mobile:tool-approval/v1","negotiated_widgets":["ToolApprovalCard"]}}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.Welcome>()
            parsed.a2uiNegotiation?.a2uiEnabled shouldBe true
            parsed.a2uiNegotiation?.negotiatedCatalog shouldBe "com.letta.mobile:tool-approval/v1"
            parsed.a2uiNegotiation?.negotiatedWidgets shouldBe listOf("ToolApprovalCard")
        }

        "spec §4.7 stop_reason — inner field is `stop_reason`, NOT `reason`" {
            val payload = """
                {"v":1,"type":"stop_reason","id":"f","ts":"t",
                 "turn_id":"T","run_id":"R","stop_reason":"end_turn"}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.StopReason>()
            parsed.stopReason shouldBe "end_turn"
        }

        "spec §4.4 usage_statistics — first-frame counters default to 0" {
            val payload = """
                {"v":1,"type":"usage_statistics","id":"f","ts":"t",
                 "turn_id":"T","run_id":"R","prompt_tokens":12}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.UsageStatistics>()
            parsed.promptTokens shouldBe 12L
            parsed.completionTokens shouldBe 0L
            parsed.totalTokens shouldBe 0L
        }

        "spec §4.1 approval_request_message folds into ToolCallMessage" {
            val payload = """
                {"v":1,"type":"approval_request_message","id":"toolcall-x","ts":"t",
                 "agent_id":"a","conversation_id":"c","turn_id":"T","run_id":"R",
                 "tool_call":{"tool_call_id":"tc-1","name":"Bash","arguments":"{}"}}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.ToolCallMessage>()
            parsed.toolCall?.toolCallId shouldBe "tc-1"
            parsed.toolCall?.name shouldBe "Bash"
        }

        "letta-mobile-51xm.2 a2ui — routes typed A2UI messages separately" {
            val payload = """
                {"v":1,"type":"a2ui","id":"a2ui-1","ts":"t",
                 "agent_id":"a","conversation_id":"c","turn_id":"T","run_id":"R",
                 "message":{"version":"v0.9","createSurface":{"surfaceId":"approval-1","catalogId":"com.letta.mobile:tool-approval/v1"}}}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.A2ui>()
            parsed.runId shouldBe "R"
            parsed.messages.single().shouldBeInstanceOf<A2uiMessage.CreateSurface>()
            parsed.messages.single().surfaceId shouldBe "approval-1"
        }

        "spec §2 forward-compat — unknown type lands as ServerFrame.Unknown" {
            val payload = """
                {"v":1,"type":"some_future_type","id":"f","ts":"t","extra":"value"}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.Unknown>()
            parsed.type shouldBe "some_future_type"
        }
    }
})
