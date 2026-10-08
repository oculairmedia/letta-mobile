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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.home.FleetAgentStat
import com.letta.mobile.data.home.FleetSortKey
import com.letta.mobile.data.home.relativeAge
import com.letta.mobile.ui.chat.AgentOrb
import com.letta.mobile.ui.home.FleetBarSpark
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The fleet: every agent with its model, chat count, last activity and 48-hour event strip. A wide
 * window gets the sortable table; a phone gets compact rows with sort chips. Either way a row opens
 * the agent and its pin toggles the agent's tile in the pinned grid.
 */
internal fun LazyListScope.homeFleetSection(page: HomePageScope) {
    item(key = "fleet-label") { HomeSectionLabel(FLEET_LABEL) }
    item(key = "fleet-sort") { if (page.wide) FleetTableHeader(page) else FleetSortChips(page) }
    val rows = page.state.sortedAgents
    if (rows.isEmpty()) item(key = "fleet-empty") { HomeEmptyLine(NO_AGENTS) }
    items(items = rows, key = { "fleet-${it.agentId}" }) { agent ->
        FleetAgentRow(agent, page)
    }
}

@Composable
private fun FleetTableHeader(page: HomePageScope) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.sm), verticalAlignment = Alignment.CenterVertically) {
            SortHeaderCell(FleetSortKey.Agent, page, Modifier.weight(1f))
            SortHeaderCell(FleetSortKey.Model, page, Modifier.width(ModelWidth))
            SortHeaderCell(FleetSortKey.Conversations, page, Modifier.width(CountWidth), alignEnd = true)
            SortHeaderCell(FleetSortKey.LastActivity, page, Modifier.width(TimeWidth), alignEnd = true)
            Text(
                text = STRIP_LABEL,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MUTED_ALPHA),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier.width(SparkWidth),
            )
            Box(Modifier.width(PinWidth))
        }
        RowHairline()
    }
}

@Composable
private fun SortHeaderCell(key: FleetSortKey, page: HomePageScope, modifier: Modifier, alignEnd: Boolean = false) {
    val sort = page.state.sort
    val active = sort.key == key
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable { page.actions.selectSort(key) }
            .testTag(HomePageTags.sort(key))
            .padding(vertical = LettaDimens.Space.xs, horizontal = LettaDimens.Space.hair),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.spacedBy(LettaDimens.Space.xs, Alignment.End) else Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Text(
            text = key.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MUTED_ALPHA),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (active) Text(sortArrow(sort.descending), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun FleetSortChips(page: HomePageScope) {
    val sort = page.state.sort
    val chipModifier = if (page.options.touch) Modifier.minimumInteractiveComponentSize() else Modifier
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        FleetSortKey.entries.forEach { key ->
            val active = sort.key == key
            FilterChip(
                selected = active,
                onClick = { page.actions.selectSort(key) },
                label = { Text(if (active) "${key.label} ${sortArrow(sort.descending)}" else key.label) },
                modifier = chipModifier.testTag(HomePageTags.sort(key)),
            )
        }
    }
}

@Composable
private fun FleetAgentRow(agent: FleetAgentStat, page: HomePageScope) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { page.navigation.onOpenAgent(agent.agentId) }
                .testTag(HomePageTags.agentRow(agent))
                .padding(vertical = LettaDimens.Space.sm, horizontal = LettaDimens.Space.hair),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FleetAgentIdentity(agent, page, Modifier.weight(1f))
            if (page.wide) {
                FleetCell(agent.modelLabel, Modifier.width(ModelWidth))
                FleetCell(agent.conversationCount.toString(), Modifier.width(CountWidth), strong = agent.conversationCount > 0, align = TextAlign.End)
            }
            FleetCell(agent.lastActivity?.let { relativeAge(it) } ?: DASH, Modifier.width(TimeWidth), align = TextAlign.End)
            if (page.wide) {
                Box(Modifier.width(SparkWidth), contentAlignment = Alignment.CenterEnd) {
                    FleetBarSpark(values = agent.activityByHour, modifier = Modifier.width(SparkBarsWidth).height(LettaDimens.Space.lg))
                }
            }
            AgentPinToggle(agent, page)
        }
        RowHairline()
    }
}

@Composable
private fun FleetAgentIdentity(agent: FleetAgentStat, page: HomePageScope, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
        AgentOrb(agentId = agent.agentId, index = page.orbIndex(agent.agentId), size = LettaDimens.Orb.sm, cornerRadius = LettaDimens.Radius.sm)
        Column(Modifier.weight(1f, fill = false)) {
            // An unresolved name is the raw backend id; it renders as the technical fallback it is.
            Text(
                text = agent.name,
                style = if (agent.nameIsIdFallback) MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
                fontWeight = if (agent.nameIsIdFallback) FontWeight.Normal else FontWeight.Medium,
                color = if (agent.nameIsIdFallback) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!page.wide) {
                Text("${agent.modelLabel} · ${agent.conversationCount} chats", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Only running agents get a dot; idle is the absence of a marker.
        if (agent.running) Box(Modifier.size(LettaDimens.Space.sm).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
    }
}

@Composable
private fun AgentPinToggle(agent: FleetAgentStat, page: HomePageScope) {
    val agentId = agent.agentId
    val pinned = page.state.isAgentPinned(agentId)
    IconButton(
        onClick = { page.actions.setAgentPinned(agentId, !pinned) },
        modifier = Modifier.width(PinWidth).testTag(HomePageTags.pinAgent(agent)),
    ) {
        Icon(
            imageVector = if (pinned) LettaIcons.PinOff else LettaIcons.Pin,
            contentDescription = if (pinned) UNPIN_AGENT else PIN_AGENT,
            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(LettaDimens.Control.iconSm),
        )
    }
}

@Composable
private fun FleetCell(text: String, modifier: Modifier, strong: Boolean = false, align: TextAlign = TextAlign.Start) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        modifier = modifier,
    )
}

@Composable
private fun RowHairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline)))
}

private fun sortArrow(descending: Boolean): String = if (descending) "▼" else "▲"

private const val FLEET_LABEL = "Fleet"
private const val STRIP_LABEL = "LAST 48H"
private const val NO_AGENTS = "No agents yet. Create one to see it here."
private const val PIN_AGENT = "Pin to Home"
private const val UNPIN_AGENT = "Unpin from Home"
private const val DASH = "—"
private val ModelWidth = 150.dp
private val CountWidth = 56.dp
private val TimeWidth = 84.dp
private val SparkWidth = 72.dp
private val SparkBarsWidth = 60.dp
private val PinWidth = 40.dp
