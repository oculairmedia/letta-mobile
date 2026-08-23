package com.letta.mobile.feature.chat.coordination

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.chat.projection.projectRunActivity
import com.letta.mobile.data.model.UiMessage
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.MutableStateFlow
import com.letta.mobile.ui.chat.render.ChatUiState

/**
 * letta-mobile-ah1ng: one run's assistant-side messages, grouped by the
 * server `runId`, in first-appearance order. User messages never carry a
 * `runId`; blank ids are skipped so legacy hydrated history stays ungrouped.
 */
internal data class AssistantRunGroup(
    val runId: String,
    val messages: List<UiMessage>,
)

/**
 * Groups [messages] by non-blank [UiMessage.runId] preserving appearance
 * order. All roles sharing a run id are grouped together so a pending
 * tool-return row keeps its run active during reconciliation — matching the
 * render-side grouping that feeds [projectRunActivity].
 */
internal fun assistantRunGroups(messages: List<UiMessage>): List<AssistantRunGroup> {
    val grouped = LinkedHashMap<String, MutableList<UiMessage>>()
    for (message in messages) {
        val runId = message.runId?.takeIf { it.isNotBlank() } ?: continue
        grouped.getOrPut(runId) { mutableListOf() }.add(message)
    }
    return grouped.map { (runId, groupMessages) -> AssistantRunGroup(runId, groupMessages) }
}

/**
 * letta-mobile-ah1ng: terminal-run reconciliation selection.
 *
 * Returns the run ids that must be auto-collapsed given the projected
 * timeline, i.e. runs whose projected activity is terminal (no pending rows,
 * not the streaming tail) and that are neither already collapsed nor
 * explicitly suppressed by a user expansion.
 *
 * Unlike the previous global "streaming just finished" edge, this derives
 * collapse from per-run terminal identity, so:
 *  - a completed run first observed during hydration/reconnect collapses;
 *  - a terminal projection that lands before presence clears still collapses
 *    once presence clears (and vice versa);
 *  - an active newest run stays expanded while prior terminal runs collapse
 *    deterministically.
 *
 * Selection is pure and additive: callers merge the result into their
 * collapsed set without ever removing entries.
 */
internal fun runIdsToCollapseOnTerminalReconciliation(
    messages: List<UiMessage>,
    collapsedRunIds: Set<String>,
    autoCollapseSuppressedRunIds: Set<String>,
    conversationHasActivePresence: Boolean,
): Set<String> {
    val groups = assistantRunGroups(messages)
    if (groups.isEmpty()) return emptySet()
    val newestRunId = groups.last().runId
    return buildSet {
        for (group in groups) {
            val runId = group.runId
            if (runId in collapsedRunIds || runId in autoCollapseSuppressedRunIds) continue
            val activity = projectRunActivity(
                messages = group.messages,
                // Streaming presence only scopes to the newest run; older runs
                // with no pending rows are already terminal even mid-conversation.
                isActiveRunStreaming = conversationHasActivePresence && runId == newestRunId,
            )
            if (activity != null && !activity.isActive) add(runId)
        }
    }
}

/**
 * Owns persisted expansion/collapse state for run blocks and reasoning sections.
 * Keeping this out of AdminChatViewModel makes the VM only delegate user
 * gestures and timeline projection hooks while this class handles SavedState
 * persistence plus ChatUiState projection.
 */
