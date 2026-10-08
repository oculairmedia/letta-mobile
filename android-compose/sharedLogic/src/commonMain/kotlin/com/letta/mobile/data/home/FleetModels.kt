package com.letta.mobile.data.home

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.Conversation
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/**
 * Pure, renderer-agnostic fleet model behind the shared Home page (letta-mobile-c3np7.3.11).
 *
 * Everything the fleet half of Home draws is derived here from state the host already holds (its
 * conversation list, the agent roster and the "who is running" set) - no new repositories and no
 * network calls. Keeping the aggregation free of Compose keeps it unit-testable and is the hand-off
 * point for a Home page expressed by a Letta Code mod as an A2UI document.
 */

/** Number of trailing days the fleet-wide activity series covers. */
const val FLEET_ACTIVITY_DAYS: Int = 14

/**
 * Number of trailing hours the per-agent row series covers. At hour granularity each bar is one
 * conversation update, so a row reads as an event strip instead of a fake trend.
 */
const val FLEET_ACTIVITY_HOURS: Int = 48

/** Rows shown in the recent-conversations list before it stops. */
const val FLEET_RECENT_LIMIT: Int = 12

/** One conversation as the host knows it - the only input the fleet model needs from a chat list. */
@Immutable
data class FleetConversation(
    val id: String,
    val agentId: String?,
    val agentName: String,
    val title: String,
    val preview: String,
    /** ISO-8601 update time, or a sentinel label ("Queued") for local rows. */
    val updatedAtLabel: String,
)

/** One agent's row in the fleet table. */
@Immutable
data class FleetAgentStat(
    val agentId: String,
    val name: String,
    /** Backend model handle, when the roster knows it. */
    val model: String?,
    val conversationCount: Int,
    /** Most recent conversation update, or null when the agent has no chats. */
    val lastActivity: Instant?,
    /** True while this agent is mid-run (thinking / streaming / spawned task). */
    val running: Boolean,
    /** Conversation updates per day, oldest -> newest, length [FLEET_ACTIVITY_DAYS]. */
    val activityByDay: List<Int>,
    /** Conversation updates per hour, oldest -> newest, length [FLEET_ACTIVITY_HOURS]. */
    val activityByHour: List<Int> = emptyList(),
) {
    /** True when the row fell back to the raw `agent-...` id because no display name was found. */
    val nameIsIdFallback: Boolean get() = name == agentId

    /** The model's short name ("gpt-4.1" for "openai/gpt-4.1"), or a dash when unknown. */
    val modelLabel: String get() = model?.substringAfterLast('/') ?: "—"
}

/** One row of the fleet-wide "recent conversations" list. */
@Immutable
data class FleetRecentConversation(
    val conversationId: String,
    val agentId: String?,
    val agentName: String,
    val title: String,
    val preview: String,
    /** Parsed update time when the label was ISO-8601, else null. */
    val updatedAt: Instant?,
    /** Raw label, kept so the UI can fall back to it for non-ISO sentinels. */
    val updatedAtLabel: String,
)

/** The header strip: fleet-wide counters plus a fleet-wide activity series. */
@Immutable
data class FleetSummary(
    val totalAgents: Int = 0,
    val totalConversations: Int = 0,
    val activeToday: Int = 0,
    val runningNow: Int = 0,
    /** Fleet-wide conversation updates per day, oldest -> newest. */
    val conversationsByDay: List<Int> = emptyList(),
    /** Distinct agents that were active per day, oldest -> newest. */
    val agentsActiveByDay: List<Int> = emptyList(),
    /** The roster fetch was cut short by broken backend pagination: agent counts are lower bounds. */
    val rosterTruncated: Boolean = false,
    /** Only the first page of conversations is loaded: the conversation count is a lower bound. */
    val conversationsTruncated: Boolean = false,
)

/** "22" when the roster is complete, "22+" when it is a known lower bound. */
val FleetSummary.agentsLabel: String
    get() = if (rosterTruncated) "$totalAgents+" else totalAgents.toString()

/** "50" when every conversation is loaded, "50+" when more pages exist. */
val FleetSummary.conversationsLabel: String
    get() = if (conversationsTruncated) "$totalConversations+" else totalConversations.toString()

