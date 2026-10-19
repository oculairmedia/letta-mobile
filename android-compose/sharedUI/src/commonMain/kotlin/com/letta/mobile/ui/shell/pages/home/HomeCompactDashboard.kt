package com.letta.mobile.ui.shell.pages.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.home.FleetAgentStat
import com.letta.mobile.data.home.FleetRecentConversation
import com.letta.mobile.data.home.FleetSort
import com.letta.mobile.data.home.HomeAgentRef
import com.letta.mobile.data.home.HomePageState
import com.letta.mobile.data.home.HomePinnedItem
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.data.home.HomeStatTile
import com.letta.mobile.data.home.relativeAgeLabel
import com.letta.mobile.data.home.sortFleet
import com.letta.mobile.data.home.statTiles
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.list.RosterListRow
import com.letta.mobile.ui.components.list.RosterRowSlots
import com.letta.mobile.ui.components.list.RosterRowState
import com.letta.mobile.ui.home.FleetBarSpark
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.mascot.AgentAvatar
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Home below [LettaDimens.Pane.wideBreakpoint]: what is happening and where
 * the user left off come first, the counters shrink to one strip. In order: a one-line "running
 * now" strip (only while something runs), the recent conversations, the agents as a row of avatars
 * (pinned first), then the stats strip whose overflow opens the pinned-tile editor. The full fleet
 * table stays on the wide layout and the Agents page.
 */
internal fun LazyListScope.homeCompactDashboard(page: HomePageScope) {
    homeNowStrip(page)
    homeCompactRecents(page)
    homeAgentAvatarRow(page)
    item(key = "stats-strip") { HomeStatsStrip(page) }
    if (page.editingPins) homePinnedSection(page) else homePinnedShortcutRow(page)
}

// region Now

private fun LazyListScope.homeNowStrip(page: HomePageScope) {
    val running = page.state.fleet.agents.filter { it.running }
    if (running.isEmpty()) return
    item(key = "now-strip") { NowStrip(running, page) }
}

@Composable
private fun NowStrip(running: List<FleetAgentStat>, page: HomePageScope) {
    val onClick = {
        if (page.offers(HomeShortcut.RUNS)) page.navigation.onOpenShortcut(HomeShortcut.RUNS) else page.navigation.onOpenAgent(running.first().agentId)
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().testTag(HomePageTags.NOW_STRIP),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            Box(Modifier.size(LettaDimens.Space.sm).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
            Text(
                text = nowLine(running),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(LettaIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.iconSm))
        }
    }
}

/** "2 running · Scout, Planner". */
internal fun nowLine(running: List<FleetAgentStat>): String =
    "${running.size} running · ${running.joinToString(", ") { it.name }}"

// endregion

// region Recents

private fun LazyListScope.homeCompactRecents(page: HomePageScope) {
    item(key = "recents-header") { SeeAllHeader(RECENT_LABEL, HomeShortcut.CONVERSATIONS, page, Modifier.testTag(HomePageTags.RECENTS)) }
    val recent = page.state.fleet.recent.take(COMPACT_RECENT_LIMIT)
    if (recent.isEmpty()) item(key = "recents-empty") { HomeEmptyLine(NO_CONVERSATIONS) }
    items(items = recent, key = { "recent-${it.conversationId}" }) { conversation ->
        CompactRecentRow(conversation, page)
    }
}

@Composable
private fun CompactRecentRow(conversation: FleetRecentConversation, page: HomePageScope) {
    val subtitle = listOf(conversation.agentName, conversation.preview).filter { it.isNotBlank() }.joinToString(" · ")
    RosterListRow(
        spec = LettaListRowSpec(
            title = conversation.title,
            trailing = relativeAgeLabel(conversation.updatedAtLabel),
            subtitle = subtitle.ifBlank { null },
        ),
        row = RosterRowState(onClick = { page.navigation.onOpenConversation(conversation) }),
        slots = RosterRowSlots(leading = { AgentAvatar(conversation.agentId, conversation.agentName, LettaDimens.Orb.md) }),
        modifier = Modifier.testTag(HomePageTags.recent(conversation)),
    )
}

// endregion

// region Agents

private fun LazyListScope.homeAgentAvatarRow(page: HomePageScope) {
    val agents = page.state.avatarRowAgents()
    if (agents.isEmpty()) return
    item(key = "agents-header") { SeeAllHeader(AGENTS_LABEL, HomeShortcut.AGENTS, page) }
    item(key = "agents-row") { AgentAvatarRow(agents, page) }
}

/** Pinned agents in the user's order, then the rest of the fleet newest activity first. */
internal fun HomePageState.avatarRowAgents(): List<HomeAgentRef> {
    val pinned = pinnedItems.filterIsInstance<HomePinnedItem.Agent>().map { it.agent }
    val pinnedIds = pinned.mapTo(HashSet()) { it.id }
    val rest = sortFleet(fleet.agents, FleetSort())
        .filter { it.agentId !in pinnedIds }
        .map { HomeAgentRef(it.agentId, it.name) }
    return (pinned + rest).take(AGENT_ROW_LIMIT)
}

