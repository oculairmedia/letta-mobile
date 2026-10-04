package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.runtime.isTurnAlreadyActiveMessage
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ToolCallPayload
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.util.Telemetry
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
        // Lifecycle only (error / stop / usage frames with no turn of their own). A message
        // row's turn is the wire `turn_id`; these never name a message.
        val turnId: String?,
        val runId: String?,
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
            ?: return mapPlainBody(payload)
        val delta = envelope["delta"].objectOrNull() ?: envelope
        val messageType = delta.string("message_type") ?: payload.messageType
            ?: return emptyList()
        val meta = Metadata.from(payload, envelope, delta, context)

        return when (messageType) {
            // letta-mobile-r3i1z (observer ingestion): the fanned-out user echo
            // arrives as a `user_message` stream_delta (id = cm-user-<otid>,
            // otid = <otid>, content = text/content-parts). The INITIATOR never
            // needs this branch — it dedups against its own optimistic Local row —
            // but a passive OBSERVER has no optimistic twin, so it must project the
            // echo into a real user row. The stable `cm-user-<otid>` id + otid make
            // the reducer collapse replays idempotently (eaczz.5). Emitting it here
            // (instead of a separate observer-only mapper) keeps observer frame
            // shape byte-identical to what the initiator path would produce.
            //
            // letta-mobile-utw4u: `content` on the wire is EITHER a plain string
            // (text-only sends) OR a multimodal `content_parts` array (text +
            // base64 image). The previous [delta.contentText()] flattens the array
            // into "look at this..." plus base64 garbage, dropping every image on
            // every observer. Forward [contentRaw] verbatim; [ServerFrame.UserMessage.content]
            // derives the text-only projection for legacy String readers.
            "user_message" -> listOf(
                ServerFrame.UserMessage(
                    id = delta.string("id") ?: meta.messageId(),
                    ts = meta.timestamp,
                    agentId = meta.agentId,
                    conversationId = meta.conversationId,
                    turnId = meta.stamp.turnId,
                    runId = meta.runId,
                    contentRaw = delta["content"]?.takeIf { it != JsonNull } ?: JsonPrimitive(delta.contentText()),
                    otid = delta.string("otid") ?: delta.string("client_message_id"),
                    seq = meta.eventSeq,
                    seqId = meta.seqId,
                    logicalMessageId = meta.stamp.logicalId,
                ),
            )

            "assistant_message",
            "reasoning_message",
            "hidden_reasoning_message" -> mapTextRow(messageType, delta, meta)

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
            "error_message" -> mapErrorMessage(delta, meta)

            else -> emptyList()
        }
    }

    /**
     * An assistant / reasoning text frame is named by the `logical_message_id` the stream stamper
     * minted, and nothing else: no id is derived here. A frame without that stamp cannot be placed
     * in the timeline, so it is dropped and counted rather than appended under a guessed identity.
     */
    private fun mapTextRow(messageType: String, delta: JsonObject, meta: Metadata): List<ServerFrame> {
        val logicalId = meta.stamp.logicalId ?: return dropUnstampedText(messageType, meta.frameId)
        return listOf(
            if (messageType == "assistant_message") {
                assistantRow(logicalId, delta, meta)
            } else {
                reasoningRow(logicalId, delta, meta)
            },
        )
    }

    private fun assistantRow(logicalId: String, delta: JsonObject, meta: Metadata) =
        ServerFrame.AssistantMessage(
            id = logicalId,
            ts = meta.timestamp,
            agentId = meta.agentId,
            conversationId = meta.conversationId,
            turnId = meta.stamp.turnId,
            runId = meta.runId,
            content = delta.contentText(),
            otid = delta.string("otid") ?: delta.string("client_message_id"),
            seq = meta.eventSeq,
            seqId = meta.seqId,
            logicalMessageId = logicalId,
            textSeq = meta.stamp.textSeq,
        )

    private fun reasoningRow(logicalId: String, delta: JsonObject, meta: Metadata) =
        ServerFrame.ReasoningMessage(
            id = logicalId,
            ts = meta.timestamp,
            agentId = meta.agentId,
            conversationId = meta.conversationId,
            turnId = meta.stamp.turnId,
            runId = meta.runId,
            reasoning = delta.reasoningText(),
            signature = delta.string("signature"),
            seq = meta.eventSeq,
            seqId = meta.seqId,
            logicalMessageId = logicalId,
            textSeq = meta.stamp.textSeq,
        )

    private fun dropUnstampedText(messageType: String, frameId: String): List<ServerFrame> {
        Telemetry.event(
            "IrohStreamDelta", "live.unstampedFrame",
            "messageType" to messageType,
            "frameId" to frameId,
            level = Telemetry.Level.WARN,
        )
        return emptyList()
    }

    private fun mapErrorMessage(delta: JsonObject, meta: Metadata): List<ServerFrame> {
        val message = delta.errorText()
        val busy = isTurnAlreadyActiveMessage(message)
        val initiatorBusy =
            busy && delta.string("iroh_rejection") == INITIATOR_BUSY_REJECTION
        val errorFrame = ServerFrame.Error(
            id = meta.frameId,
            ts = meta.timestamp,
            code = if (busy) "iroh_turn_engine_busy" else "app_server_error",
            message = message,
            conversationId = meta.conversationId,
            turnId = meta.turnId,
            runId = meta.runId,
        )
        // Untagged busy frames stay Error-only: a broadcast busy must not
        // synthesize TurnDone onto the owning peer's live turn. Initiator-only
        // rejections are tagged and get a failed TurnDone so peer B leaves
        // Thinking while peer A continues unaffected.
        if (busy && !initiatorBusy) return listOf(errorFrame)
        return listOf(
            errorFrame,
            ServerFrame.TurnDone(
                id = meta.frameId,
                ts = meta.timestamp,
                turnId = meta.turnId.orEmpty(),
                runId = meta.runId.orEmpty(),
                status = "failed",
                seq = meta.eventSeq,
            ),
        )
    }

    private const val INITIATOR_BUSY_REJECTION = "initiator_busy"

    /** A body that is not a JSON envelope carries no stamp, so a text one cannot be placed in the timeline. */
    private fun mapPlainBody(payload: RuntimeEventPayload.RemoteStreamFrame): List<ServerFrame> =
        when (val type = payload.messageType ?: "assistant_message") {
            "assistant_message",
            "reasoning_message",
            "hidden_reasoning_message" -> dropUnstampedText(type, payload.frameId)
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
                turnId = meta.stamp.turnId,
                runId = meta.runId,
                toolCall = firstCall,
                toolCalls = calls.takeIf { it.isNotEmpty() },
                seq = meta.eventSeq,
                logicalMessageId = meta.stamp.logicalId,
            ),
        )
    }

    private fun mapToolReturn(
        delta: JsonObject,
        meta: Metadata,
    ): ServerFrame.ToolReturnMessage {
        val canonical = canonicalToolReturn(delta, meta.frameId)
        return ServerFrame.ToolReturnMessage(
            id = delta.string("id") ?: "toolreturn-${canonical.toolCallId}",
            ts = meta.timestamp,
            agentId = meta.agentId,
            conversationId = meta.conversationId,
            turnId = meta.stamp.turnId,
            runId = meta.runId,
            toolCallId = canonical.toolCallId,
            status = canonical.status,
            toolReturn = canonical.body,
            stdout = delta["stdout"].stringArrayOrNull(),
            stderr = delta["stderr"].stringArrayOrNull(),
            seq = meta.eventSeq,
            logicalMessageId = meta.stamp.logicalId,
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

    /** What the host's stream stamper wrote on a delta; every field is null for a frame it never saw. */
    private data class Stamp(val logicalId: String?, val turnId: String?, val textSeq: Int?) {
        companion object {
            fun from(envelope: JsonObject, delta: JsonObject) = Stamp(
                logicalId = delta.string("logical_message_id")?.takeIf { it.isNotBlank() },
                turnId = (delta.string("turn_id") ?: envelope.string("turn_id"))?.takeIf { it.isNotBlank() },
                textSeq = delta.long("text_seq")?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt(),
            )
        }
    }

    private data class Metadata(
        val frameId: String,
        val eventSeq: Long?,
        val seqId: Int?,
        val timestamp: String,
        val agentId: String,
        val conversationId: String,
        val stamp: Stamp,
        /** The stamp's turn or the caller's lifecycle turn: for error / stop / usage frames only. */
        val turnId: String?,
        val runId: String?,
        private val messageId: String?,
    ) {
        fun messageId(): String = messageId ?: frameId

        companion object {
            fun from(
                payload: RuntimeEventPayload.RemoteStreamFrame,
                envelope: JsonObject,
                delta: JsonObject,
                context: Context,
            ): Metadata {
                val runtime = envelope["runtime"].objectOrNull()
                val eventSeq = envelope.long("event_seq") ?: delta.long("event_seq")
                val stamp = Stamp.from(envelope, delta)
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
                    stamp = stamp,
                    turnId = stamp.turnId ?: context.turnId,
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

internal data class CanonicalToolCall(
    val toolCallId: String,
    val name: String,
    val arguments: String,
)

internal fun canonicalToolCalls(delta: JsonObject): List<CanonicalToolCall> {
    val arrayCalls = (delta["tool_calls"] as? JsonArray)
        ?.mapNotNull { canonicalToolCallElement(it as? JsonObject) }
        .orEmpty()
    if (arrayCalls.isNotEmpty()) return arrayCalls
    return listOfNotNull(canonicalToolCallElement(delta["tool_call"] as? JsonObject ?: delta))
}

private fun canonicalToolCallElement(call: JsonObject?): CanonicalToolCall? {
    call ?: return null
    val function = call["function"] as? JsonObject
    val id = call.stringValue("tool_call_id")
        ?: call.stringValue("id")
        ?: return null
    val name = call.stringValue("name")
        ?: call.stringValue("tool_name")
        ?: function?.stringValue("name")
        ?: "tool"
    val arguments = call["arguments"]
        ?: call["input"]
        ?: function?.get("arguments")
        ?: function?.get("input")
    return CanonicalToolCall(id, name, arguments.argumentValue())
}

internal data class CanonicalToolReturn(
    val toolCallId: String,
    val status: String,
    val body: JsonElement?,
)

internal fun canonicalToolReturn(delta: JsonObject, defaultId: String? = null): CanonicalToolReturn {
    val returnObject = delta["tool_return"] as? JsonObject
    return CanonicalToolReturn(
        toolCallId = delta.stringValue("tool_call_id")
            ?: returnObject?.stringValue("tool_call_id")
            ?: defaultId.orEmpty(),
        status = delta.stringValue("status") ?: returnObject?.stringValue("status") ?: "success",
        body = delta["tool_return"]
            ?: delta["output"]
            ?: delta["message"]
            ?: delta["content"],
    )
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.argumentValue(): String = when (this) {
    is JsonPrimitive -> contentOrNull ?: toString()
    null -> "{}"
    else -> toString()
}
