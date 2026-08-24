package com.letta.mobile.data.desktopshell

data class ConversationTabsState(
    val openConversationIds: List<String> = emptyList(),
)

data class ConversationTabCloseResult(
    val state: ConversationTabsState,
    val fallbackConversationId: String?,
)

object ConversationTabsReducer {
    fun select(state: ConversationTabsState, conversationId: String): ConversationTabsState =
        if (conversationId in state.openConversationIds) {
            state
        } else {
            state.copy(openConversationIds = state.openConversationIds + conversationId)
        }

    fun retainAvailable(
        state: ConversationTabsState,
        availableConversationIds: Set<String>,
        selectedConversationId: String?,
    ): ConversationTabsState {
        val retained = state.openConversationIds.filter { it in availableConversationIds }.toMutableList()
        if (selectedConversationId != null && selectedConversationId !in retained) {
            retained += selectedConversationId
        }
        return state.copy(openConversationIds = retained)
    }

    /**
     * Moves [conversationId] so it occupies [targetIndex] in the open-tab
     * order, shifting the tabs between its old and new position over by one.
     * A no-op when the id isn't open, or when [targetIndex] (after clamping
     * to the valid range) resolves to the tab's current position — including
     * dropping a tab back onto itself.
     */
    fun reorder(state: ConversationTabsState, conversationId: String, targetIndex: Int): ConversationTabsState {
        val openIds = state.openConversationIds
        val currentIndex = openIds.indexOf(conversationId)
        if (currentIndex < 0 || openIds.size < 2) return state
        val clampedTarget = targetIndex.coerceIn(0, openIds.lastIndex)
        if (clampedTarget == currentIndex) return state
        val reordered = openIds.toMutableList()
        reordered.removeAt(currentIndex)
        reordered.add(clampedTarget, conversationId)
        return state.copy(openConversationIds = reordered)
    }

    fun close(state: ConversationTabsState, conversationId: String): ConversationTabCloseResult {
        val openIds = state.openConversationIds
        val closingIndex = openIds.indexOf(conversationId)
        val fallback = if (closingIndex < 0) {
            null
        } else {
            openIds.getOrNull(closingIndex + 1) ?: openIds.getOrNull(closingIndex - 1)
        }
        return ConversationTabCloseResult(
            state = state.copy(openConversationIds = openIds.filterNot { it == conversationId }),
            fallbackConversationId = fallback,
        )
    }
}
