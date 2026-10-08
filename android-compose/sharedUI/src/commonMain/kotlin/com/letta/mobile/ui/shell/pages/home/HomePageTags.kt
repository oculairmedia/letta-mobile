package com.letta.mobile.ui.shell.pages.home

import com.letta.mobile.data.home.FleetAgentStat
import com.letta.mobile.data.home.FleetRecentConversation
import com.letta.mobile.data.home.FleetSortKey
import com.letta.mobile.data.home.HomePinnedItem
import com.letta.mobile.data.home.HomeShortcut

/** Test tags for the shared Home page. */
object HomePageTags {
    const val PAGE = "home_page"
    const val SEARCH = "home_search"
    const val SEARCH_RESULTS = "home_search_results"
    const val SEARCH_EMPTY = "home_search_empty"
    const val COMPOSER = "home_composer"
    const val SEND = "home_send"
    const val PINNED_GRID = "home_pinned_grid"
    const val EDIT_PINS = "home_edit_pins"
    const val ADD_PIN = "home_add_pin"
    const val STATS = "home_stats"

    fun pin(item: HomePinnedItem): String = "home_pin_${item.key}"

    fun unpin(item: HomePinnedItem): String = "home_unpin_${item.key}"

    fun configure(agentId: String): String = "home_configure_$agentId"

    fun addShortcut(shortcut: HomeShortcut): String = "home_add_${shortcut.name}"

    fun recent(conversation: FleetRecentConversation): String = "home_recent_${conversation.conversationId}"

    fun agentRow(agent: FleetAgentStat): String = "home_agent_${agent.agentId}"

    fun pinAgent(agent: FleetAgentStat): String = "home_pin_agent_${agent.agentId}"

    fun sort(key: FleetSortKey): String = "home_sort_${key.name}"

    fun searchHit(id: String): String = "home_hit_$id"
}
