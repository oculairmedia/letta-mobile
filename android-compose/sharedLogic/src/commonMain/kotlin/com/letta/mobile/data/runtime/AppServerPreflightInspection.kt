package com.letta.mobile.data.runtime

import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * letta-mobile-lgns8.21.7: byte bounds for the turn context preflight.
 *
 * `conversation_messages_list` is count-bounded (50 rows) but NOT byte-bounded:
 * one Letta row can carry a multi-megabyte base64 image or tool return. The old
 * preflight probe walked every row with an unbounded recursive DFS and touched
 * whole payloads to answer four tiny questions (id, role, empty content,
 * stop_reason/usage), so a single poisoned row could pin the CPU or blow the
 * stack before the turn even started.
 *
 * These bounds mirror [com.letta.mobile.data.controller.node.iroh.MessageListPageGuard]:
 * the same 900 KiB page budget (which itself sits under the 1 MiB Iroh frame
 * cap), applied here as an *inspection* budget rather than a transmission one.
 * Nothing below ever materializes a value it has not first sized in O(1).
 */
object AppServerPreflightBounds {
    /**
     * Cumulative bytes the preflight may inspect across the whole newest-message
     * page. Same number as `MessageListPageGuard.MAX_PAGE_BYTES` — a page that is
     * legal to transmit is legal to inspect end to end.
     */
    const val MAX_INSPECTED_BYTES: Int = 900 * 1024

    /**
     * Per-message share of the budget. One pathological row must not consume the
     * whole page budget and blind the preflight to the rows behind it.
     */
    const val MAX_MESSAGE_INSPECTED_BYTES: Int = 64 * 1024

    /** Recursion depth cap for the stop_reason DFS (real envelopes nest < 6). */
    const val MAX_INSPECTION_DEPTH: Int = 12

    /** Node cap for the stop_reason DFS, per message. */
    const val MAX_NODES_PER_MESSAGE: Int = 2_000

    /**
     * Longest string prefix the inspector will account for. Every field the
     * preflight actually reads (id, role, message_type, stop_reason) is short;
     * anything longer is an attachment/tool payload and is sized, never scanned.
     */
    const val MAX_SCANNED_STRING_CHARS: Int = 4_096

    const val TELEMETRY_TAG: String = "AppServerPreflight"
}

/**
 * Single-pass envelope probe for preflight checks. [bounded] is true when this
 * message's inspection hit one of the bounds, i.e. the probe is a *partial*
 * answer and the caller must degrade rather than trust a negative result.
 */
internal data class PreflightMessageProbe(
    val id: String?,
    val role: String?,
    val hasEmptyAssistant: Boolean,
    val hasLengthStop: Boolean,
    /** Null when the message carries no input token count. */
    val inputTokens: Long?,
    val bounded: Boolean,
)

/** Aggregate accounting for one preflight inspection pass. */
internal data class PreflightInspectionSummary(
    val messagesInspected: Int,
    val messagesSkipped: Int,
    val bytesVisited: Long,
    val boundedMessages: Int,
    val oversizedFieldHits: Int,
    val depthBoundHits: Int,
    val nodeBoundHits: Int,
    val messageByteBoundHits: Int,
    val budgetExhausted: Boolean,
) {
    val anyBoundHit: Boolean
        get() = boundedMessages > 0 || oversizedFieldHits > 0 || depthBoundHits > 0 ||
            nodeBoundHits > 0 || messageByteBoundHits > 0 || messagesSkipped > 0 || budgetExhausted
}

/**
 * Byte-bounded, depth-bounded message inspector.
 *
 * Cost accounting rule: a byte is "visited" only when the inspector actually
 * looks at it. String lengths are read in O(1); anything longer than
 * [maxStringChars] is counted at the cap and never scanned, so a 95 MB base64
 * content field costs the inspector 4 KiB of budget and zero copies.
 */
