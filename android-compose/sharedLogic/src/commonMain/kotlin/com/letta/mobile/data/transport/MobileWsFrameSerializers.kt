package com.letta.mobile.data.transport

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

/**
 * letta-mobile-2rkdj: hand-written deserializer for the `subscribe_frame`
 * envelope (§11). The wrapper carries `run_id` + `seq` + an opaque `frame`
 * object whose shape is whatever BridgeFrame the shim recorded. We keep
 * the inner `frame` as a raw [JsonObject] so the caller can re-decode it
 * with [ServerFrameSerializer] and route it through the same handler as
 * a live frame — replayed and live frames must take the same code path.
 */
private object SubscribeFrameDeserializer : KSerializer<ServerFrame> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ServerFrame.SubscribeFrameMessage")

    override fun deserialize(decoder: Decoder): ServerFrame {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("SubscribeFrameDeserializer requires a JsonDecoder")
        val element = jsonDecoder.decodeJsonElement().jsonObject
        val frameField = element["frame"]
        val innerFrame = (frameField as? JsonObject)
            ?: error("subscribe_frame envelope missing object-shaped 'frame' field")
        return ServerFrame.SubscribeFrameMessage(
            v = element["v"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
            id = element["id"]?.jsonPrimitive?.content.orEmpty(),
            ts = element["ts"]?.jsonPrimitive?.content.orEmpty(),
            runId = element["run_id"]?.jsonPrimitive?.content
                ?: error("subscribe_frame envelope missing 'run_id'"),
            seq = element["seq"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: error("subscribe_frame envelope missing numeric 'seq'"),
            frame = innerFrame,
        )
    }

    override fun serialize(
        encoder: Encoder,
        value: ServerFrame,
    ): Unit = error("ServerFrame.SubscribeFrameMessage is inbound-only; encoding it is never valid")
}

/**
 * Deserialize-only stand-in for the polymorphic strategy when the
 * envelope carries a `type` we don't recognize. Pulls the raw element
 * out of the [kotlinx.serialization.json.JsonDecoder] and packs it
 * into [ServerFrame.Unknown] without trying to map any individual
 * fields (we don't know the shape). Implements the full [KSerializer]
 * surface because [JsonContentPolymorphicSerializer.selectDeserializer]
 * casts its return value to KSerializer at runtime.
 */
private object UnknownFrameDeserializer : KSerializer<ServerFrame> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ServerFrame.Unknown")

    override fun deserialize(decoder: Decoder): ServerFrame {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("UnknownFrameDeserializer requires a JsonDecoder")
        val element = jsonDecoder.decodeJsonElement().jsonObject
        return ServerFrame.Unknown(
            v = element["v"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
            id = element["id"]?.jsonPrimitive?.content.orEmpty(),
            ts = element["ts"]?.jsonPrimitive?.content.orEmpty(),
            type = element["type"]?.jsonPrimitive?.content.orEmpty(),
            raw = element,
        )
    }

    /**
     * We never need to serialize an [ServerFrame.Unknown] (it's
     * inbound-only — the client never produces unknown server
     * frames). Throwing here makes any accidental encode loud.
     */
    override fun serialize(
        encoder: Encoder,
        value: ServerFrame,
    ): Unit = error("ServerFrame.Unknown is inbound-only; encoding it is never valid")
}