/** Run state is spelled out by the RUNNING NOW tile, so the subtitle keeps to the two counts. */
val FleetSummary.subtitle: String
    get() = "$agentsLabel agents · $conversationsLabel conversations"

/** Everything the fleet half of the Home page renders. */
@Immutable
data class FleetOverview(
    val summary: FleetSummary = FleetSummary(),
    val agents: List<FleetAgentStat> = emptyList(),
    /** Fleet-wide conversations, newest first. */
    val recent: List<FleetRecentConversation> = emptyList(),
)

/** User-selectable sort criteria for the fleet table. */
enum class FleetSortKey(val label: String) {
    Agent("Agent"),
    Model("Model"),
    Conversations("Chats"),
    LastActivity("Last activity"),
}

@Immutable
data class FleetSort(
    val key: FleetSortKey = FleetSortKey.LastActivity,
    val descending: Boolean = true,
)

/**
 * Header-click semantics: clicking the active column flips direction, clicking a different column
 * adopts that column's natural direction (names read A-Z, counts and recency biggest/newest first).
 */
fun FleetSort.toggled(next: FleetSortKey): FleetSort =
    if (next == key) copy(descending = !descending) else FleetSort(next, next.defaultDescending())

private fun FleetSortKey.defaultDescending(): Boolean = when (this) {
    FleetSortKey.Agent, FleetSortKey.Model -> false
    FleetSortKey.Conversations, FleetSortKey.LastActivity -> true
}

/**
 * Sorts the fleet. Agents never active sort last under [FleetSortKey.LastActivity] in either
 * direction (an absent timestamp is "unknown", not "oldest"), and name is always the stable
 * tie-break so the table never reshuffles between recompositions.
 */
fun sortFleet(agents: List<FleetAgentStat>, sort: FleetSort): List<FleetAgentStat> {
    val byName = compareBy<FleetAgentStat> { it.name.lowercase() }.thenBy { it.agentId }
    if (sort.key == FleetSortKey.LastActivity) {
        val (dated, undated) = agents.partition { it.lastActivity != null }
        val sortedDated = dated.sortedWith(compareBy<FleetAgentStat> { it.lastActivity }.then(byName))
        val ordered = if (sort.descending) sortedDated.reversed() else sortedDated
        return ordered + undated.sortedWith(byName)
    }
    val comparator = when (sort.key) {
        FleetSortKey.Model -> compareBy<FleetAgentStat> { it.model.orEmpty().lowercase() }.then(byName)
        FleetSortKey.Conversations -> compareBy<FleetAgentStat> { it.conversationCount }.then(byName)
        else -> byName
    }
    val ordered = agents.sortedWith(comparator)
    return if (sort.descending) ordered.reversed() else ordered
}

/** Inputs for [buildFleetOverview] - all already live in the host. */
data class FleetOverviewParams(
    val conversations: List<FleetConversation>,
    val rosterAgents: List<Agent>,
    /** Agent ids currently mid-run (thinking conversation + active subagents). */
    val runningAgentIds: Set<String> = emptySet(),
    val now: Instant = Clock.System.now(),
    val zone: TimeZone = TimeZone.currentSystemDefault(),
    val days: Int = FLEET_ACTIVITY_DAYS,
    val hours: Int = FLEET_ACTIVITY_HOURS,
    /** How many rows the recent-conversations list keeps. */
    val recentLimit: Int = FLEET_RECENT_LIMIT,
    /** Roster fetch was cut short (backend ignores pagination). */
    val rosterTruncated: Boolean = false,
    /** Only the first page of conversations is loaded. */
    val conversationsTruncated: Boolean = false,
)

/**
 * Folds the host's conversation list and agent roster into the fleet model. The agent set is the
 * union of the roster and every agent referenced by a conversation, so roster-only agents (no
 * chats yet) and conversation-only agents (roster not yet refreshed) both get a row.
 */
