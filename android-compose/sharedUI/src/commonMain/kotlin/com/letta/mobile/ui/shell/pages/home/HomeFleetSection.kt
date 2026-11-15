package com.letta.mobile.ui.shell.pages.home

import com.letta.mobile.ui.shell.tokens.ShellType
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.components.LocalObjectVerbs
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.foundation.LettaButtonIcon
import com.letta.mobile.ui.components.foundation.LettaDivider
import com.letta.mobile.ui.components.foundation.LettaIconButton
import com.letta.mobile.ui.components.foundation.LettaIconStyle
import com.letta.mobile.ui.components.foundation.LettaStatusDot
import com.letta.mobile.ui.components.foundation.lettaClickable
import com.letta.mobile.ui.components.items
import com.letta.mobile.data.objectverbs.ObjectRef

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.home.FleetAgentStat
import com.letta.mobile.data.home.FleetSortKey
import com.letta.mobile.util.time.TimeLabels
import com.letta.mobile.ui.home.FleetBarSpark
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.mascot.AgentAvatar
import com.letta.mobile.ui.shell.tokens.ShellDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaSectionLabel
import com.letta.mobile.ui.theme.tokens.LettaStatusTone
import com.letta.mobile.ui.theme.tokens.LettaTextStyles

/**
 * The fleet: every agent with its model, chat count, last activity and 48-hour event strip. A wide
 * window gets the sortable table; a phone gets compact rows with sort chips. Either way a row opens
 * the agent and its pin toggles the agent's tile in the pinned grid.
 */
internal fun LazyListScope.homeFleetSection(page: HomePageScope) {
    item(key = "fleet-label") { LettaSectionLabel(FLEET_LABEL) }
    item(key = "fleet-sort") { if (page.wide) FleetTableHeader(page) else FleetSortChips(page) }
    val rows = page.state.sortedAgents
    if (rows.isEmpty()) item(key = "fleet-empty") { LettaEmptyHint(NO_AGENTS) }
    items(items = rows, key = { "fleet-${it.agentId}" }) { agent ->
        FleetAgentRow(agent, page)
    }
}

@Composable
private fun FleetTableHeader(page: HomePageScope) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.sm), verticalAlignment = Alignment.CenterVertically) {
            SortHeaderCell(FleetSortKey.Agent, page, Modifier.weight(1f))
            SortHeaderCell(FleetSortKey.Model, page, Modifier.width(ShellDimens.fleetModelColumn))
            SortHeaderCell(FleetSortKey.Conversations, page, Modifier.width(ShellDimens.fleetCountColumn), alignEnd = true)
            SortHeaderCell(FleetSortKey.LastActivity, page, Modifier.width(ShellDimens.fleetTimeColumn), alignEnd = true)
            Text(
                text = STRIP_LABEL,
                style = LettaTextStyles.labelSmallSemibold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = LettaDimens.Alpha.muted),
                textAlign = TextAlign.End,
                modifier = Modifier.width(ShellDimens.fleetSparkColumn),
            )
            Box(Modifier.width(LettaDimens.Control.iconButton))
        }
        LettaDivider()
    }
}

@Composable
private fun SortHeaderCell(key: FleetSortKey, page: HomePageScope, modifier: Modifier, alignEnd: Boolean = false) {
    val sort = page.state.sort
    val active = sort.key == key
    Row(
        modifier = modifier
            .lettaClickable(role = Role.Button, shape = MaterialTheme.shapes.small, onClick = { page.actions.selectSort(key) })
            .testTag(HomePageTags.sort(key))
            .padding(vertical = LettaDimens.Space.xs, horizontal = LettaDimens.Space.hair),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) Arrangement.spacedBy(LettaDimens.Space.xs, Alignment.End) else Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Text(
            text = key.label.uppercase(),
            style = LettaTextStyles.labelSmallSemibold,
            color = if (active) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = LettaDimens.Alpha.muted)
            },
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
            LettaChipTab(
                text = if (active) "${key.label} ${sortArrow(sort.descending)}" else key.label,
                active = active,
                onClick = { page.actions.selectSort(key) },
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
                .lettaClickable(
                    role = Role.Button,
                    shape = MaterialTheme.shapes.small,
                    onClick = { page.navigation.onOpenAgent(agent.agentId) },
                )
                .testTag(HomePageTags.agentRow(agent))
                .padding(vertical = LettaDimens.Space.sm, horizontal = LettaDimens.Space.hair),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FleetAgentIdentity(agent, page, Modifier.weight(1f))
            if (page.wide) {
                FleetCell(agent.modelLabel, Modifier.width(ShellDimens.fleetModelColumn))
                FleetCell(
                    agent.conversationCount.toString(),
                    Modifier.width(ShellDimens.fleetCountColumn),
                    strong = agent.conversationCount > 0,
                    align = TextAlign.End,
                )
            }
            val age = agent.lastActivity?.let { TimeLabels.age(it) } ?: DASH
            FleetCell(age, Modifier.width(ShellDimens.fleetTimeColumn), align = TextAlign.End)
            if (page.wide) {
                Box(Modifier.width(ShellDimens.fleetSparkColumn), contentAlignment = Alignment.CenterEnd) {
                    FleetBarSpark(
                        values = agent.activityByHour,
                        modifier = Modifier.width(ShellDimens.fleetSparkBars).height(LettaDimens.Space.lg),
                    )
                }
            }
            AgentPinToggle(agent)
        }
        LettaDivider()
    }
}

@Composable
private fun FleetAgentIdentity(agent: FleetAgentStat, page: HomePageScope, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
        AgentAvatar(agentId = agent.agentId, name = agent.name, size = LettaDimens.Orb.sm, cornerRadius = LettaDimens.Radius.sm)
        Column(Modifier.weight(1f, fill = false)) {
            // An unresolved name is the raw backend id; it renders as the technical fallback it is.
            Text(
                text = agent.name,
                style = if (agent.nameIsIdFallback) ShellType.idFallback else ShellType.bodyEmphasis,
                color = if (agent.nameIsIdFallback) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!page.wide) {
                Text("${agent.modelLabel} · ${agent.conversationCount} chats", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Only running agents get a dot; idle is the absence of a marker.
        if (agent.running) LettaStatusDot(LettaStatusTone.Running)
    }
}

@Composable
private fun AgentPinToggle(agent: FleetAgentStat) {
    val agentId = agent.agentId
    val pin = LocalObjectVerbs.current?.items(ObjectRef("agent", agentId), "home-tile")?.singleOrNull() ?: return
    val glyph = LettaIconStyle(LettaDimens.Control.iconSm, MaterialTheme.colorScheme.onSurfaceVariant)
    LettaIconButton(
        icon = LettaButtonIcon(pin.icon ?: LettaIcons.Pin, pin.label, glyph = glyph),
        onClick = pin.onClick,
        enabled = pin.enabled,
        modifier = Modifier.testTag(HomePageTags.pinAgent(agent)),
    )
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

private fun sortArrow(descending: Boolean): String = if (descending) "▼" else "▲"

private const val FLEET_LABEL = "Fleet"
private const val STRIP_LABEL = "LAST 48H"
private const val NO_AGENTS = "No agents yet. Create one to see it here."
private const val DASH = "—"
