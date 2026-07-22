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
            // letta-mobile-r3i1z (observer ingestion): the fanned-out user echo
            // arrives as a `user_message` stream_delta (id = cm-user-<otid>,
            // otid = <otid>, content = text/content-parts). The INITIATOR never
            // needs this branch — it dedups against its own optimistic Local row —
            // but a passive OBSERVER has no optimistic twin, so it must project the
            // echo into a real user row. The stable `cm-user-<otid>` id + otid make
            // the reducer collapse replays idempotently (eaczz.5). Emitting it here
            // (instead of a separate observer-only mapper) keeps observer frame
            // shape byte-identical to what the initiator path would produce.
            "user_message" -> listOf(
                ServerFrame.UserMessage(
                    id = delta.string("id") ?: meta.messageId(),
                    ts = meta.timestamp,
                    agentId = meta.agentId,
                    conversationId = meta.conversationId,
                    turnId = meta.turnId,
                    runId = meta.runId,
                    content = delta.contentText(),
                    otid = delta.string("otid") ?: delta.string("client_message_id"),
                    seq = meta.eventSeq,
                    seqId = meta.seqId,
                ),
            )

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
        val canonical = canonicalToolReturn(delta, meta.frameId)
        return ServerFrame.ToolReturnMessage(
            id = delta.string("id") ?: "toolreturn-${canonical.toolCallId}",
            ts = meta.timestamp,
            agentId = meta.agentId,
            conversationId = meta.conversationId,
            turnId = meta.turnId,
            runId = meta.runId,
            toolCallId = canonical.toolCallId,
            status = canonical.status,
            toolReturn = canonical.body,
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
         * carries no otid.
         *
         * Anchor priority is the PER-MESSAGE stable id: since the serve-path
         * IrohAssistantAccumulator retags assistant deltas with a stable
         * `cm-stream-<uuid>` id per logical assistant message, that id is both
         * stable across a message's fragments AND distinct between separate
         * assistant messages in one turn. That distinction matters for
         * tool-mediated turns: the pre-tool preamble and the post-tool final
         * response are DIFFERENT messages, and giving them one shared per-turn
         * otid made the reducer's findByOtid merge fold the final response into
         * the earlier preamble row — mutating an old row instead of appending,
         * so the final text only became visible after the next reconcile
         * (the "populates after I respond" bug).
         *
         * The per-turn [turnId] fallback remains ONLY for legacy frames with no
         * stable message id at all (pre-accumulator servers), where grouping the
         * whole turn is still safer than a per-fragment split
         * (letta-mobile-x1xnl).
         */
        fun assistantStreamOtid(): String {
            // Only trust ids the serve-path accumulator stamped: raw backend
            // ids (`letta-msg-*`) ROTATE per fragment and would re-split one
            // message into per-fragment rows (the original x1xnl bug).
            val stableMessageId = messageId?.takeIf { it.startsWith("cm-stream-") }
            return if (stableMessageId != null) {
                "iroh-assistant-$stableMessageId"
            } else {
                "iroh-assistant-$turnId"
            }
        }
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

internal data class CanonicalToolCall(
    val toolCallId: String,
    val name: String,
    val arguments: String,
)

internal fun canonicalToolCall(delta: JsonObject): CanonicalToolCall? {
    val call = delta["tool_call"] as? JsonObject ?: delta
    val function = call["function"] as? JsonObject
    val id = call.stringValue("tool_call_id")
        ?: call.stringValue("id")
        ?: delta.stringValue("tool_call_id")
        ?: return null
    val name = call.stringValue("name")
        ?: call.stringValue("tool_name")
        ?: function?.stringValue("name")
        ?: delta.stringValue("name")
        ?: delta.stringValue("tool_name")
        ?: "tool"
    val arguments = call["arguments"]
        ?: call["input"]
        ?: function?.get("arguments")
        ?: function?.get("input")
        ?: delta["arguments"]
        ?: delta["input"]
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
