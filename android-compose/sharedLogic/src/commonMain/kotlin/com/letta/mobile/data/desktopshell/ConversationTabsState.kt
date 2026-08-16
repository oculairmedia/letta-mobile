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
