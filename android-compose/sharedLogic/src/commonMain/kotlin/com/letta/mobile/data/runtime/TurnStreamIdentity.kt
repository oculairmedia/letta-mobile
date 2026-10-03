package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.iroh.canonicalToolCalls
import com.letta.mobile.data.transport.iroh.canonicalToolReturn
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Whether a text frame's chunk is a provider increment or an already-complete snapshot. */
internal enum class StreamTextFrameSource {
    AppServerDelta,
    CumulativeSnapshot,
}

/**
 * The one place a chat message's identity is minted (letta-mobile-jdcoj, plan
 * `chat-timeline-identity-plan.md` 1.1-1.3). One instance lives per turn inside [TurnDraftProcessor];
 * every `RemoteStreamFrame` / `ExternalTransportFrame` body passes through [stamp] before anything
 * downstream (the Iroh fanout, a direct-route timeline) sees it.
 *
 * Wire shape: a row-bearing `stream_delta.delta` gains
 * `"logical_message_id": String`, `"turn_id": String`, and, on assistant / reasoning /
 * hidden_reasoning text frames only, `"text_seq": Int` (1 for the first emitted text frame of a
 * logical id, +1 per emitted frame). Those text frames carry the FULL cumulative text in `content`
 * (assistant) or `reasoning` (reasoning kinds). `delta.id` is never rewritten. `stop_reason`,
 * `usage_statistics`, errors and heartbeats are forwarded unstamped.
 *
 * Identity per kind: user `client_message_id` (also the turn id); text kinds `lm-<uuid>` per message
 * boundary ([TextMessageBoundaries]); tool call `tc-<first tool_call_id>`; tool return
 * `tr-<tool_call_id>`; other row-bearing deltas `lm-<uuid>` per frame.
 */
internal class TurnStreamIdentity(
    private val turnId: String,
    mintId: () -> String,
) {
    private val mint = { LogicalMessageId(mintId()) }
    private val boundaries = TextMessageBoundaries(mint)
    private val texts = CumulativeTexts()
    private val seenTextFrameKeys = HashSet<String>()

    /** Stamps a RemoteStreamFrame/ExternalTransportFrame body; returns the body to forward, or null to drop. */
    fun stamp(body: String, source: StreamTextFrameSource): String? {
        val frame = parseDeltaFrame(body) ?: return body
        return when (frame.type) {
            in TEXT_TYPES -> stampText(frame, source)
            in UNSTAMPED_TYPES, null -> body
            else -> stampRow(frame)
        }
    }

    private fun stampText(frame: DeltaFrame, source: StreamTextFrameSource): String? {
        val frameKey = frame.idempotencyKey
        if (frameKey != null && !seenTextFrameKeys.add(frameKey)) return null
        val logicalId = boundaries.textMessageId(frame.boundaryKey())
        val text = frame.textChunk()?.let { texts.next(logicalId, it, source) }
        return frame.restamped(FrameIdentity(logicalId, turnId), text)
    }

    private fun stampRow(frame: DeltaFrame): String {
        val type = frame.type.orEmpty()
        boundaries.noteNonTextFrame(type)
        return frame.restamped(rowIdentity(type, frame.delta), text = null)
    }

    private fun rowIdentity(type: String, delta: JsonObject): FrameIdentity = when (type) {
        "user_message" -> (delta.stampString("otid") ?: turnId).let { FrameIdentity(LogicalMessageId(it), it) }
        "tool_call_message" -> FrameIdentity(toolCallRowId(delta), turnId)
        "tool_return_message" -> FrameIdentity(toolReturnRowId(delta), turnId)
        else -> FrameIdentity(mint(), turnId)
    }

    private fun toolCallRowId(delta: JsonObject): LogicalMessageId =
        canonicalToolCalls(delta).firstOrNull()?.toolCallId?.takeIf { it.isNotBlank() }
            ?.let { LogicalMessageId("tc-$it") } ?: mint()

    private fun toolReturnRowId(delta: JsonObject): LogicalMessageId =
        canonicalToolReturn(delta).toolCallId.takeIf { it.isNotBlank() }
            ?.let { LogicalMessageId("tr-$it") } ?: mint()

    private companion object {
        val TEXT_TYPES = setOf("assistant_message", "reasoning_message", "hidden_reasoning_message")
        val UNSTAMPED_TYPES = setOf(
            "stop_reason",
            "usage_statistics",
            "ping",
            "heartbeat",
            "error_message",
            "loop_error",
        )
    }
}

/** Stamps the body of a stream-frame draft; null when the frame is a replay to drop. */
internal fun TurnStreamIdentity.stampDraft(draft: RuntimeEventDraft): RuntimeEventDraft? =
    stampPayload(draft.payload)?.let { draft.copy(payload = it) }