internal class ChatRunExpansionState(
    private val savedStateHandle: SavedStateHandle,
    private val uiState: MutableStateFlow<ChatUiState>,
) {
    fun hydrateUiState() {
        uiState.value = uiState.value.copy(
            collapsedRunIds = collapsedRunIds().toImmutableSet(),
            expandedReasoningMessageIds = expandedReasoningMessageIds().toImmutableSet(),
        )
    }

    fun toggleRunCollapsed(runId: String) {
        val nextCollapsed = collapsedRunIds().toMutableSet()
        val nextSuppressed = autoCollapseSuppressedRunIds().toMutableSet()
        if (nextCollapsed.remove(runId)) {
            // User expanded an auto-collapsed completed run; do not immediately
            // collapse it again on the next timeline emission.
            nextSuppressed.add(runId)
        } else {
            nextCollapsed.add(runId)
            nextSuppressed.remove(runId)
        }
        persistAutoCollapseSuppressedRunIds(nextSuppressed)
        persistCollapsedRunIds(nextCollapsed)
    }

    fun toggleReasoningExpanded(messageId: String) {
        val next = expandedReasoningMessageIds().toMutableSet().apply {
            if (!add(messageId)) remove(messageId)
        }
        persistExpandedReasoningMessageIds(next)
    }

    /**
     * letta-mobile-ah1ng: reconcile run expansion against every production
     * projection publication instead of only the global isStreaming edge.
     *
     * Runs whose projected activity has become terminal collapse by default;
     * explicitly user-expanded ([autoCollapseSuppressedRunIds]) and active
     * runs stay open, previously collapsed runs stay collapsed. When nothing
     * needs to change, [next] is returned untouched so projection dedupe
     * identity (same instance => no recomposition) is preserved.
     */
    fun reconcileCollapsedRunsOnProjection(
        previous: ChatUiState,
        next: ChatUiState,
    ): ChatUiState {
        val newlyTerminalRunIds = runIdsToCollapseOnTerminalReconciliation(
            messages = next.messages,
            collapsedRunIds = next.collapsedRunIds,
            autoCollapseSuppressedRunIds = autoCollapseSuppressedRunIds(),
            conversationHasActivePresence = next.isStreaming || next.isAgentTyping,
        )
        if (newlyTerminalRunIds.isEmpty()) return next
        val merged = LinkedHashSet<String>(next.collapsedRunIds).apply {
            addAll(newlyTerminalRunIds)
        }
        // Persist directly WITHOUT touching uiState here: the caller owns the
        // publication and will assign the returned copy, so an intermediate
        // uiState write would briefly publish a state missing `next`'s fields.
        savedStateHandle[COLLAPSED_RUN_IDS_KEY] = ArrayList(merged)
        return next.copy(collapsedRunIds = merged.toImmutableSet())
    }

    private fun collapsedRunIds(): Set<String> =
        savedStateHandle.get<ArrayList<String>>(COLLAPSED_RUN_IDS_KEY)?.toSet().orEmpty()

    private fun autoCollapseSuppressedRunIds(): Set<String> =
        savedStateHandle.get<ArrayList<String>>(AUTO_COLLAPSE_SUPPRESSED_RUN_IDS_KEY)?.toSet().orEmpty()

    private fun expandedReasoningMessageIds(): Set<String> =
        savedStateHandle.get<ArrayList<String>>(EXPANDED_REASONING_MESSAGE_IDS_KEY)?.toSet().orEmpty()

    private fun persistCollapsedRunIds(ids: Set<String>) {
        savedStateHandle[COLLAPSED_RUN_IDS_KEY] = ArrayList(ids)
        uiState.value = uiState.value.copy(collapsedRunIds = ids.toImmutableSet())
    }

    private fun persistExpandedReasoningMessageIds(ids: Set<String>) {
        savedStateHandle[EXPANDED_REASONING_MESSAGE_IDS_KEY] = ArrayList(ids)
        uiState.value = uiState.value.copy(expandedReasoningMessageIds = ids.toImmutableSet())
    }

    private fun persistAutoCollapseSuppressedRunIds(ids: Set<String>) {
        savedStateHandle[AUTO_COLLAPSE_SUPPRESSED_RUN_IDS_KEY] = ArrayList(ids)
    }

    private companion object {
        const val COLLAPSED_RUN_IDS_KEY = "collapsedRunIds"
        const val AUTO_COLLAPSE_SUPPRESSED_RUN_IDS_KEY = "autoCollapseSuppressedRunIds"
        const val EXPANDED_REASONING_MESSAGE_IDS_KEY = "expandedReasoningMessageIds"
    }
}
