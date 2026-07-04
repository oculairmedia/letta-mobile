package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AppServerProtocolTest {
    @Test
    fun encodesAuthCommandAndDecodesAuthResponse() {
        val encoded = AppServerProtocol.json.parseToJsonElement(
            AppServerProtocol.encodeCommand(AppServerCommand.Auth(requestId = "auth-1", token = "secret")),
        ).jsonObject
        assertEquals("auth", encoded["type"]?.jsonPrimitive?.content)
        assertEquals("auth-1", encoded["request_id"]?.jsonPrimitive?.content)
        assertEquals("secret", encoded["token"]?.jsonPrimitive?.content)

        val received = AppServerProtocol.decodeFrame(
            rawJson = """{"type":"auth_response","request_id":"auth-1","success":false,"error":"invalid_token"}""",
            channel = AppServerChannel.Control,
        )
        val frame = assertIs<AppServerInboundFrame.AuthResponse>(received.frame)
        assertEquals("auth-1", frame.requestId)
        assertEquals(false, frame.success)
        assertEquals("invalid_token", frame.error)
        assertNull(frame.capabilities)
    }

    @Test
    fun authCapabilitiesRoundTripAndStayAbsentByDefault() {
        val withCaps = AppServerProtocol.json.parseToJsonElement(
            AppServerProtocol.encodeCommand(
                AppServerCommand.Auth(requestId = "auth-2", token = "", capabilities = listOf("frame_part")),
            ),
        ).jsonObject
        assertEquals(
            listOf("frame_part"),
            withCaps["capabilities"]?.jsonArray?.map { it.jsonPrimitive.content },
        )

        val withoutCaps = AppServerProtocol.json.parseToJsonElement(
            AppServerProtocol.encodeCommand(AppServerCommand.Auth(requestId = "auth-3", token = "t")),
        ).jsonObject
        assertNull(withoutCaps["capabilities"], "old servers must not see a capabilities key by default")

        val received = AppServerProtocol.decodeFrame(
            rawJson = """{"type":"auth_response","request_id":"auth-2","success":true,"capabilities":["frame_part"]}""",
            channel = AppServerChannel.Control,
        )
        val frame = assertIs<AppServerInboundFrame.AuthResponse>(received.frame)
        assertEquals(listOf("frame_part"), frame.capabilities)
    }

    @Test
    fun encodesRuntimeStartWithTypedV2CommandShape() {
        val json = AppServerProtocol.encodeCommand(
            AppServerCommand.RuntimeStart(
                requestId = "runtime-start-1",
                agentId = "agent-1",
                createConversation = AppServerRuntimeStartCreateConversationOptions(
                    body = buildJsonObject { put("name", "mobile fixture") },
                ),
                cwd = "/tmp/project",
                mode = AppServerPermissionMode.Unrestricted,
                clientInfo = AppServerRuntimeStartClientInfo(
                    name = "letta-mobile",
                    title = "Letta Mobile",
                    version = "0.1",
                ),
                recoverApprovals = false,
                forceDeviceStatus = true,
                externalTools = listOf(
                    AppServerExternalToolsGroup(
                        scopeId = "mobile-scope",
                        tools = listOf(
                            AppServerExternalToolDefinition(
                                name = "mobile_echo",
                                label = "Mobile echo",
                                description = "Echo text from the mobile controller.",
                                parameters = buildJsonObject {
                                    put("type", "object")
                                },
                            ),
                        ),
                    ),
                ),
            ),
        )

        val root = AppServerProtocol.json.parseToJsonElement(json).jsonObject
        assertEquals("runtime_start", root["type"]?.jsonPrimitive?.content)
        assertEquals("runtime-start-1", root["request_id"]?.jsonPrimitive?.content)
        assertEquals("agent-1", root["agent_id"]?.jsonPrimitive?.content)
        assertEquals("mobile fixture", root["create_conversation"]?.jsonObject?.get("body")?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals("unrestricted", root["mode"]?.jsonPrimitive?.content)
        assertEquals("letta-mobile", root["client_info"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals("mobile-scope", root["external_tools"]?.jsonArray?.first()?.jsonObject?.get("scope_id")?.jsonPrimitive?.content)
    }

    @Test
    fun encodesInputWithRequiredRuntimeAndTypedCreateMessagePayload() {
        val json = AppServerProtocol.encodeCommand(
            AppServerCommand.Input(
                runtime = runtime,
                payload = AppServerInputPayload.CreateMessage(
                    messages = listOf(AppServerInputMessage.userText("hello", clientMessageId = "local-1")),
                    clientToolAllowlist = listOf("read_file"),
                    externalToolScopeIds = listOf("mobile-scope"),
                ),
            ),
        )

        val root = AppServerProtocol.json.parseToJsonElement(json).jsonObject
        val payload = root["payload"]?.jsonObject
        assertEquals("input", root["type"]?.jsonPrimitive?.content)
        assertEquals("agent-1", root["runtime"]?.jsonObject?.get("agent_id")?.jsonPrimitive?.content)
        assertEquals("conv-1", root["runtime"]?.jsonObject?.get("conversation_id")?.jsonPrimitive?.content)
        assertEquals("create_message", payload?.get("kind")?.jsonPrimitive?.content)
        assertEquals("hello", payload?.get("messages")?.jsonArray?.first()?.jsonObject?.get("content")?.jsonPrimitive?.content)
        assertEquals("local-1", payload?.get("messages")?.jsonArray?.first()?.jsonObject?.get("client_message_id")?.jsonPrimitive?.content)
        assertEquals("read_file", payload?.get("client_tool_allowlist")?.jsonArray?.first()?.jsonPrimitive?.content)
        assertEquals("mobile-scope", payload?.get("external_tool_scope_ids")?.jsonArray?.first()?.jsonPrimitive?.content)
    }

    @Test
    fun encodesApprovalResponseInputWithTypedDecisionBehavior() {
        val json = AppServerProtocol.encodeCommand(
            AppServerCommand.Input(
                runtime = runtime,
                payload = AppServerInputPayload.ApprovalResponse(
                    requestId = "approval-1",
                    decision = AppServerApprovalResponseDecision.Allow(
                        message = "approved",
                        selectedPermissionSuggestionIds = listOf("suggestion-1"),
                    ),
                ),
            ),
        )

        val payload = AppServerProtocol.json.parseToJsonElement(json).jsonObject["payload"]?.jsonObject
        val decision = payload?.get("decision")?.jsonObject
        assertEquals("approval_response", payload?.get("kind")?.jsonPrimitive?.content)
        assertEquals("approval-1", payload?.get("request_id")?.jsonPrimitive?.content)
        assertEquals("allow", decision?.get("behavior")?.jsonPrimitive?.content)
        assertEquals("suggestion-1", decision?.get("selected_permission_suggestion_ids")?.jsonArray?.first()?.jsonPrimitive?.content)
    }

    @Test
    fun encodesSyncAbortAndExternalToolResponseAsTypedCommands() {
        val sync = AppServerProtocol.json.parseToJsonElement(
            AppServerProtocol.encodeCommand(
                AppServerCommand.Sync(
                    runtime = runtime,
                    requestId = "sync-1",
                    recoverApprovals = false,
                    forceDeviceStatus = true,
                ),
            ),
        ).jsonObject
        assertEquals("sync", sync["type"]?.jsonPrimitive?.content)
        assertEquals("agent-1", sync["runtime"]?.jsonObject?.get("agent_id")?.jsonPrimitive?.content)
        assertEquals("sync-1", sync["request_id"]?.jsonPrimitive?.content)

        val abort = AppServerProtocol.json.parseToJsonElement(
            AppServerProtocol.encodeCommand(
                AppServerCommand.AbortMessage(
                    runtime = runtime,
                    requestId = "abort-1",
                    runId = "run-1",
                ),
            ),
        ).jsonObject
        assertEquals("abort_message", abort["type"]?.jsonPrimitive?.content)
        assertEquals("run-1", abort["run_id"]?.jsonPrimitive?.content)
        assertEquals("conv-1", abort["runtime"]?.jsonObject?.get("conversation_id")?.jsonPrimitive?.content)

        val toolResponse = AppServerProtocol.json.parseToJsonElement(
            AppServerProtocol.encodeCommand(
                AppServerCommand.ExternalToolCallResponse(
                    requestId = "external-tool-1",
                    result = AppServerExternalToolResult(
                        content = listOf(
                            AppServerExternalToolResultContent(
                                type = "text",
                                text = "done",
                            ),
                        ),
                        isError = false,
                    ),
                ),
            ),
        ).jsonObject
        assertEquals("external_tool_call_response", toolResponse["type"]?.jsonPrimitive?.content)
        assertEquals("external-tool-1", toolResponse["request_id"]?.jsonPrimitive?.content)
        assertNull(toolResponse["tool_call_id"])
        assertEquals("done", toolResponse["result"]?.jsonObject?.get("content")?.jsonArray?.first()?.jsonObject?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun decodesRuntimeStartResponseAndPreservesReturnedRuntimeExactly() {
        val received = AppServerProtocol.decodeFrame(
            rawJson = """
                {
                  "type": "runtime_start_response",
                  "request_id": "runtime-start-1",
                  "success": true,
                  "runtime": {
                    "agent_id": "agent-1",
                    "conversation_id": "conv-1",
                    "acting_user_id": "user-1"
                  },
                  "agent": {"id": "agent-1", "future": {"ok": true}},
                  "conversation": {"id": "conv-1"},
                  "created": {"agent": false, "conversation": true}
                }
            """.trimIndent(),
            channel = AppServerChannel.Control,
        )

        val frame = assertIs<AppServerInboundFrame.RuntimeStartResponse>(received.frame)
        assertEquals(AppServerChannel.Control, received.channel)
        assertEquals("runtime_start_response", frame.type)
        assertEquals("runtime-start-1", frame.requestId)
        assertEquals("agent-1", frame.runtime?.agentId)
        assertEquals("conv-1", frame.runtime?.conversationId)
        assertEquals("user-1", frame.runtime?.actingUserId)
        assertEquals("true", frame.agent?.get("future")?.jsonObject?.get("ok")?.jsonPrimitive?.content)
        assertEquals(true, frame.created?.conversation)
    }

    @Test
    fun decodesRuntimeEventsFromEitherChannelWithoutInterpretingUnknownDeltaShape() {
        val received = AppServerProtocol.decodeFrame(
            rawJson = """
                {
                  "type": "stream_delta",
                  "runtime": {"agent_id": "agent-1", "conversation_id": "conv-1"},
                  "event_seq": 12,
                  "emitted_at": "2026-06-24T00:00:00Z",
                  "idempotency_key": "evt-12",
                  "delta": {
                    "message_type": "unknown_future_delta",
                    "nested": {"value": 42}
                  }
                }
            """.trimIndent(),
            channel = AppServerChannel.Control,
        )

        val frame = assertIs<AppServerInboundFrame.StreamDelta>(received.frame)
        assertEquals(AppServerChannel.Control, received.channel)
        assertEquals("agent-1", frame.runtime.agentId)
        assertEquals("unknown_future_delta", frame.delta.jsonObject["message_type"]?.jsonPrimitive?.content)
        assertEquals("42", frame.delta.jsonObject["nested"]?.jsonObject?.get("value")?.jsonPrimitive?.content)
    }

    @Test
    fun unknownFrameDoesNotCrashAndPreservesRawJsonAndRequestId() {
        val received = AppServerProtocol.decodeFrame(
            rawJson = """
                {"type":"future_event","request_id":"future-1","payload":{"x":1}}
            """.trimIndent(),
            channel = AppServerChannel.Stream,
        )

        val frame = assertIs<AppServerInboundFrame.Unknown>(received.frame)
        assertEquals("future_event", frame.type)
        assertEquals("future-1", frame.requestId)
        assertEquals(AppServerChannel.Stream, received.channel)
        assertEquals("1", frame.raw["payload"]?.jsonObject?.get("x")?.jsonPrimitive?.content)
    }


    @Test
    fun encodesAdminRpcCommandWithProxyParams() {
        val encoded = AppServerProtocol.encodeCommand(
            AppServerCommand.AdminRpc(
                requestId = "admin-1",
                method = "message.list",
                params = kotlinx.serialization.json.buildJsonObject {
                    put("conversation_id", kotlinx.serialization.json.JsonPrimitive("conv-1"))
                    put("limit", kotlinx.serialization.json.JsonPrimitive("100"))
                    put("order", kotlinx.serialization.json.JsonPrimitive("asc"))
                },
            ),
        )

        val raw = AppServerProtocol.json.parseToJsonElement(encoded).jsonObject
        assertEquals("admin_rpc", raw["type"]?.jsonPrimitive?.content)
        assertEquals("admin-1", raw["request_id"]?.jsonPrimitive?.content)
        assertEquals("message.list", raw["method"]?.jsonPrimitive?.content)
        val params = raw["params"]?.jsonObject
        assertEquals("conv-1", params?.get("conversation_id")?.jsonPrimitive?.content)
        assertEquals("100", params?.get("limit")?.jsonPrimitive?.content)
        assertEquals("asc", params?.get("order")?.jsonPrimitive?.content)
    }

    @Test
    fun decodesAdminRpcSuccessAndFailureResponses() {
        val success = AppServerProtocol.decodeFrame(
            rawJson = """
                {
                  "type": "admin_rpc_response",
                  "request_id": "admin-1",
                  "success": true,
                  "result": {"ok": true, "value": 42}
                }
            """.trimIndent(),
            channel = AppServerChannel.Control,
        )

        val successFrame = assertIs<AppServerInboundFrame.AdminRpcResponse>(success.frame)
        assertEquals("admin-1", successFrame.requestId)
        assertEquals(true, successFrame.success)
        assertEquals("true", successFrame.result?.jsonObject?.get("ok")?.jsonPrimitive?.content)
        assertEquals("42", successFrame.result?.jsonObject?.get("value")?.jsonPrimitive?.content)

        val failure = AppServerProtocol.decodeFrame(
            rawJson = """
                {
                  "type": "admin_rpc_response",
                  "request_id": "admin-2",
                  "success": false,
                  "error": "unknown method"
                }
            """.trimIndent(),
            channel = AppServerChannel.Control,
        )

        val failureFrame = assertIs<AppServerInboundFrame.AdminRpcResponse>(failure.frame)
        assertEquals("admin-2", failureFrame.requestId)
        assertEquals(false, failureFrame.success)
        assertEquals("unknown method", failureFrame.error)
    }

    @Test
    fun decodesControlRequestForToolApproval() {
        val received = AppServerProtocol.decodeFrame(
            rawJson = """
                {
                  "type": "control_request",
                  "request_id": "approval-1",
                  "agent_id": "agent-1",
                  "conversation_id": "conv-1",
                  "request": {
                    "subtype": "can_use_tool",
                    "tool_name": "write_file",
                    "tool_call_id": "tool-call-1",
                    "input": {"path": "README.md"}
                  }
                }
            """.trimIndent(),
            channel = AppServerChannel.Control,
        )

        val frame = assertIs<AppServerInboundFrame.ControlRequest>(received.frame)
        assertEquals("approval-1", frame.requestId)
        assertEquals("agent-1", frame.runtime?.agentId)
        assertEquals("can_use_tool", frame.request["subtype"]?.jsonPrimitive?.content)
    }

    private companion object {
        val runtime = AppServerRuntimeScope(
            agentId = "agent-1",
            conversationId = "conv-1",
        )
    }
}