/** Stamps a stream-frame payload's body; other payloads pass through, null when the frame is a replay to drop. */
internal fun TurnStreamIdentity.stampPayload(payload: RuntimeEventPayload): RuntimeEventPayload? =
    when (payload) {
        is RuntimeEventPayload.RemoteStreamFrame ->
            stamp(payload.body, StreamTextFrameSource.AppServerDelta)?.let { payload.copy(body = it) }
        is RuntimeEventPayload.ExternalTransportFrame ->
            stamp(payload.body, StreamTextFrameSource.CumulativeSnapshot)?.let { payload.copy(body = it) }
        else -> payload
    }

/** A turn's identity: its id is the prompt's client message id when it has one, else `turn-<uuid>`. */
@OptIn(ExperimentalUuidApi::class)
internal fun turnStreamIdentityFor(clientMessageId: String?): TurnStreamIdentity =
    TurnStreamIdentity(
        turnId = clientMessageId ?: "turn-${Uuid.random()}",
        mintId = { "lm-${Uuid.random()}" },
    )

@JvmInline
private value class LogicalMessageId(val value: String)

private data class FrameIdentity(val logicalId: LogicalMessageId, val turnId: String)

/** What decides which logical message a text frame belongs to: its type, then `message_id`, then `otid`. */
private data class TextFrameKey(val type: String, val messageId: String?, val otid: String?)

private data class StampedText(val text: String, val seq: Int)

/**
 * Where one assistant / reasoning message ends and the next begins within a turn, in precedence
 * order: the upstream `message_id`, else its `otid`, else "a frame of a different message_type was
 * seen since the last text frame of this type". `run_id` and the rotating delta `id` never count.
 */
private class TextMessageBoundaries(private val mintId: () -> LogicalMessageId) {
    private val explicit = HashMap<String, LogicalMessageId>()
    private val currentByType = HashMap<String, LogicalMessageId>()
    private var lastStampedType: String? = null

    fun textMessageId(key: TextFrameKey): LogicalMessageId {
        val explicitKey = listOfNotNull(key.messageId?.let { "m:$it" }, key.otid?.let { "o:$it" }).firstOrNull()
        val id = explicitKey?.let { explicit.getOrPut("${key.type}|$it", mintId) } ?: segmentedId(key.type)
        lastStampedType = key.type
        return id
    }

    fun noteNonTextFrame(type: String) {
        lastStampedType = type
    }

    private fun segmentedId(type: String): LogicalMessageId {
        val continued = lastStampedType == type
        return currentByType[type].takeIf { continued } ?: mintId().also { currentByType[type] = it }
    }
}

private class CumulativeTexts {
    private val textById = HashMap<LogicalMessageId, String>()
    private val seqById = HashMap<LogicalMessageId, Int>()

    fun next(logicalId: LogicalMessageId, chunk: String, source: StreamTextFrameSource): StampedText {
        val text = when (source) {
            StreamTextFrameSource.AppServerDelta -> textById[logicalId].orEmpty() + chunk
            StreamTextFrameSource.CumulativeSnapshot -> chunk
        }
        val seq = (seqById[logicalId] ?: 0) + 1
        textById[logicalId] = text
        seqById[logicalId] = seq
        return StampedText(text, seq)
    }
}

private class DeltaFrame(val envelope: JsonObject, val delta: JsonObject) {
    val type: String? = delta.stampString("message_type")
    val idempotencyKey: String? = envelope.stampString("idempotency_key")

    private val textField: String get() = if (type == "assistant_message") "content" else "reasoning"

    fun boundaryKey() = TextFrameKey(type.orEmpty(), delta.stampString("message_id"), delta.stampString("otid"))

    fun textChunk(): String? =
        textFrom(delta[textField]) ?: textFrom(delta["content"]) ?: textFrom(delta["text"])

    fun restamped(identity: FrameIdentity, text: StampedText?): String {
        val rewritten = buildJsonObject {
            delta.forEach { (key, value) -> if (key !in rewrittenKeys(text)) put(key, value) }
            text?.let {
                put(textField, it.text)
                put("text_seq", it.seq)
            }
            put("logical_message_id", identity.logicalId.value)
            put("turn_id", identity.turnId)
        }
        return buildJsonObject {
            envelope.forEach { (key, value) -> if (key != "delta") put(key, value) }
            put("delta", rewritten)
        }.toString()
    }

    private fun rewrittenKeys(text: StampedText?): Set<String> =
        setOf("logical_message_id", "turn_id", "text_seq") + listOfNotNull(textField.takeIf { text != null })
}

private fun parseDeltaFrame(body: String): DeltaFrame? = runCatching {
    val envelope = AppServerProtocol.json.parseToJsonElement(body).jsonObject
    val delta = envelope["delta"] as? JsonObject
    delta.takeIf { envelope.stampString("type") == "stream_delta" }?.let { DeltaFrame(envelope, it) }
}.getOrNull()

private fun JsonObject.stampString(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun textFrom(element: JsonElement?): String? = when (element) {
    null -> null
    is JsonPrimitive -> element.contentOrNull
    is JsonArray -> element.joinToString("") { textPartFrom(it) }.takeIf { it.isNotEmpty() }
    else -> null
}

private fun textPartFrom(part: JsonElement): String = runCatching {
    val obj = part.jsonObject
    if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty() else ""
}.getOrDefault("")
