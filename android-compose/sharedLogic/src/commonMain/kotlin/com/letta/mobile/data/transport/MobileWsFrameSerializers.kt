package com.letta.mobile.data.transport

import com.letta.mobile.data.a2ui.decodeA2uiMessages
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
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
    override fun selectDeserializer(element: JsonElement): kotlinx.serialization.DeserializationStrategy<ServerFrame> {
        val type = element.jsonObject["type"]?.jsonPrimitive?.content
        return when (type) {
            "welcome" -> ServerFrame.Welcome.serializer()
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
            "a2ui_frame" -> A2uiFrameDeserializer
            "a2ui_capabilities" -> ServerFrame.A2uiCapabilities.serializer()
            "subscribe_frame" -> SubscribeFrameDeserializer
            "subscribe_done" -> ServerFrame.SubscribeDone.serializer()
            "user_action_ack" -> ServerFrame.UserActionAck.serializer()
            "user_action_outcome" -> ServerFrame.UserActionOutcome.serializer()
            "cron_list_response" -> ServerFrame.CronListResponse.serializer()
            "cron_add_response" -> ServerFrame.CronAddResponse.serializer()
            "cron_get_response" -> ServerFrame.CronGetResponse.serializer()
            "cron_delete_response" -> ServerFrame.CronDeleteResponse.serializer()
            "cron_delete_all_response" -> ServerFrame.CronDeleteAllResponse.serializer()
            "crons_updated" -> ServerFrame.CronsUpdated.serializer()
            "goals_updated" -> ServerFrame.GoalsUpdated.serializer()
            "agent_updated" -> ServerFrame.AgentUpdated.serializer()
            "subagent_list_response" -> ServerFrame.SubagentListResponse.serializer()
            "subagent_todos_response" -> ServerFrame.SubagentTodosResponse.serializer()
            "subagents_updated" -> ServerFrame.SubagentsUpdated.serializer()
            else -> UnknownFrameDeserializer
        }
    }
}

private object A2uiFrameDeserializer : kotlinx.serialization.KSerializer<ServerFrame> {
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
        kotlinx.serialization.descriptors.buildClassSerialDescriptor("ServerFrame.A2ui")

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ServerFrame {
        val jsonDecoder = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: error("A2uiFrameDeserializer requires a JsonDecoder")
        val element = jsonDecoder.decodeJsonElement().jsonObject
        // Shim §2.2: payload lives under `a2ui` (single object or array
        // of v0.9 messages). On `ok=false` the field may be absent.
        val payload = element["a2ui"]
        val ok = element["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        val messages = payload?.let { decodeA2uiMessages(jsonDecoder.json, it) }.orEmpty()
        return ServerFrame.A2ui(
            v = element["v"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
            id = element["id"]?.jsonPrimitive?.content.orEmpty(),
            ts = element["ts"]?.jsonPrimitive?.content.orEmpty(),
            agentId = element["agent_id"]?.jsonPrimitive?.content,
            conversationId = element["conversation_id"]?.jsonPrimitive?.content,
            turnId = element["turn_id"]?.jsonPrimitive?.content,
            runId = element["run_id"]?.jsonPrimitive?.content,
            requestId = element["request_id"]?.jsonPrimitive?.content,
            seq = element["seq"]?.jsonPrimitive?.content?.toLongOrNull(),
            otid = element["otid"]?.jsonPrimitive?.content,
            ok = ok,
            parseError = element["parse_error"]?.jsonPrimitive?.content,
            validationError = element["validation_error"]?.jsonPrimitive?.content,
            messages = messages,
            raw = element,
        )
    }

    override fun serialize(
        encoder: kotlinx.serialization.encoding.Encoder,
        value: ServerFrame,
    ): Unit = error("ServerFrame.A2ui is inbound-only; encoding it is never valid")
}

/**
 * letta-mobile-2rkdj: hand-written deserializer for the `subscribe_frame`
 * envelope (§11). The wrapper carries `run_id` + `seq` + an opaque `frame`
 * object whose shape is whatever BridgeFrame the shim recorded. We keep
 * the inner `frame` as a raw [JsonObject] so the caller can re-decode it
 * with [ServerFrameSerializer] and route it through the same handler as
 * a live frame — replayed and live frames must take the same code path.
 */
private object SubscribeFrameDeserializer : kotlinx.serialization.KSerializer<ServerFrame> {
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
        kotlinx.serialization.descriptors.buildClassSerialDescriptor("ServerFrame.SubscribeFrameMessage")

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ServerFrame {
        val jsonDecoder = decoder as? kotlinx.serialization.json.JsonDecoder
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
        encoder: kotlinx.serialization.encoding.Encoder,
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
private object UnknownFrameDeserializer : kotlinx.serialization.KSerializer<ServerFrame> {
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
        kotlinx.serialization.descriptors.buildClassSerialDescriptor("ServerFrame.Unknown")

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): ServerFrame {
        val jsonDecoder = decoder as? kotlinx.serialization.json.JsonDecoder
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
        encoder: kotlinx.serialization.encoding.Encoder,
        value: ServerFrame,
    ): Unit = error("ServerFrame.Unknown is inbound-only; encoding it is never valid")
}
