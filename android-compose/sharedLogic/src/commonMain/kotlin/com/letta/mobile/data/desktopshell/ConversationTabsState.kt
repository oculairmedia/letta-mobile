package com.letta.mobile.data.desktopshell

/** Ordered kept tabs and the one replaceable preview. Tabs are session-local. */
data class ConversationTabsState(
    val pinnedConversationIds: List<String> = emptyList(),
    val transientConversationId: String? = null,
) {
    val openConversationIds: List<String> get() = pinnedConversationIds + listOfNotNull(transientConversationId)
}

data class ConversationTabCloseResult(
    val state: ConversationTabsState,
    val fallbackConversationId: String?,
)

object ConversationTabsReducer {
    fun select(state: ConversationTabsState, conversationId: String): ConversationTabsState =
        if (conversationId in state.pinnedConversationIds) state else state.copy(transientConversationId = conversationId)

    fun pin(state: ConversationTabsState, conversationId: String): ConversationTabsState =
        if (state.transientConversationId != conversationId) state else state.copy(
            pinnedConversationIds = state.pinnedConversationIds + conversationId,
            transientConversationId = null,
        )

    /** Unpinning the selected tab makes it the preview; any other unpinned tab closes. */
    fun unpin(state: ConversationTabsState, conversationId: String, selectedConversationId: String?): ConversationTabsState =
        if (conversationId !in state.pinnedConversationIds) state else state.copy(
            pinnedConversationIds = state.pinnedConversationIds - conversationId,
            transientConversationId = if (conversationId == selectedConversationId) conversationId else state.transientConversationId,
        )

    fun retainAvailable(
        state: ConversationTabsState,
        availableConversationIds: Set<String>,
        selectedConversationId: String?,
    ): ConversationTabsState {
        val kept = availableConversationIds + listOfNotNull(selectedConversationId)
        val retained = state.copy(
            pinnedConversationIds = state.pinnedConversationIds.filter { it in kept },
            transientConversationId = state.transientConversationId?.takeIf { it in kept },
        )
        return if (selectedConversationId != null && selectedConversationId !in retained.openConversationIds) {
            select(retained, selectedConversationId)
        } else {
            retained
        }
    }

    /** Moves a pinned tab within the pinned group; a preview cannot be reordered. */
    fun reorder(state: ConversationTabsState, conversationId: String, targetIndex: Int): ConversationTabsState {
        val pinned = state.pinnedConversationIds
        val currentIndex = pinned.indexOf(conversationId)
        if (currentIndex < 0 || pinned.size < 2) return state
        val target = targetIndex.coerceIn(0, pinned.lastIndex)
        if (target == currentIndex) return state
        val reordered = pinned.toMutableList()
        reordered.removeAt(currentIndex)
        reordered.add(target, conversationId)
        return state.copy(pinnedConversationIds = reordered)
    }

    fun close(state: ConversationTabsState, conversationId: String): ConversationTabCloseResult {
        val openIds = state.openConversationIds
        val index = openIds.indexOf(conversationId)
        val fallback = if (index < 0) null else openIds.getOrNull(index + 1) ?: openIds.getOrNull(index - 1)
        return ConversationTabCloseResult(
            state = state.copy(
                pinnedConversationIds = state.pinnedConversationIds - conversationId,
                transientConversationId = state.transientConversationId?.takeUnless { it == conversationId },
            ),
            fallbackConversationId = fallback,
        )
    }
}
