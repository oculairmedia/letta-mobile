package com.letta.mobile.data.runtime

import androidx.compose.runtime.Immutable
import com.letta.mobile.runtime.ApprovalDiffPreview
import com.letta.mobile.runtime.PermissionSuggestion
import com.letta.mobile.runtime.ToolApprovalRequest
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the server's `can_use_tool` control request offered for one parked tool call
 * (letta-mobile-bzvro.11 / .12), keyed by tool call so the approval card, which is
 * projected from the streamed `approval_request_message`, can find it.
 *
 * An entry exists only while the request genuinely waits on the person: the engine records it
 * for a control request that reached the collect body (not auto-approved) and drops it when the
 * tool returns, the answer goes out, or the turn ends.
 */
@Immutable
data class PendingApprovalDetails(
    /** The control request's real `request_id`: the id an `approval_response` must carry. */
    val approvalId: String,
    val toolCallId: String,
    val toolName: String,
    val suggestions: List<PermissionSuggestion> = emptyList(),
    val blockedPath: String? = null,
    val diffs: List<ApprovalDiffPreview> = emptyList(),
) {
    internal companion object {
        fun of(request: ToolApprovalRequest) = PendingApprovalDetails(
            approvalId = request.approvalId.value,
            toolCallId = request.callId.value,
            toolName = request.toolName.value,
            suggestions = request.suggestions,
            blockedPath = request.blockedPath,
            diffs = request.diffs,
        )
    }
}

/** Observable registry of [PendingApprovalDetails]; entries are scoped to a runtime so a turn end clears only its own. */
internal class PendingApprovalDetailsStore {
    private data class Entry(val key: TurnRuntimeKey, val details: PendingApprovalDetails)

    private val lock = SynchronizedObject()
    private var entries = emptyMap<String, Entry>()
    private val view = MutableStateFlow<Map<String, PendingApprovalDetails>>(emptyMap())

    /** Pending details by tool call id. */
    val pending: StateFlow<Map<String, PendingApprovalDetails>> = view.asStateFlow()

    fun record(key: TurnRuntimeKey, details: PendingApprovalDetails) {
        publish { it + (details.toolCallId to Entry(key, details)) }
    }

    fun resolve(toolCallId: String) {
        publish { it - toolCallId }
    }

    fun clearKey(key: TurnRuntimeKey) {
        publish { current -> current.filterValues { it.key != key } }
    }

    private fun publish(change: (Map<String, Entry>) -> Map<String, Entry>) {
        synchronized(lock) {
            entries = change(entries)
            view.value = entries.mapValues { it.value.details }
        }
    }
}
