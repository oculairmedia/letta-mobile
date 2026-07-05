package com.letta.mobile.data.controller.node.iroh

import kotlinx.atomicfu.atomic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Server-process-scoped dedupe for client message ids (P3, letta-mobile-3wq5g).
 *
 * Moving this off a per-[IrohNodeConnection] set means a redial that re-delivers
 * the SAME `client_message_id` on a fresh connection is still recognised as a
 * duplicate and never double-runs the turn. Bounded LRU (insertion-order
 * eviction) keeps memory flat; the trade-off is that a re-delivery older than
 * [maxIds] entries can slip through, which is acceptable for the reconnect
 * window this guards.
 *
 * Thread-safe: several connections in one process may mark ids concurrently.
 */
class ProcessScopedClientMessageDedupe(
    private val maxIds: Int = DEFAULT_MAX_IDS,
    private val trimCount: Int = DEFAULT_TRIM_COUNT,
) {
    private val lock = Any()
    private val ids = LinkedHashSet<String>()

    /** Returns true when [id] is seen for the first time, false when duplicate. */
    fun markHandled(id: String): Boolean = synchronized(lock) {
        if (!ids.add(id)) return false
        if (ids.size > maxIds) {
            val iterator = ids.iterator()
            var removed = 0
            while (removed < trimCount && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
                removed++
            }
        }
        true
    }

    fun contains(id: String): Boolean = synchronized(lock) { ids.contains(id) }

    fun size(): Int = synchronized(lock) { ids.size }

    companion object {
        const val DEFAULT_MAX_IDS = 512
        const val DEFAULT_TRIM_COUNT = 128
    }
}

/**
 * Server-process-scoped store of terminal frames that could NOT be delivered
 * because the control/stream channel died mid-turn (P3, q71yi root cause).
 *
 * Keyed by `client_message_id` — the one id the client re-presents when it
 * redials and re-sends the same turn. On that re-send the server replays the
 * parked terminal instead of silently dropping the duplicate, so the client's
 * "Thinking…" turn actually resolves. Bounded so a flood of dropped turns can't
 * grow memory without bound.
 */
class ParkedTerminalStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val lock = Any()
    private val parked = LinkedHashMap<String, String>()

    /** Park (or overwrite) the terminal delta JSON for [key]. */
    fun park(key: String, terminalDeltaJson: String) = synchronized(lock) {
        parked.remove(key)
        parked[key] = terminalDeltaJson
        while (parked.size > maxEntries) {
            val oldest = parked.keys.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            } else {
                break
            }
        }
    }

    /** Remove and return the parked terminal delta JSON for [key], or null. */
    fun takeParked(key: String): String? = synchronized(lock) { parked.remove(key) }

    fun contains(key: String): Boolean = synchronized(lock) { parked.containsKey(key) }

    fun size(): Int = synchronized(lock) { parked.size }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 256
    }
}

/**
 * Per-connection, atomically-incrementing `event_seq` source with a
 * process-scoped disjoint base (P3, fixes the shared companion-var race).
 *
 * Each connection reserves its own [ConnectionEventSeq] whose values increase
 * strictly monotonically (the P1 stream invariant), and different connections
 * draw from disjoint ranges so their seqs never collide within one process.
 */
internal class ConnectionEventSeq(base: Long) {
    private val counter = atomic(base)

    /** Strictly-monotonic next value for this connection. */
    fun next(): Long = counter.getAndIncrement()

    /** The next value that [next] would return, without consuming it. */
    val peek: Long get() = counter.value
}

internal object IrohEventSeqAllocator {
    private val nextBase = atomic(1L)

    /** Range width reserved per connection before the next base is handed out. */
    const val STRIDE = 1_000_000L

    fun newConnectionSeq(): ConnectionEventSeq = ConnectionEventSeq(nextBase.getAndAdd(STRIDE))
}

/**
 * Tracks tool_call ids opened by a turn's stream frames and closed by their
 * matching tool_return frames, so on failure/abort the server can synthesize a
 * terminal `tool_return(status=error, "cancelled")` for anything still open
 * (P3, letta-mobile-8s45p) — no tool_call is left dangling without a return.
 */
internal class OpenToolCallTracker {
    private val open = LinkedHashSet<String>()

    /** Observe a raw stream frame, updating the open-tool_call set. */
    fun observe(rawFrame: String) {
        val delta = DanglingToolCallSynthesizer.deltaOf(rawFrame) ?: return
        when (DanglingToolCallSynthesizer.messageType(delta)) {
            "tool_call_message",
            "approval_request_message" -> open += DanglingToolCallSynthesizer.toolCallIds(delta)
            "tool_return_message" -> open -= DanglingToolCallSynthesizer.toolReturnCallIds(delta)
            else -> Unit
        }
    }

    fun openIds(): List<String> = open.toList()

    fun isEmpty(): Boolean = open.isEmpty()

    fun close(toolCallId: String) {
        open.remove(toolCallId)
    }
}

