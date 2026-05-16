package com.letta.mobile.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.letta.mobile.data.model.UiMessage
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.MutableStateFlow

internal fun runIdsEligibleForCompletionAutoCollapse(messages: List<UiMessage>): Set<String> {
    val newestRunId = messages.asReversed().firstNotNullOfOrNull { message ->
        message.runId?.takeIf { message.role == "assistant" && it.isNotBlank() }
    }
    return newestRunId?.let { setOf(it) }.orEmpty()
}

internal fun collapsedRunIdsAfterRunCompletion(
    messages: List<UiMessage>,
    collapsedRunIds: Set<String>,
    autoCollapseSuppressedRunIds: Set<String>,
): Set<String> {
    val eligibleRunIds = runIdsEligibleForCompletionAutoCollapse(messages)
        .filterNot { it in autoCollapseSuppressedRunIds }
    if (eligibleRunIds.isEmpty()) return collapsedRunIds
    return LinkedHashSet<String>(collapsedRunIds).apply { addAll(eligibleRunIds) }
}

/**
 * Owns persisted expansion/collapse state for run blocks and reasoning sections.
 * Keeping this out of [AdminChatViewModel] makes the VM only delegate user
 * gestures and timeline completion hooks while this class handles SavedState
 * persistence plus [ChatUiState] projection.
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

    fun collapseCompletedRunsIfStreamingFinished(
        previous: ChatUiState,
        next: ChatUiState,
    ): ChatUiState = if (previous.isStreaming && !next.isStreaming) {
        collapseCompletedRunsByDefault(next)
    } else {
        next
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

    private fun collapseCompletedRunsByDefault(state: ChatUiState): ChatUiState {
        val nextCollapsed = collapsedRunIdsAfterRunCompletion(
            messages = state.messages,
            collapsedRunIds = state.collapsedRunIds,
            autoCollapseSuppressedRunIds = autoCollapseSuppressedRunIds(),
        )
        if (nextCollapsed == state.collapsedRunIds) return state
        savedStateHandle[COLLAPSED_RUN_IDS_KEY] = ArrayList(nextCollapsed)
        return state.copy(collapsedRunIds = nextCollapsed.toImmutableSet())
    }

    private companion object {
        const val COLLAPSED_RUN_IDS_KEY = "collapsedRunIds"
        const val AUTO_COLLAPSE_SUPPRESSED_RUN_IDS_KEY = "autoCollapseSuppressedRunIds"
        const val EXPANDED_REASONING_MESSAGE_IDS_KEY = "expandedReasoningMessageIds"
    }
}
