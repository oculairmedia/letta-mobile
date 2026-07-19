package com.letta.mobile.data.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MobileWsFrameDiscriminatorCommonTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun everyClientDiscriminatorKeepsItsEncodedWireValue() {
        val frames = listOf(
            HelloFrame(id = "id", ts = "ts", token = "token"),
            SendMessageFrame(id = "id", ts = "ts", agentId = "agent", conversationId = "conversation", text = "text"),
            UserActionFrame(id = "id", ts = "ts", name = "action", context = buildJsonObject {}),
            CancelFrame(id = "id", ts = "ts", runId = "run"),
            SubscribeFrame(id = "id", ts = "ts", runId = "run"),
            ByeFrame(id = "id", ts = "ts"),
            CronListFrame(id = "id", ts = "ts", requestId = "request"),
            CronAddFrame(
                id = "id",
                ts = "ts",
                requestId = "request",
                agentId = "agent",
                name = "name",
                description = "description",
                prompt = "prompt",
                recurring = false,
            ),
            CronGetFrame(id = "id", ts = "ts", requestId = "request", taskId = "task"),
            CronDeleteFrame(id = "id", ts = "ts", requestId = "request", taskId = "task"),
            CronDeleteAllFrame(id = "id", ts = "ts", requestId = "request", agentId = "agent"),
            SubagentListFrame(id = "id", ts = "ts", requestId = "request"),
            SubagentTodosFrame(id = "id", ts = "ts", requestId = "request", toolCallId = "tool"),
        )

        assertEquals(
            listOf(
                "hello", "send_message", "user_action", "cancel", "subscribe", "bye",
                "cron_list", "cron_add", "cron_get", "cron_delete", "cron_delete_all",
                "subagent_list", "subagent_todos",
            ),
            frames.map { frame ->
                val encoded = frame.encodeJson(json)
                assertEquals(encoded, frame.encodeJson(json))
                json.parseToJsonElement(encoded).jsonObject.getValue("type").jsonPrimitive.content
            },
        )
    }

    @Test
    fun everyServerDiscriminatorSelectsItsExistingPublicSubtype() {
        val payloads = linkedMapOf(
            "welcome" to "\"server_id\":\"server\",\"session_id\":\"session\"",
            "error" to "\"code\":\"code\"",
            "turn_started" to routing() + ",\"agent_id\":\"agent\",\"conversation_id\":\"conversation\"",
            "turn_done" to "\"turn_id\":\"turn\",\"run_id\":\"run\",\"status\":\"completed\"",
            "stop_reason" to "\"stop_reason\":\"end_turn\"",
            "usage_statistics" to "",
            "user_message" to "\"content\":\"content\"",
            "assistant_message" to "\"content\":\"content\"",
            "reasoning_message" to routing() + ",\"agent_id\":\"agent\",\"conversation_id\":\"conversation\",\"reasoning\":\"reasoning\"",
            "tool_call_message" to routing() + ",\"agent_id\":\"agent\",\"conversation_id\":\"conversation\"",
            "approval_request_message" to routing() + ",\"agent_id\":\"agent\",\"conversation_id\":\"conversation\"",
            "tool_return_message" to "\"tool_call_id\":\"tool\"",
            "a2ui_frame" to "\"ok\":false",
            "a2ui_capabilities" to "\"version\":\"0.9\",\"catalog_id\":\"basic\"",
            "subscribe_frame" to "\"run_id\":\"run\",\"seq\":1,\"frame\":{}",
            "subscribe_done" to "\"run_id\":\"run\",\"last_seq\":1,\"status\":\"completed\"",
            "user_action_ack" to "\"action_id\":\"action\",\"status\":\"accepted\"",
            "user_action_outcome" to "\"frameId\":\"frame\",\"outcome\":\"recorded_only\"",
            "cron_list_response" to "\"success\":true",
            "cron_add_response" to "\"success\":true",
            "cron_get_response" to "\"success\":true",
            "cron_delete_response" to "\"success\":true",
            "cron_delete_all_response" to "\"success\":true",
            "crons_updated" to "\"reason\":\"changed\",\"at\":\"ts\"",
            "goals_updated" to "",
            "agent_updated" to "\"agent_id\":\"agent\"",
            "subagent_list_response" to "\"success\":true",
            "subagent_todos_response" to "\"success\":true",
            "subagents_updated" to "",
        )

        val decoded = payloads.map { (type, fields) ->
            val suffix = if (fields.isEmpty()) "" else ",$fields"
            json.decodeFromString(ServerFrameSerializer, "{\"v\":1,\"type\":\"$type\",\"id\":\"id\",\"ts\":\"ts\"$suffix}")
        }

        assertEquals(
            listOf(
                "Welcome", "Error", "TurnStarted", "TurnDone", "StopReason", "UsageStatistics",
                "UserMessage", "AssistantMessage", "ReasoningMessage", "ToolCallMessage", "ToolCallMessage",
                "ToolReturnMessage", "A2ui", "A2uiCapabilities", "SubscribeFrameMessage", "SubscribeDone",
                "UserActionAck", "UserActionOutcome", "CronListResponse", "CronAddResponse", "CronGetResponse",
                "CronDeleteResponse", "CronDeleteAllResponse", "CronsUpdated", "GoalsUpdated", "AgentUpdated",
                "SubagentListResponse", "SubagentTodosResponse", "SubagentsUpdated",
            ),
            decoded.map { it::class.simpleName },
        )
        assertIs<ServerFrame.ToolCallMessage>(decoded[payloads.keys.indexOf("approval_request_message")])
        decoded.forEach { frame -> assertEquals(1, frame.v) }
    }

    @Test
    fun unknownAndMalformedEnvelopesKeepExistingBehavior() {
        val unknown = json.decodeFromString(
            ServerFrameSerializer,
            "{\"type\":\"future_type\",\"extra\":true}",
        )
        assertIs<ServerFrame.Unknown>(unknown)
        assertEquals("future_type", unknown.type)
        assertEquals("", unknown.id)
        assertEquals("", unknown.ts)

        val missingType = json.decodeFromString(ServerFrameSerializer, "{\"id\":\"id\",\"extra\":true}")
        assertIs<ServerFrame.Unknown>(missingType)
        assertEquals("", missingType.type)

        assertFails {
            json.decodeFromString(ServerFrameSerializer, "{\"type\":\"subscribe_frame\",\"frame\":[]}")
        }
        assertFails { json.decodeFromString(ServerFrameSerializer, "[]") }
    }

    private fun routing(): String = "\"turn_id\":\"turn\",\"run_id\":\"run\""
}
