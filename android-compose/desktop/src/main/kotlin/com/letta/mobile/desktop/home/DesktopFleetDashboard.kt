package com.letta.mobile.desktop.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.AgentOrb
import com.letta.mobile.ui.home.FleetBarSpark
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The fleet dashboard: counters plus a sortable agent table.
 *
 * It sits *below* Home's recent-conversations list — useful as reference, but
 * not what the page is about. Emitted as lazy items so the whole page stays one
 * scroll container.
 */
internal fun LazyListScope.fleetDashboardSection(
    state: DesktopHomeState,
    onSortKeySelected: (FleetSortKey) -> Unit,
    onOpenAgent: (String) -> Unit,
) {
    item { HomeSectionLabel("Fleet") }
    item { FleetStatTiles(state.overview.summary) }
    item { FleetTableHeader(sort = state.sort, onSortKeySelected = onSortKeySelected) }
    val rows = sortFleet(state.overview.agents, state.sort)
    if (rows.isEmpty()) {
        item { HomeEmptyLine("No agents yet. Create one from the rail to see it here.") }
    }
    items(items = rows, key = { it.agentId }) { agent ->
        FleetAgentRow(
            agent = agent,
            orbIndex = state.orbIndexByAgentId[agent.agentId] ?: 0,
            onClick = { onOpenAgent(agent.agentId) },
        )
    }
}

/**
 * Counters. Only the tile whose *value* is a point on the series it draws keeps
 * a chart: "active today" is one sample of "agents active per day", so the bars
 * are that metric's own history. Totals (agents, conversations) and the live
 * run count have no honest series behind them, so they show no chart rather
 * than borrowing an unrelated one.
 */
@Composable
private fun FleetStatTiles(summary: FleetSummary) {
    // height(IntrinsicSize.Max) + fillMaxHeight: every tile matches the
    // tallest one and footers pin to a common bottom edge — mixed tile
    // heights made the strip look broken.
    Row(
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
    ) {
        FleetStatTile(
            label = "Agents",
            value = summary.agentsLabel,
            caption = "roster truncated by backend".takeIf { summary.rosterTruncated },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        FleetStatTile(
            label = "Conversations",
            value = summary.totalConversations.toString(),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        FleetStatTile(
            label = "Active today",
            value = summary.activeToday.toString(),
            series = summary.agentsActiveByDay,
            caption = "agents active · ${summary.agentsActiveByDay.size}d",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        FleetStatTile(
            label = "Running now",
            value = summary.runningNow.toString(),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun FleetStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    series: List<Int>? = null,
    caption: String? = null,
) {
    Column(
        // background(shape) + border(shape), NO clip: clipping then bordering
        // the same rounded shape at fractional Windows DPI cuts the stroke to
        // sub-pixel on straight edges — the outline appeared only at the
        // corners. Nothing here needs clipping (no ripple, no overflow).
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(LettaDimens.Space.sm))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Footer pins to the shared bottom edge across all tiles.
        Spacer(Modifier.weight(1f))
        if (series != null) {
            FleetBarSpark(series, Modifier.fillMaxWidth().height(LettaDimens.Space.xl))
        }
        if (caption != null) {
            Spacer(Modifier.height(LettaDimens.Space.xs))
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun FleetTableHeader(sort: FleetSort, onSortKeySelected: (FleetSortKey) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = LettaDimens.Space.sm, bottom = LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortHeaderCell(FleetSortKey.Agent, sort, onSortKeySelected, Modifier.weight(1f))
            SortHeaderCell(
                FleetSortKey.Model,
                sort,
                onSortKeySelected,
                Modifier.width(MODEL_WIDTH),
            )
            SortHeaderCell(
                key = FleetSortKey.Conversations,
                sort = sort,
                onSortKeySelected = onSortKeySelected,
                modifier = Modifier.width(COUNT_WIDTH),
                alignEnd = true,
            )
            SortHeaderCell(
                key = FleetSortKey.LastActivity,
                sort = sort,
                onSortKeySelected = onSortKeySelected,
                modifier = Modifier.width(TIME_WIDTH),
                alignEnd = true,
            )
            Text(
                text = "LAST 48H",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier.width(SPARK_WIDTH),
            )
        }
        RowHairline()
    }
}

@Composable
private fun SortHeaderCell(
    key: FleetSortKey,
    sort: FleetSort,
    onSortKeySelected: (FleetSortKey) -> Unit,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
) {
    val active = sort.key == key
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable { onSortKeySelected(key) }
            .padding(vertical = LettaDimens.Space.xs, horizontal = LettaDimens.Space.hair),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (alignEnd) {
            Arrangement.spacedBy(LettaDimens.Space.xs, Alignment.End)
        } else {
            Arrangement.spacedBy(LettaDimens.Space.xs)
        },
    ) {
        Text(
            text = key.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (active) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (active) {
            Text(
                text = if (sort.descending) "▼" else "▲",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RowHairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline)),
    )
}

@Composable
private fun FleetAgentRow(
    agent: FleetAgentStat,
    orbIndex: Int,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onClick)
                .padding(vertical = LettaDimens.Space.sm, horizontal = LettaDimens.Space.hair),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FleetAgentIdentity(agent = agent, orbIndex = orbIndex, modifier = Modifier.weight(1f))
            FleetCellText(
                text = agent.model?.substringAfterLast('/') ?: "—",
                modifier = Modifier.width(MODEL_WIDTH),
            )
            FleetCellText(
                text = agent.conversationCount.toString(),
                modifier = Modifier.width(COUNT_WIDTH),
                strong = agent.conversationCount > 0,
                align = TextAlign.End,
            )
            FleetCellText(
                text = agent.lastActivity?.let { relativeAge(it) } ?: "—",
                modifier = Modifier.width(TIME_WIDTH),
                align = TextAlign.End,
            )
            Box(
                modifier = Modifier.width(SPARK_WIDTH),
                contentAlignment = Alignment.CenterEnd,
            ) {
                FleetBarSpark(
                    values = agent.activityByHour,
                    modifier = Modifier.width(60.dp).height(LettaDimens.Space.lg),
                )
            }
        }
        RowHairline()
    }
}

@Composable
private fun FleetAgentIdentity(
    agent: FleetAgentStat,
    orbIndex: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        AgentOrb(agentId = agent.agentId, index = orbIndex, size = LettaDimens.Orb.sm, cornerRadius = LettaDimens.Radius.sm)
        Text(
            text = agent.name,
            // An unresolved name is the raw backend id; render it as the
            // technical fallback it is instead of dressing it up as a title.
            style = if (agent.nameIsIdFallback) {
                MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            fontWeight = if (agent.nameIsIdFallback) FontWeight.Normal else FontWeight.Medium,
            color = if (agent.nameIsIdFallback) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        // Only running agents get a dot; "idle" is the absence of a marker, not
        // a grey badge on every row.
        if (agent.running) RunningDot()
    }
}

@Composable
private fun RunningDot() {
    Box(
        modifier = Modifier
            .size(LettaDimens.Space.sm)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary),
    )
}

@Composable
private fun FleetCellText(
    text: String,
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (strong) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        modifier = modifier,
    )
}

private val MODEL_WIDTH = 150.dp
private val COUNT_WIDTH = 56.dp
private val TIME_WIDTH = 84.dp
private val SPARK_WIDTH = 72.dp