fun buildFleetOverview(params: FleetOverviewParams): FleetOverview {
    val conversations = params.conversations.distinctBy { it.id }
    val window = ActivityWindow(params)
    val rosterById = params.rosterAgents.associateBy { it.id.value }
    val conversationsByAgent = conversations
        .mapNotNull { conversation -> conversation.agentId?.takeIf { it.isNotBlank() }?.let { it to conversation } }
        .groupBy({ it.first }, { it.second })
    val agentIds = LinkedHashSet<String>().apply {
        addAll(params.rosterAgents.map { it.id.value })
        addAll(conversationsByAgent.keys)
    }
    val agents = agentIds.map { agentId ->
        window.agentStat(agentId, rosterById[agentId], conversationsByAgent[agentId].orEmpty())
    }
    return FleetOverview(
        summary = fleetSummary(agents, conversations.size, window, params),
        agents = agents,
        recent = buildRecentConversations(
            conversations = conversations,
            nameByAgentId = agents.associate { it.agentId to it.name },
            limit = params.recentLimit,
        ),
    )
}

/** The activity window every agent's buckets share, and who is running right now. */
private class ActivityWindow(params: FleetOverviewParams) {
    val days = params.days.coerceAtLeast(1)
    val hours = params.hours.coerceAtLeast(1)
    val now = params.now
    val zone = params.zone
    val today: LocalDate = params.now.toLocalDateTime(params.zone).date
    private val runningAgentIds = params.runningAgentIds

    fun agentStat(agentId: String, roster: Agent?, conversations: List<FleetConversation>): FleetAgentStat {
        val dayBuckets = IntArray(days)
        val hourBuckets = IntArray(hours)
        val instants = conversations.mapNotNull { parseConversationInstant(it.updatedAtLabel) }
        instants.forEach { at ->
            dayIndex(at)?.let { dayBuckets[it]++ }
            hourIndex(at)?.let { hourBuckets[it]++ }
        }
        return FleetAgentStat(
            agentId = agentId,
            name = resolveAgentName(agentId, roster, conversations),
            model = roster?.model?.takeIf { it.isNotBlank() },
            conversationCount = conversations.size,
            lastActivity = instants.maxOrNull(),
            running = agentId in runningAgentIds,
            activityByDay = dayBuckets.toList(),
            activityByHour = hourBuckets.toList(),
        )
    }

    fun dayIndex(at: Instant): Int? {
        val ago = at.toLocalDateTime(zone).date.daysUntil(today)
        return if (ago in 0 until days) days - 1 - ago else null
    }

    fun hourIndex(at: Instant): Int? {
        val ago = (now - at).inWholeHours
        return if (ago in 0 until hours) (hours - 1 - ago).toInt() else null
    }

    fun isToday(at: Instant): Boolean = at.toLocalDateTime(zone).date == today
}

private fun fleetSummary(
    agents: List<FleetAgentStat>,
    conversationCount: Int,
    window: ActivityWindow,
    params: FleetOverviewParams,
): FleetSummary {
    val conversationsByDay = IntArray(window.days)
    val agentsActiveByDay = IntArray(window.days)
    agents.forEach { agent ->
        agent.activityByDay.forEachIndexed { index, count ->
            conversationsByDay[index] += count
            if (count > 0) agentsActiveByDay[index]++
        }
    }
    return FleetSummary(
        totalAgents = agents.size,
        totalConversations = conversationCount,
        activeToday = agents.count { stat -> stat.lastActivity?.let(window::isToday) == true },
        runningNow = agents.count { it.running },
        conversationsByDay = conversationsByDay.toList(),
        agentsActiveByDay = agentsActiveByDay.toList(),
        rosterTruncated = params.rosterTruncated,
        conversationsTruncated = params.conversationsTruncated,
    )
}

/**
 * Fleet-wide conversations, newest first. Names come from the resolved fleet rows so the list
 * agrees with the table (a conversation carrying the raw agent id still shows the roster name).
 */
