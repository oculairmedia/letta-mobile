package com.letta.mobile.feature.chat.coordination

import androidx.lifecycle.SavedStateHandle
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
        if (nextCollapsed.remove(runId)) {
            // Explicit expansion remains stable because projection no longer
            // auto-collapses terminal or hydrated runs.
        } else {
            nextCollapsed.add(runId)
        }
        persistCollapsedRunIds(nextCollapsed)
    }

    fun toggleReasoningExpanded(messageId: String) {
        val next = expandedReasoningMessageIds().toMutableSet().apply {
            if (!add(messageId)) remove(messageId)
        }
        persistExpandedReasoningMessageIds(next)
    }

    /** Keep projection geometry unchanged across live, terminal, and hydrated states. */
    fun reconcileCollapsedRunsOnProjection(
        @Suppress("UNUSED_PARAMETER") previous: ChatUiState,
        next: ChatUiState,
    ): ChatUiState = next

    private fun collapsedRunIds(): Set<String> =
        savedStateHandle.get<ArrayList<String>>(COLLAPSED_RUN_IDS_KEY)?.toSet().orEmpty()

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

    private companion object {
        const val COLLAPSED_RUN_IDS_KEY = "collapsedRunIds"
        const val EXPANDED_REASONING_MESSAGE_IDS_KEY = "expandedReasoningMessageIds"
    }
}
