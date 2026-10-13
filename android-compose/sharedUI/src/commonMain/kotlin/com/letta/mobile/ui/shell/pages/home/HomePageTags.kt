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
    const val ERROR = "home_error"

    /** The header's refresh, on pointer hosts only (touch pulls the page instead). */
    const val REFRESH = "home_refresh"

    /** Compact Home (phone): the one-line "running now" strip, recents, the agent avatar row and the stats strip. */
    const val NOW_STRIP = "home_now_strip"
    const val RECENTS = "home_recents"
    const val AGENT_ROW = "home_agent_row"
    const val STATS_STRIP = "home_stats_strip"
    const val STATS_EXPAND = "home_stats_expand"
    const val STATS_TREND = "home_stats_trend"
    const val STATS_MENU = "home_stats_menu"
    const val CUSTOMIZE = "home_customize"
    const val SHORTCUT_ROW = "home_shortcut_row"

    fun seeAll(shortcut: HomeShortcut): String = "home_see_all_${shortcut.name}"

    fun agentAvatar(agentId: String): String = "home_avatar_$agentId"

    fun stat(label: String): String = "home_stat_$label"

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
