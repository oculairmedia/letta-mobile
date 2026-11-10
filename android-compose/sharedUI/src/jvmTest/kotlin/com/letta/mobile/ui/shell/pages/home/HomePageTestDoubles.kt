package com.letta.mobile.ui.shell.pages.home

import com.letta.mobile.data.home.FleetSortKey
import com.letta.mobile.data.home.HomePageActions
import com.letta.mobile.data.home.HomeShortcut

/** Callbacks for a Home page a test only looks at: every action and every navigation does nothing. */
internal val InertHomeCallbacks = HomePageCallbacks(
    InertHomeActions,
    HomePageNavigation(onSubmitPrompt = {}, onOpenConversation = {}, onOpenAgent = {}, onOpenShortcut = {}),
)

private object InertHomeActions : HomePageActions {
    override fun loadIfStale() = Unit
    override fun refresh() = Unit
    override fun updateSearchQuery(query: String) = Unit
    override fun clearSearch() = Unit
    override fun selectSort(key: FleetSortKey) = Unit
    override fun reorderPins(keys: List<String>) = Unit
    override fun setShortcutPinned(shortcut: HomeShortcut, pinned: Boolean) = Unit
    override fun setAgentPinned(agentId: String, pinned: Boolean) = Unit
}