private fun buildRecentConversations(
    conversations: List<FleetConversation>,
    nameByAgentId: Map<String, String>,
    limit: Int,
): List<FleetRecentConversation> = conversations
    .sortedByDescending { conversationRecency(it.updatedAtLabel) }
    .take(limit.coerceAtLeast(0))
    .map { conversation ->
        FleetRecentConversation(
            conversationId = conversation.id,
            agentId = conversation.agentId,
            agentName = conversation.agentId?.let(nameByAgentId::get) ?: conversation.agentName.ifBlank { "Letta" },
            title = conversation.title,
            preview = conversation.preview.trim().takeUnless { it.equals("Loaded from backend", ignoreCase = true) }.orEmpty(),
            updatedAt = parseConversationInstant(conversation.updatedAtLabel),
            updatedAtLabel = conversation.updatedAtLabel,
        )
    }

/**
 * Recency key mirroring the shell's own ordering: locally queued rows are the newest thing there
 * is, and anything unparseable sorts to the bottom rather than being guessed at.
 */
fun conversationRecency(label: String): Instant =
    parseConversationInstant(label) ?: if (label == QUEUED_LABEL) Instant.DISTANT_FUTURE else Instant.DISTANT_PAST

private const val QUEUED_LABEL = "Queued"

/**
 * Target for the Home chatbox: the focused agent's most recent conversation when there is one,
 * otherwise the fleet's most recent. Null means "nothing to send into yet" and the caller stages
 * the text in the composer instead.
 */
fun preferredComposerConversationId(conversations: List<FleetConversation>, preferredAgentId: String?): String? {
    fun newest(list: List<FleetConversation>): String? = list.maxByOrNull { conversationRecency(it.updatedAtLabel) }?.id
    val focused = preferredAgentId?.let { id -> newest(conversations.filter { it.agentId == id }) }
    return focused ?: newest(conversations)
}

/**
 * The fleet model's view of a backend [Conversation] (hosts that list conversations straight from
 * the backend, like Android). Hidden (subagent / background) conversations map to null.
 */
fun Conversation.toFleetConversation(): FleetConversation? = if (hidden == true) {
    null
} else {
    FleetConversation(
        id = id.value,
        agentId = agentId.value,
        agentName = agentName.orEmpty(),
        title = summary?.takeIf { it.isNotBlank() } ?: UNTITLED_CONVERSATION,
        preview = "",
        updatedAtLabel = lastMessageAt ?: updatedAt ?: createdAt.orEmpty(),
    )
}

private const val UNTITLED_CONVERSATION = "Untitled conversation"

/** ISO-8601 conversation timestamps parse; sentinel labels ("Queued") carry no date and give null. */
fun parseConversationInstant(label: String): Instant? = runCatching { Instant.parse(label) }.getOrNull()

/** Prefer a real name over an id (conversations sometimes carry the raw id as their agent name). */
private fun resolveAgentName(agentId: String, roster: Agent?, conversations: List<FleetConversation>): String {
    roster?.name?.takeIf { it.isNotBlank() && it != agentId }?.let { return it }
    return conversations.firstNotNullOfOrNull { conversation ->
        conversation.agentName.takeIf { it.isNotBlank() && it != agentId }
    } ?: agentId
}

/** Compact age label ("now", "5m", "3h", "2d", "1w", "2mo") matching the rail timestamp idiom. */
fun relativeAge(at: Instant, now: Instant = Clock.System.now()): String {
    val seconds = (now - at).inWholeSeconds
    return when {
        seconds < SECONDS_PER_MINUTE -> "now"
        seconds < SECONDS_PER_HOUR -> "${seconds / SECONDS_PER_MINUTE}m"
        seconds < SECONDS_PER_DAY -> "${seconds / SECONDS_PER_HOUR}h"
        seconds < SECONDS_PER_WEEK -> "${seconds / SECONDS_PER_DAY}d"
        seconds < SECONDS_PER_MONTH -> "${seconds / SECONDS_PER_WEEK}w"
        else -> "${seconds / SECONDS_PER_MONTH}mo"
    }
}

/** [relativeAge] for a raw label; a label that is not an ISO instant is returned unchanged. */
fun relativeAgeLabel(label: String, now: Instant = Clock.System.now()): String =
    parseConversationInstant(label)?.let { relativeAge(it, now) } ?: label

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_WEEK = 604_800L
private const val SECONDS_PER_MONTH = 2_592_000L
