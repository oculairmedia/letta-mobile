package com.letta.mobile.ui.shell.pages.home

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.letta.mobile.data.home.FleetRecentConversation
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.util.time.TimeLabels
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.list.RosterListRow
import com.letta.mobile.ui.components.list.RosterRowSlots
import com.letta.mobile.ui.components.list.RosterRowState
import com.letta.mobile.ui.mascot.AgentAvatar
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/**
 * Recent conversations, the body of Home at every width: newest first under Today / Yesterday /
 * Previous 7 days / Earlier, each row the agent's avatar, the title, "agent · last message" and
 * its age. A phone shows the latest few; a wide window everything the fleet aggregate carries.
 */
internal fun LazyListScope.homeRecents(page: HomePageScope) {
    item(key = "recents-header") { SeeAllHeader(RECENT_LABEL, HomeShortcut.CONVERSATIONS, page, Modifier.testTag(HomePageTags.RECENTS)) }
    val limit = if (page.wide) WIDE_RECENT_LIMIT else COMPACT_RECENT_LIMIT
    val groups = recentDayGroups(page.state.fleet.recent.take(limit))
    if (groups.isEmpty()) item(key = "recents-empty") { HomeEmptyLine(NO_CONVERSATIONS) }
    groups.forEach { group ->
        item(key = "recents-${group.day.name}") { RecentDayLabel(group.day) }
        items(items = group.conversations, key = { "recent-${it.conversationId}" }) { conversation ->
            RecentRow(conversation, page)
        }
    }
}

@Composable
private fun RecentDayLabel(day: RecentDay) {
    Text(
        text = day.label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = LettaDimens.Space.sm, top = LettaDimens.Space.xs).testTag(HomePageTags.recentDay(day.name)),
    )
}

@Composable
private fun RecentRow(conversation: FleetRecentConversation, page: HomePageScope) {
    val subtitle = listOf(conversation.agentName, conversation.preview).filter { it.isNotBlank() }.joinToString(" · ")
    RosterListRow(
        spec = LettaListRowSpec(
            title = conversation.title,
            trailing = TimeLabels.ageLabel(conversation.updatedAtLabel),
            subtitle = subtitle.ifBlank { null },
        ),
        row = RosterRowState(onClick = { page.navigation.onOpenConversation(conversation) }),
        slots = RosterRowSlots(leading = { AgentAvatar(conversation.agentId, conversation.agentName, LettaDimens.Orb.md) }),
        modifier = Modifier.testTag(HomePageTags.recent(conversation)),
    )
}

/** The day buckets of the recents list, in display order. */
internal enum class RecentDay(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    WEEK("Previous 7 days"),
    EARLIER("Earlier"),
}

internal class RecentDayGroup(val day: RecentDay, val conversations: List<FleetRecentConversation>)

/**
 * [recent] bucketed by local calendar day, keeping its order inside each bucket. An undated row (a
 * local "Queued" one) is today's: it is the newest thing the user did.
 */
internal fun recentDayGroups(
    recent: List<FleetRecentConversation>,
    now: Instant = Clock.System.now(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
): List<RecentDayGroup> {
    val today = now.toLocalDateTime(zone).date
    return recent
        .groupBy { conversation ->
            conversation.updatedAt?.let { recentDay(it.toLocalDateTime(zone).date.daysUntil(today)) } ?: RecentDay.TODAY
        }
        .entries
        .sortedBy { it.key.ordinal }
        .map { RecentDayGroup(it.key, it.value) }
}

private fun recentDay(daysAgo: Int): RecentDay = when {
    daysAgo <= 0 -> RecentDay.TODAY
    daysAgo == 1 -> RecentDay.YESTERDAY
    daysAgo < DAYS_PER_WEEK -> RecentDay.WEEK
    else -> RecentDay.EARLIER
}

private const val RECENT_LABEL = "Recent"
private const val COMPACT_RECENT_LIMIT = 6
private const val WIDE_RECENT_LIMIT = 12
private const val DAYS_PER_WEEK = 7
