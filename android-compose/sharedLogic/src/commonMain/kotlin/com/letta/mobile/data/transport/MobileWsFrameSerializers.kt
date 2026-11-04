package com.letta.mobile.data.transport

import com.letta.mobile.data.transport.serialization.SubscribeFrameDeserializer
import com.letta.mobile.data.transport.serialization.UnknownFrameDeserializer
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Discriminated-union deserializer for [ServerFrame], keyed on the
 * envelope `type` field. Matches the pattern used by
 * [com.letta.mobile.data.model.LettaMessageSerializer] for the inner
 * LettaMessage hierarchy.
 *
 * Unknown `type` values fall through to [ServerFrame.Unknown] so the
 * forward-compat silent-ignore rule (spec §2) is honored without
 * losing visibility into what the shim sent.
 */
object ServerFrameSerializer : JsonContentPolymorphicSerializer<ServerFrame>(ServerFrame::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ServerFrame> {
        val type = element.jsonObject["type"]?.jsonPrimitive?.content
        return when (type) {
            "error" -> ServerFrame.Error.serializer()
            "turn_started" -> ServerFrame.TurnStarted.serializer()
            "turn_done" -> ServerFrame.TurnDone.serializer()
            "stop_reason" -> ServerFrame.StopReason.serializer()
            "usage_statistics" -> ServerFrame.UsageStatistics.serializer()
            "user_message" -> ServerFrame.UserMessage.serializer()
            "assistant_message" -> ServerFrame.AssistantMessage.serializer()
            "reasoning_message" -> ServerFrame.ReasoningMessage.serializer()
            "tool_call_message",
            "approval_request_message" -> ServerFrame.ToolCallMessage.serializer()
            "tool_return_message" -> ServerFrame.ToolReturnMessage.serializer()
            "subscribe_frame" -> SubscribeFrameDeserializer
            "subscribe_done" -> ServerFrame.SubscribeDone.serializer()
            "turn_queued" -> ServerFrame.TurnQueued.serializer()
            "cron_list_response" -> ServerFrame.CronListResponse.serializer()
            "cron_add_response" -> ServerFrame.CronAddResponse.serializer()
            "cron_get_response" -> ServerFrame.CronGetResponse.serializer()
            "cron_delete_response" -> ServerFrame.CronDeleteResponse.serializer()
            "cron_delete_all_response" -> ServerFrame.CronDeleteAllResponse.serializer()
            "cron_pause_response" -> ServerFrame.CronPauseResponse.serializer()
            "cron_resume_response" -> ServerFrame.CronResumeResponse.serializer()
            "crons_updated" -> ServerFrame.CronsUpdated.serializer()
            "goals_updated" -> ServerFrame.GoalsUpdated.serializer()
            "agent_updated" -> ServerFrame.AgentUpdated.serializer()
            "conversation_activity" -> ServerFrame.ConversationActivity.serializer()
            "conversation_updated" -> ServerFrame.ConversationUpdated.serializer()
            "subagent_list_response" -> ServerFrame.SubagentListResponse.serializer()
            "subagent_todos_response" -> ServerFrame.SubagentTodosResponse.serializer()
            "subagents_updated" -> ServerFrame.SubagentsUpdated.serializer()
            "pending_approvals" -> ServerFrame.PendingApprovalsUpdated.serializer()
            "permission_mode" -> ServerFrame.PermissionModeUpdated.serializer()
            else -> UnknownFrameDeserializer
        }
    }
}