internal class BoundedMessageInspector(
    private val maxTotalBytes: Int = AppServerPreflightBounds.MAX_INSPECTED_BYTES,
    private val maxMessageBytes: Int = AppServerPreflightBounds.MAX_MESSAGE_INSPECTED_BYTES,
    private val maxDepth: Int = AppServerPreflightBounds.MAX_INSPECTION_DEPTH,
    private val maxNodes: Int = AppServerPreflightBounds.MAX_NODES_PER_MESSAGE,
    private val maxStringChars: Int = AppServerPreflightBounds.MAX_SCANNED_STRING_CHARS,
) {
    init {
        require(maxTotalBytes > 0 && maxMessageBytes > 0)
        require(maxDepth > 0 && maxNodes > 0 && maxStringChars > 0)
    }

    var bytesVisited: Long = 0L
        private set
    var messagesInspected: Int = 0
        private set

    /** Rows never looked at because the page budget ran out first. */
    var messagesSkipped: Int = 0
        private set
    private var boundedMessages = 0
    private var oversizedFieldHits = 0
    private var depthBoundHits = 0
    private var nodeBoundHits = 0
    private var messageByteBoundHits = 0

    private var messageBytes = 0
    private var messageNodes = 0
    private var messageBounded = false

    /** True once the cumulative page budget is spent — stop inspecting. */
    val budgetExhausted: Boolean
        get() = bytesVisited >= maxTotalBytes

    fun skipRemaining(count: Int) {
        if (count > 0) messagesSkipped += count
    }

    fun summary(): PreflightInspectionSummary = PreflightInspectionSummary(
        messagesInspected = messagesInspected,
        messagesSkipped = messagesSkipped,
        bytesVisited = bytesVisited,
        boundedMessages = boundedMessages,
        oversizedFieldHits = oversizedFieldHits,
        depthBoundHits = depthBoundHits,
        nodeBoundHits = nodeBoundHits,
        messageByteBoundHits = messageByteBoundHits,
        budgetExhausted = budgetExhausted,
    )

    fun inspect(element: JsonElement): PreflightMessageProbe {
        messagesInspected++
        messageBytes = 0
        messageNodes = 0
        messageBounded = false

        val root = element as? JsonObject
        val id = root?.boundedString("id")
        // App Server conversation rows often use message_type=assistant_message
        // without a role field — treat both shapes as assistant.
        val messageType = root?.boundedString("message_type") ?: root?.boundedString("messageType")
        val role = root?.boundedString("role")
            ?: when (messageType) {
                "assistant_message" -> "assistant"
                "user_message" -> "user"
                "system_message" -> "system"
                else -> null
            }
        val content = root?.get("content") ?: root?.get("parts")
        val hasEmptyAssistant = role == "assistant" && isEmptyMessageContent(content)
        val inputTokens = readInputTokens(root)
        val hasLengthStop = hasProviderLengthStop(element, depth = 0)
        if (messageBounded) boundedMessages++
        return PreflightMessageProbe(
            id = id,
            role = role,
            hasEmptyAssistant = hasEmptyAssistant,
            hasLengthStop = hasLengthStop,
            inputTokens = inputTokens,
            bounded = messageBounded,
        )
    }

    // ---- accounting -------------------------------------------------------

    private fun charge(chars: Int) {
        bytesVisited += chars
        messageBytes += chars
    }

    /** Accounts for a string without scanning past [maxStringChars]. */
    private fun chargeText(text: String?): String? {
        if (text == null) return null
        if (text.length > maxStringChars) {
            oversizedFieldHits++
            messageBounded = true
            charge(maxStringChars)
        } else {
            charge(text.length)
        }
        return text
    }

    private fun messageBudgetSpent(): Boolean {
        if (messageBytes >= maxMessageBytes || budgetExhausted) {
            if (!messageBounded) {
                messageByteBoundHits++
                messageBounded = true
            }
            return true
        }
        return false
    }

    // ---- bounded field reads ---------------------------------------------

    private fun JsonObject.boundedString(key: String): String? {
        charge(key.length)
        val primitive = this[key] as? JsonPrimitive ?: return null
        return chargeText(primitive.contentOrNull)
    }

    private fun JsonObject.longValue(key: String): Long? {
        charge(key.length)
        return (this[key] as? JsonPrimitive)?.longOrNull
    }

    /**
     * True for missing / null / [] / blank-string content payloads.
     *
     * A string longer than [maxStringChars] is by definition not empty content,
     * so it is classified by length alone — `isBlank()` on a 95 MB base64 field
     * is exactly the unbounded scan this bead exists to remove.
     */
    private fun isEmptyMessageContent(element: JsonElement?): Boolean = when (element) {
        null, JsonNull -> true
        is JsonArray -> element.isEmpty()
        is JsonPrimitive -> {
            val text = element.contentOrNull
            when {
                text == null -> true
                text.length > maxStringChars -> {
                    oversizedFieldHits++
                    messageBounded = true
                    charge(maxStringChars)
                    false
                }
                else -> {
                    charge(text.length)
                    text.isBlank()
                }
            }
        }
        else -> false
    }

    private fun readInputTokens(root: JsonObject?): Long? {
        if (root == null) return null
        val providerResult = root.objectValue("provider_result")
        val usage = root.objectValue("usage")
            ?: providerResult?.objectValue("usage")
            ?: root
        val input = usage.longValue("input")
            ?: usage.longValue("input_tokens")
            ?: usage.longValue("inputTokens")
            ?: return null
        val cacheRead = usage.longValue("cache_read")
            ?: usage.longValue("cacheRead")
            ?: 0L
        return input + cacheRead
    }

    private fun JsonObject.objectValue(key: String): JsonObject? {
        charge(key.length)
        return this[key] as? JsonObject
    }

    // ---- bounded DFS ------------------------------------------------------

    private fun hasProviderLengthStop(element: JsonElement, depth: Int): Boolean {
        if (messageBudgetSpent()) return false
        if (depth > maxDepth) {
            depthBoundHits++
            messageBounded = true
            return false
        }
        if (++messageNodes > maxNodes) {
            nodeBoundHits++
            messageBounded = true
            return false
        }
        when (element) {
            is JsonObject -> {
                val stop = element.boundedString("stop_reason") ?: element.boundedString("stopReason")
                if (stop == "length") return true
                for ((key, value) in element) {
                    charge(key.length)
                    if (shouldWalkForLengthStop(key, value) && hasProviderLengthStop(value, depth + 1)) {
                        return true
                    }
                    if (messageBudgetSpent()) return false
                }
            }
            is JsonArray -> {
                for (item in element) {
                    if (hasProviderLengthStop(item, depth + 1)) return true
                    if (messageBudgetSpent()) return false
                }
            }
            else -> Unit
        }
        return false
    }

    private fun shouldWalkForLengthStop(key: String, value: JsonElement): Boolean {
        if (value !is JsonObject && value !is JsonArray) return false
        if (key == "message" || key == "stop" || key == "metadata" ||
            key == "provider" || key == "provider_result" || key == "response"
        ) {
            return true
        }
        val obj = value as? JsonObject ?: return false
        return "stop_reason" in obj || "stopReason" in obj || "role" in obj || "usage" in obj
    }
}