@Composable
private fun AgentAvatarRow(agents: List<HomeAgentRef>, page: HomePageScope) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag(HomePageTags.AGENT_ROW),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        items(items = agents, key = { it.id }) { agent -> AgentAvatarTile(agent, page) }
    }
}

@Composable
private fun AgentAvatarTile(agent: HomeAgentRef, page: HomePageScope) {
    Column(
        modifier = Modifier
            .width(AvatarTileWidth)
            .clip(MaterialTheme.shapes.small)
            .clickable { page.navigation.onOpenAgent(agent.id) }
            .testTag(HomePageTags.agentAvatar(agent.id))
            .padding(vertical = LettaDimens.Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        AgentAvatar(agentId = agent.id, name = agent.name, size = LettaDimens.Orb.lg)
        Text(
            text = agent.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// endregion

// region Stats

/**
 * Every counter as a small value-over-label pair in one horizontally scrolling row; a figure opens
 * its page when the host offers it. Expanding shows the 14-day trend and the captions the tiles
 * carried; the overflow's "Customize Home" opens the pinned-tile editor.
 */
@Composable
private fun HomeStatsStrip(page: HomePageScope) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val tiles = page.state.statTiles()
    Column(Modifier.fillMaxWidth().testTag(HomePageTags.STATS_STRIP)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            ) {
                tiles.forEach { tile -> StatPair(tile, page) }
            }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag(HomePageTags.STATS_EXPAND)) {
                DisclosureChevron(expanded = expanded, compact = true, contentDescription = if (expanded) HIDE_TREND else SHOW_TREND)
            }
            StatsMenu(page)
        }
        if (expanded) StatsTrend(tiles)
    }
}

@Composable
private fun StatPair(tile: HomeStatTile, page: HomePageScope) {
    val target = tile.shortcut?.takeIf(page::offers)
    val clickable = if (target != null) Modifier.clickable { page.navigation.onOpenShortcut(target) } else Modifier
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .then(clickable)
            .testTag(HomePageTags.stat(tile.label))
            .padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
    ) {
        Text(tile.value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(tile.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun StatsTrend(tiles: List<HomeStatTile>) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.sm).testTag(HomePageTags.STATS_TREND),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        tiles.forEach { tile ->
            tile.series?.let { FleetBarSpark(it, Modifier.fillMaxWidth().height(LettaDimens.Space.xl)) }
            tile.caption?.let { caption ->
                Text(
                    text = "${tile.label}: $caption",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MUTED_ALPHA),
                )
            }
        }
    }
}

@Composable
private fun StatsMenu(page: HomePageScope) {
    if (!page.state.canCustomizePins) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(HomePageTags.STATS_MENU)) {
            Icon(LettaIcons.MoreVert, contentDescription = MORE_LABEL, modifier = Modifier.size(LettaDimens.Control.iconSm))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(CUSTOMIZE_LABEL) },
                onClick = {
                    open = false
                    page.onEditingPinsChange(true)
                },
                modifier = Modifier.testTag(HomePageTags.CUSTOMIZE),
            )
        }
    }
}

/** The pinned-tile editor has something to show: pins, or shortcuts the user could still pin. */
private val HomePageState.canCustomizePins: Boolean
    get() = pinsLoaded && (pinnedItems.isNotEmpty() || unpinnedShortcuts.isNotEmpty())

// endregion

// region Pinned shortcuts

/** Pinned shortcuts the stats strip does not already open, as one row of chips. */
private fun LazyListScope.homePinnedShortcutRow(page: HomePageScope) {
    val covered = page.state.statTiles().mapNotNullTo(HashSet()) { it.shortcut }
    val shortcuts = page.state.pinnedItems.filterIsInstance<HomePinnedItem.Shortcut>().filter { it.shortcut !in covered }
    if (shortcuts.isEmpty()) return
    item(key = "pinned-shortcuts") {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(HomePageTags.SHORTCUT_ROW),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            shortcuts.forEach { item ->
                AssistChip(
                    onClick = { page.navigation.onOpenShortcut(item.shortcut) },
                    label = { Text(item.shortcut.label) },
                    leadingIcon = { Icon(item.shortcut.icon, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.iconSm)) },
                    modifier = Modifier.testTag(HomePageTags.pin(item)),
                )
            }
        }
    }
}

// endregion

@Composable
private fun SeeAllHeader(label: String, target: HomeShortcut, page: HomePageScope, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HomeSectionLabel(label, Modifier.weight(1f))
        if (page.offers(target)) {
            TextButton(onClick = { page.navigation.onOpenShortcut(target) }, modifier = Modifier.testTag(HomePageTags.seeAll(target))) {
                Text(SEE_ALL)
            }
        }
    }
}

private const val COMPACT_RECENT_LIMIT = 6
private const val AGENT_ROW_LIMIT = 12
private const val AGENTS_LABEL = "Agents"
private const val SEE_ALL = "See all"
private const val SHOW_TREND = "Show trend"
private const val HIDE_TREND = "Hide trend"
private const val MORE_LABEL = "More"
private const val CUSTOMIZE_LABEL = "Customize Home"
private val AvatarTileWidth = 64.dp