/**
 * Pure helpers for the dangling-tool_call fix. Extracted so the frame parsing
 * and synthetic-return construction are unit-testable without a live QUIC
 * connection.
 */
internal object DanglingToolCallSynthesizer {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The inner `delta` object of a stream_delta frame, or the frame itself. */
    fun deltaOf(rawFrame: String): JsonObject? = runCatching {
        val obj = json.parseToJsonElement(rawFrame).jsonObject
        obj["delta"]?.jsonObject ?: obj
    }.getOrNull()

    fun messageType(delta: JsonObject): String? =
        delta["message_type"]?.jsonPrimitive?.contentOrNull

    /** tool_call ids opened by a tool_call/approval_request delta. */
    fun toolCallIds(delta: JsonObject): List<String> {
        val explicit = (delta["tool_calls"] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonObject)?.toolCallId()
        }.orEmpty()
        if (explicit.isNotEmpty()) return explicit
        (delta["tool_call"] as? JsonObject)?.toolCallId()?.let { return listOf(it) }
        return listOfNotNull(
            delta["tool_call_id"]?.jsonPrimitive?.contentOrNull
                ?: delta["id"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /** tool_call ids closed by a tool_return delta. */
    fun toolReturnCallIds(delta: JsonObject): List<String> {
        val direct = delta["tool_call_id"]?.jsonPrimitive?.contentOrNull
        val nested = (delta["tool_return"] as? JsonObject)
            ?.get("tool_call_id")?.jsonPrimitive?.contentOrNull
        return listOfNotNull(direct ?: nested)
    }

    private fun JsonObject.toolCallId(): String? =
        this["tool_call_id"]?.jsonPrimitive?.contentOrNull
            ?: this["id"]?.jsonPrimitive?.contentOrNull
            ?: (this["function"] as? JsonObject)?.get("tool_call_id")?.jsonPrimitive?.contentOrNull

    /**
     * Delta for a synthetic terminal tool_return closing an open tool_call after
     * a turn failure/abort. Status is `error` with a `cancelled` body so the
     * client renders the call as ended, not perpetually pending.
     */
    fun cancelledToolReturnDelta(toolCallId: String): JsonObject = buildJsonObject {
        put("message_type", "tool_return_message")
        put("tool_call_id", toolCallId)
        put("status", "error")
        put("tool_return", "cancelled")
    }
}

/**
 * letta-mobile-h30cy: mirror the shim's tagAsOptimistic / cm-stream tagging on the
 * Iroh serve path. The Iroh stream forwards the App Server delta with its raw
 * provider id (letta-msg-*), but message.list later returns the SAME reply with a
 * DIFFERENT id namespace (ui-msg-*, null run) that shares NO identity field with
 * the stream — so mobile cannot dedupe the streamed row against the disk-fetched
 * copy and renders it twice (Iroh dupes; HTTPS does not, because the WS/HTTP shim
 * paths already apply this tag). Rewrite an assistant/reasoning stream_delta's id
 * to a stable `cm-stream-<otid>` / `cm-reason-<otid>`, which mobile's
 * optimistic-twin dedup collapses against the disk copy. tool_call/tool_return
 * keep their stable ids; frames without an otid are left unchanged; idempotent.
 */
internal fun tagStreamDeltaForOptimisticDedup(
    delta: kotlinx.serialization.json.JsonObject,
): kotlinx.serialization.json.JsonObject {
    val messageType = delta["message_type"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
        ?: return delta
    val prefix = when (messageType) {
        "assistant_message" -> "cm-stream-"
        "reasoning_message", "hidden_reasoning_message" -> "cm-reason-"
        else -> return delta
    }
    val otid = delta["otid"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
    if (otid.isNullOrEmpty()) return delta
    val currentId = delta["id"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
    if (currentId != null && (currentId.startsWith("cm-stream-") || currentId.startsWith("cm-reason-"))) {
        return delta
    }
    return kotlinx.serialization.json.buildJsonObject {
        delta.forEach { (k, v) -> if (k != "id") put(k, v) }
        put("id", "$prefix$otid")
    }
}

/** Frame-level wrapper: parse a raw wire frame; if stream_delta, tag its inner delta id. */
internal fun retagStreamDeltaFrameForOptimisticDedup(rawFrame: String): String = runCatching {
    val obj = kotlinx.serialization.json.Json.parseToJsonElement(rawFrame).jsonObject
    if ((obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull != "stream_delta") return@runCatching rawFrame
    val delta = obj["delta"] as? kotlinx.serialization.json.JsonObject ?: return@runCatching rawFrame
    val taggedDelta = tagStreamDeltaForOptimisticDedup(delta)
    if (taggedDelta === delta) return@runCatching rawFrame
    kotlinx.serialization.json.buildJsonObject {
        obj.forEach { (k, v) -> if (k != "delta") put(k, v) }
        put("delta", taggedDelta)
    }.toString()
}.getOrDefault(rawFrame)
