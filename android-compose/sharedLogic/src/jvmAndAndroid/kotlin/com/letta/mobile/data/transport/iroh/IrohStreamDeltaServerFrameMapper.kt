package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ToolCallPayload
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Projects App Server `stream_delta` envelopes received over Iroh back into the
 * same [ServerFrame] variants used by the websocket bridge. Iroh is transport
 * only here; this mapper preserves the App Server envelope metadata instead of
 * flattening every delta into assistant text.
 */
internal object IrohStreamDeltaServerFrameMapper {
    data class Context(
        val agentId: String,
        val conversationId: String,
        val turnId: String,
        val runId: String,
        val timestamp: String,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun map(
        payload: RuntimeEventPayload.RemoteStreamFrame,
        context: Context,
    ): List<ServerFrame> {
        val envelope = payload.body.parseObjectOrNull()
            ?: return mapPlainBody(payload, context)
        val delta = envelope["delta"].objectOrNull() ?: envelope
        val messageType = delta.string("message_type") ?: payload.messageType
            ?: return emptyList()
        val meta = Metadata.from(payload, envelope, delta, context)

        return when (messageType) {
            "assistant_message" -> listOf(
                ServerFrame.AssistantMessage(
                    id = meta.messageId(),
                    ts = meta.timestamp,
                    agentId = meta.agentId,
                    conversationId = meta.conversationId,
                    turnId = meta.turnId,
                    runId = meta.runId,
                    content = delta.contentText(),
                    // letta-mobile-x1xnl (root cause): App Server assistant
                    // stream_delta frames carry NO `otid`/`client_message_id`
                    // (those are user-message echo fields), and over Iroh the
                    // backend `id` ROTATES per streamed fragment. The client
                    // projection then synthesizes `effectiveOtid` off that
                    // rotating id (server-<id>-assistant-<runId>), so every
                    // fragment gets a DIFFERENT otid — defeating every otid- and
                    // serverId-keyed dedup/merge branch in the reducer and
                    // stranding the trailing fragment(s) as a duplicate row.
                    // Anchor a STABLE otid on the (stable, per-turn) turn id
                    // instead so all fragments of one assistant message group
                    // into one row. A wire-provided otid still wins when present.
                    // The WS path is unaffected: it keeps a stable backend id and
                    // its own otid derivation.
                    otid = delta.string("otid")
                        ?: delta.string("client_message_id")
                        ?: meta.assistantStreamOtid(),
                    seq = meta.eventSeq,
                    seqId = meta.seqId,
                ),
            )

            "reasoning_message",
            "hidden_reasoning_message" -> listOf(
                ServerFrame.ReasoningMessage(
                    id = meta.messageIdFor(messageType),
                    ts = meta.timestamp,
                    agentId = meta.agentId,
                    conversationId = meta.conversationId,
                    turnId = meta.turnId,
                    runId = meta.runId,
                    reasoning = delta.reasoningText(),
                    signature = delta.string("signature"),
                    seq = meta.eventSeq,
                    seqId = meta.seqId,
                ),
            )

            "tool_call_message",
            "approval_request_message" -> mapToolCall(messageType, delta, meta)

            "tool_return_message" -> listOf(mapToolReturn(delta, meta))

            "usage_statistics" -> listOf(mapUsage(delta, meta))

            // Parity with the TS shim (mobile-channel-host.ts lcp-8ri): a
            // stop_reason frame reaching this mapper is NON-terminal (the
            // runtime event mapper already converts terminal reasons into a
            // Completed lifecycle). Multi-step tool turns emit intermediate
            // stop_reasons like `requires_approval`; emitting TurnDone here
            // ended the UI turn before the tool return / post-tool assistant
            // continuation. Emit only the StopReason frame — TurnDone comes
            // exclusively from the engine's terminal lifecycle.
            "stop_reason" -> listOf(
                ServerFrame.StopReason(
                    id = meta.frameId,
                    ts = meta.timestamp,
                    turnId = meta.turnId,
                    runId = meta.runId,
                    stopReason = delta.string("stop_reason") ?: delta.string("reason") ?: "end_turn",
                    seq = meta.eventSeq,
                ),
            )

            "loop_error",
            "error_message" -> listOf(
                ServerFrame.Error(
                    id = meta.frameId,
                    ts = meta.timestamp,
                    code = "app_server_error",
                    message = delta.errorText(),
                    conversationId = meta.conversationId,
                    turnId = meta.turnId,
                    runId = meta.runId,
                ),
                ServerFrame.TurnDone(
                    id = meta.frameId,
                    ts = meta.timestamp,
                    turnId = meta.turnId,
                    runId = meta.runId,
                    status = "failed",
                    seq = meta.eventSeq,
                ),
            )

            else -> emptyList()
        }
    }

    private fun mapPlainBody(
        payload: RuntimeEventPayload.RemoteStreamFrame,
        context: Context,
    ): List<ServerFrame> =
        when (payload.messageType) {
            null,
            "assistant_message" -> listOf(
                ServerFrame.AssistantMessage(
                    id = payload.messageId ?: payload.frameId,
                    ts = context.timestamp,
                    agentId = context.agentId,
                    conversationId = context.conversationId,
                    turnId = context.turnId,
                    runId = context.runId,
                    content = payload.body,
                ),
            )
            "reasoning_message",
            "hidden_reasoning_message" -> listOf(
                ServerFrame.ReasoningMessage(
                    id = payload.messageId ?: payload.frameId,
                    ts = context.timestamp,
                    agentId = context.agentId,
                    conversationId = context.conversationId,
                    turnId = context.turnId,
                    runId = context.runId,
                    reasoning = payload.body,
                ),
            )
            else -> emptyList()
        }

    private fun mapToolCall(
        messageType: String,
        delta: JsonObject,
        meta: Metadata,
    ): List<ServerFrame> {
        val calls = delta.toolCalls(meta.frameId)
        val firstCall = calls.firstOrNull()
        val id = delta.string("id")
            ?: firstCall?.toolCallId?.let { "toolcall-$it" }
            ?: meta.frameId
        return listOf(
            ServerFrame.ToolCallMessage(
                type = messageType,
                id = id,
                ts = meta.timestamp,
                agentId = meta.agentId,
                conversationId = meta.conversationId,
                turnId = meta.turnId,
                runId = meta.runId,
                toolCall = firstCall,
                toolCalls = calls.takeIf { it.isNotEmpty() },
                seq = meta.eventSeq,
            ),
        )
    }

    private fun mapToolReturn(
        delta: JsonObject,
        meta: Metadata,
    ): ServerFrame.ToolReturnMessage {
        val returnObject = delta["tool_return"].objectOrNull()
        val toolCallId = delta.string("tool_call_id")
            ?: returnObject?.string("tool_call_id")
            ?: meta.frameId
        return ServerFrame.ToolReturnMessage(
            id = delta.string("id") ?: "toolreturn-$toolCallId",
            ts = meta.timestamp,
            agentId = meta.agentId,
            conversationId = meta.conversationId,
            turnId = meta.turnId,
            runId = meta.runId,
            toolCallId = toolCallId,
            status = delta.string("status") ?: returnObject?.string("status") ?: "success",
            toolReturn = delta["tool_return"]
                ?: delta["output"]
                ?: delta["message"]
                ?: delta["content"],
            stdout = delta["stdout"].stringArrayOrNull(),
            stderr = delta["stderr"].stringArrayOrNull(),
            seq = meta.eventSeq,
        )
    }

    private fun mapUsage(
        delta: JsonObject,
        meta: Metadata,
    ): ServerFrame.UsageStatistics =
        ServerFrame.UsageStatistics(
            id = meta.frameId,
            ts = meta.timestamp,
            turnId = meta.turnId,
            runId = meta.runId,
            promptTokens = delta.long("prompt_tokens") ?: 0L,
            completionTokens = delta.long("completion_tokens") ?: 0L,
            totalTokens = delta.long("total_tokens") ?: 0L,
            cachedInputTokens = delta.long("cached_input_tokens") ?: 0L,
            reasoningTokens = delta.long("reasoning_tokens") ?: 0L,
            seq = meta.eventSeq,
        )

    private data class Metadata(
        val frameId: String,
        val eventSeq: Long?,
        val seqId: Int?,
        val timestamp: String,
        val agentId: String,
        val conversationId: String,
        val turnId: String,
        val runId: String,
        private val messageId: String?,
    ) {
        fun messageId(): String = messageId ?: frameId

        fun messageIdFor(messageType: String): String = when (messageType) {
            "reasoning_message",
            "hidden_reasoning_message" -> "iroh-$messageType-$runId-$turnId"
            else -> messageId ?: frameId
        }
        /**
         * Stable synthetic otid for an assistant stream_delta whose wire frame
         * carries no otid. Anchored on the (stable, per-turn) [turnId] — NOT the
         * rotating backend id and NOT the run id — so every fragment of the SAME
         * assistant message shares one otid and the reducer merges them into a
         * single row, instead of the rotating id producing a new otid per
         * fragment and stranding the tail as a duplicate row (letta-mobile-x1xnl).
         *
         * [turnId] is deliberate: over Iroh the run id starts as the
         * client-synthetic `iroh-run-*` placeholder and is later promoted to the
         * real server run id mid-stream, so keying on run id would still split
         * pre- and post-promotion fragments. The turn id is minted once per
         * send() and is invariant for the whole turn, so it is the stable
         * grouping anchor. Distinct assistant messages within one turn (rare;
         * tool-mediated multi-assistant runs) are still separated downstream by
         * the reducer's content-aware, seq-ordered merge; grouping the in-flight
         * message's fragments is strictly safer than the current per-fragment
         * split that produces the visible duplicate.
         */
        fun assistantStreamOtid(): String = "iroh-assistant-$turnId"
        companion object {
            fun from(
                payload: RuntimeEventPayload.RemoteStreamFrame,
                envelope: JsonObject,
                delta: JsonObject,
                context: Context,
            ): Metadata {
                val runtime = envelope["runtime"].objectOrNull()
                val eventSeq = envelope.long("event_seq") ?: delta.long("event_seq")
                return Metadata(
                    frameId = envelope.string("idempotency_key") ?: payload.frameId,
                    eventSeq = eventSeq,
                    seqId = eventSeq?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt(),
                    timestamp = envelope.string("emitted_at")
                        ?: delta.string("date")
                        ?: delta.string("created_at")
                        ?: context.timestamp,
                    agentId = runtime?.string("agent_id")
                        ?: delta.string("agent_id")
                        ?: context.agentId,
                    conversationId = runtime?.string("conversation_id")
                        ?: delta.string("conversation_id")
                        ?: context.conversationId,
                    turnId = envelope.string("turn_id")
                        ?: delta.string("turn_id")
                        ?: context.turnId,
                    runId = delta.string("run_id")
                        ?: envelope.string("run_id")
                        ?: context.runId,
                    messageId = delta.string("id") ?: delta.string("message_id") ?: payload.messageId,
                )
            }
        }
    }

    private fun String.parseObjectOrNull(): JsonObject? {
        val trimmed = trimStart()
        if (!trimmed.startsWith("{")) return null
        return runCatching { json.parseToJsonElement(this).objectOrNull() }.getOrNull()
    }

    private fun JsonObject.toolCalls(defaultId: String): List<ToolCallPayload> {
        val explicitCalls = this["tool_calls"].toolCallElements()
            .mapNotNull { it.toToolCallPayload(defaultId) }
        if (explicitCalls.isNotEmpty()) return explicitCalls

        this["tool_call"]?.toToolCallPayload(defaultId)?.let { return listOf(it) }

        val toolName = string("tool_name") ?: string("name")
        val callId = string("tool_call_id") ?: string("id")
        return if (toolName != null || callId != null || containsKey("arguments") || containsKey("input")) {
            listOf(
                ToolCallPayload(
                    toolCallId = callId ?: defaultId,
                    name = toolName ?: "tool",
                    arguments = (this["arguments"] ?: this["input"]).argumentString(),
                ),
            )
        } else {
            emptyList()
        }
    }

    private fun JsonElement?.toolCallElements(): List<JsonElement> =
        when (this) {
            is JsonArray -> toList()
            null,
            JsonNull -> emptyList()
            else -> listOf(this)
        }

    private fun JsonElement?.toToolCallPayload(defaultId: String): ToolCallPayload? {
        val obj = this.objectOrNull() ?: return null
        val function = obj["function"].objectOrNull()
        val callId = obj.string("tool_call_id")
            ?: obj.string("id")
            ?: function?.string("tool_call_id")
            ?: defaultId
        val name = obj.string("name")
            ?: obj.string("tool_name")
            ?: function?.string("name")
            ?: "tool"
        val arguments = obj["arguments"]
            ?: obj["input"]
            ?: function?.get("arguments")
            ?: function?.get("input")
        return ToolCallPayload(
            toolCallId = callId,
            name = name,
            arguments = arguments.argumentString(),
        )
    }

    private fun JsonObject.contentText(): String =
        textFrom("content") ?: textFrom("text") ?: textFrom("message") ?: ""

    private fun JsonObject.reasoningText(): String =
        textFrom("reasoning")
            ?: textFrom("hidden_reasoning")
            ?: textFrom("content")
            ?: textFrom("text")
            ?: textFrom("message")
            ?: ""

    private fun JsonObject.errorText(): String =
        textFrom("message")
            ?: textFrom("content")
            ?: this["api_error"].objectOrNull()?.textFrom("message")
            ?: this["api_error"].objectOrNull()?.textFrom("detail")
            ?: "App Server turn failed"

    private fun JsonObject.textFrom(key: String): String? =
        this[key].textContent()?.takeIf { it.isNotBlank() }

    private fun JsonElement?.textContent(): String? =
        when (this) {
            null,
            JsonNull -> null
            is JsonPrimitive -> contentOrNull ?: toString()
            is JsonArray -> mapNotNull { element ->
                val obj = element.objectOrNull()
                when (obj?.string("type")) {
                    "text" -> obj.textFrom("text")
                    else -> obj?.textFrom("text") ?: obj?.textFrom("content")
                }
            }.joinToString("").takeIf { it.isNotBlank() }
            is JsonObject -> textFrom("text") ?: textFrom("content") ?: textFrom("message") ?: toString()
        }

    private fun JsonElement?.argumentString(): String =
        when (this) {
            null,
            JsonNull -> "{}"
            is JsonPrimitive -> contentOrNull ?: toString()
            else -> toString()
        }

    private fun JsonElement?.stringArrayOrNull(): List<String>? {
        val array = this as? JsonArray ?: return null
        return array.mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    private fun JsonElement?.objectOrNull(): JsonObject? =
        runCatching { this as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull
            ?: this[key]?.jsonPrimitive?.intOrNull?.toLong()
            ?: this[key]?.jsonPrimitive?.booleanOrNull?.let { if (it) 1L else 0L }
}
