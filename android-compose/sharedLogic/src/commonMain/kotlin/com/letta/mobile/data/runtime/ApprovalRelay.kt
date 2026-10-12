package com.letta.mobile.data.runtime

import com.letta.mobile.runtime.ApprovalDiffPreview
import com.letta.mobile.runtime.PermissionSuggestion
import com.letta.mobile.runtime.ToolCallId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * letta-mobile-bzvro.40: carries what each parked `can_use_tool` request offered (always-allow rules,
 * blocked path, diffs) from an Iroh host to its clients.
 *
 * The host answers its App Server's control requests itself, so a client over `iroh://` never sees
 * them. The host therefore pushes, to the viewers of a conversation, a `pending_approvals` frame with
 * the FULL list of requests parked for that conversation each time it changes (and once to a viewer
 * that starts watching it). A full snapshot replaces the previous one, so a lost frame heals on the
 * next and an empty list clears every card.
 *
 * Each entry keeps the control request's own field names, so the client reads it with the same
 * fail-soft, bounded [ApprovalRequestPayloadParser] a direct connection uses: the host is trusted no
 * more than an App Server is.
 */
object ApprovalRelay {
    /** Advertised by a host that pushes `pending_approvals` and honours always-allow rule ids. */
    const val CAPABILITY: String = "approval_relay_v1"

    const val FRAME_TYPE: String = "pending_approvals"

    /** Requests one conversation can hold at once; matches the engine's own bound. */
    const val MAX_ENTRIES: Int = 200

    const val AGENT_ID = "agent_id"
    const val CONVERSATION_ID = "conversation_id"
    const val APPROVALS = "approvals"
    private const val APPROVAL_ID = "approval_id"
    private const val TOOL_CALL_ID = "tool_call_id"
    private const val TOOL_NAME = "tool_name"

    /** The `approvals` array for [details]. */
    fun encodeApprovals(details: List<PendingApprovalDetails>): JsonArray = buildJsonArray {
        details.take(MAX_ENTRIES).forEach { add(encodeEntry(it)) }
    }

    /** One complete frame for [snapshot] (stream channel, no envelope beyond `type`). */
    fun encodeFrame(snapshot: RelayedPendingApprovals, ts: String): JsonObject =
        buildJsonObject {
            put("v", 1)
            put("type", FRAME_TYPE)
            put("id", "pending-approvals-${snapshot.conversationId}-$ts")
            put("ts", ts)
            put(AGENT_ID, snapshot.agentId)
            put(CONVERSATION_ID, snapshot.conversationId)
            put(APPROVALS, encodeApprovals(snapshot.approvals))
        }

    private fun encodeEntry(details: PendingApprovalDetails): JsonObject = buildJsonObject {
        put(APPROVAL_ID, details.approvalId)
        put(TOOL_CALL_ID, details.toolCallId)
        put(TOOL_NAME, details.toolName)
        put("permission_suggestions", buildJsonArray { details.suggestions.forEach { add(it.toJson()) } })
        details.blockedPath?.let { put("blocked_path", it) }
        put("diffs", buildJsonArray { details.diffs.forEach { add(it.toJson()) } })
    }

    private fun PermissionSuggestion.toJson() = buildJsonObject {
        put("id", id)
        put("text", text)
    }

    private fun ApprovalDiffPreview.toJson() = buildJsonObject {
        path?.let { put("path", it) }
        unifiedDiff?.let { put("unified_diff", it) }
        note?.let { put("reason", it) }
    }

    /** A received frame (or its fields), or null when it names no conversation. Malformed entries are skipped. */
    fun decode(frame: JsonObject): RelayedPendingApprovals? {
        // An emptied conversation may be replayed without an agent: the conversation alone keys the snapshot.
        val conversationId = frame.text(CONVERSATION_ID) ?: return null
        return RelayedPendingApprovals(frame.text(AGENT_ID).orEmpty(), conversationId, decodeApprovals(frame[APPROVALS]))
    }

    /** The `approvals` array, bounded; malformed entries are skipped. */
    fun decodeApprovals(approvals: JsonElement?): List<PendingApprovalDetails> =
        (approvals as? JsonArray).orEmpty().asSequence()
            .mapNotNull { (it as? JsonObject)?.decodeEntry() }
            .distinctBy { it.toolCallId }
            .take(MAX_ENTRIES)
            .toList()

    private fun JsonObject.decodeEntry(): PendingApprovalDetails? {
        val approvalId = text(APPROVAL_ID)?.take(ApprovalRequestPayloadParser.MAX_ID_CHARS) ?: return null
        val toolCallId = text(TOOL_CALL_ID)?.take(ApprovalRequestPayloadParser.MAX_ID_CHARS) ?: return null
        return PendingApprovalDetails(
            approvalId = approvalId,
            toolCallId = toolCallId,
            toolName = (text(TOOL_NAME) ?: "tool").take(ApprovalRequestPayloadParser.MAX_TOOL_NAME_CHARS),
            suggestions = ApprovalRequestPayloadParser.suggestions(this),
            blockedPath = ApprovalRequestPayloadParser.blockedPath(this),
            diffs = ApprovalRequestPayloadParser.diffs(this),
        )
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
}

/** One conversation's parked requests, as a host reported them. */
data class RelayedPendingApprovals(
    val agentId: String,
    val conversationId: String,
    val approvals: List<PendingApprovalDetails>,
)

/**
 * letta-mobile-bzvro.40: the client's view of what its Iroh host has parked, by tool call id, built from
 * `pending_approvals` snapshots. Joined onto the timeline with
 * [com.letta.mobile.data.chat.projection.withPendingApprovalDetails], exactly as a direct connection's
 * own engine details are. [clear] on disconnect: nothing the host said survives the connection, and
 * the host replays each viewed conversation when the client watches it again.
 */
class RelayedPendingApprovalStore {
    private val byConversation = MutableStateFlow<Map<String, List<PendingApprovalDetails>>>(emptyMap())
    private val byToolCall = MutableStateFlow<Map<String, PendingApprovalDetails>>(emptyMap())

    /** Every reported request by tool call id (tool call ids are unique across conversations). */
    val pending: StateFlow<Map<String, PendingApprovalDetails>> = byToolCall.asStateFlow()

    fun apply(update: RelayedPendingApprovals) {
        byConversation.update { current ->
            if (update.approvals.isEmpty()) current - update.conversationId else current + (update.conversationId to update.approvals)
        }
        publish()
    }

    /** Drops one request at once (its answer was accepted); the host's next snapshot confirms it. */
    fun resolve(call: ToolCallId) {
        byConversation.update { current ->
            current.mapValues { (_, list) -> list.filterNot { it.toolCallId == call.value } }.filterValues { it.isNotEmpty() }
        }
        publish()
    }

    fun clear() {
        byConversation.value = emptyMap()
        publish()
    }

    private fun publish() {
        byToolCall.value = byConversation.value.values.flatten().associateBy { it.toolCallId }
    }
}