/**
 * Bound hits are aggregated per pass rather than emitted per hit: a poisoned row
 * can trip thousands of node/oversize bounds and the telemetry pipe must not
 * become the new unbounded cost. The pass NEVER caps silently — one event is
 * emitted whenever any bound was reached.
 */
internal fun PreflightInspectionSummary.emitTelemetry(conversationId: String, decisionDegraded: Boolean) {
    if (!anyBoundHit) return
    Telemetry.event(
        AppServerPreflightBounds.TELEMETRY_TAG,
        "context_preflight.inspection_bounded",
        "conversationId" to conversationId,
        "messagesInspected" to messagesInspected,
        "messagesSkipped" to messagesSkipped,
        "bytesVisited" to bytesVisited,
        "boundedMessages" to boundedMessages,
        "oversizedFieldHits" to oversizedFieldHits,
        "depthBoundHits" to depthBoundHits,
        "nodeBoundHits" to nodeBoundHits,
        "messageByteBoundHits" to messageByteBoundHits,
        "budgetExhausted" to budgetExhausted,
        "maxInspectedBytes" to AppServerPreflightBounds.MAX_INSPECTED_BYTES,
        "decisionDegraded" to decisionDegraded,
        level = if (decisionDegraded) Telemetry.Level.WARN else Telemetry.Level.INFO,
    )
}
