package com.letta.mobile.desktop.home

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.chat.runtime.displayTitle
import com.letta.mobile.data.model.Agent
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Pure, renderer-agnostic fleet model behind the Home dashboard.
 *
 * Everything the Home page draws is derived here from state the desktop shell
 * already holds (the conversation list + the agent roster + the live
 * "who is running" set) — no new repositories, no new network calls. Keeping
 * the aggregation free of Compose is deliberate: it is unit-testable, and it is
 * the hand-off point for the intended end state where the page itself is
 * *expressed* by the letta-code plugin as an A2UI document rather than
 * hardcoded (see [com.letta.mobile.desktop.home.DesktopHomeSurface]).
 */

/** Number of trailing days the fleet-wide activity series covers. */
const val FLEET_ACTIVITY_DAYS: Int = 14

/**
 * Number of trailing hours the per-agent row series covers.
 *
 * Per-row day buckets were near-useless: an agent's conversations almost always
 * land inside one or two days, so every row drew the same flat line with a spike
 * at the right edge. At hour granularity each bar is one conversation update, so
 * the row reads as an event strip ("when did this agent actually work") instead
 * of a fake trend.
 */
const val FLEET_ACTIVITY_HOURS: Int = 48

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
    /**
     * True when name resolution never found a display name and the row is
     * falling back to the raw `agent-…` id. The table renders these in a
     * technical style so they read as "not named yet", not as a broken row.
     */
    val nameIsIdFallback: Boolean get() = name == agentId
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
    val totalAgents: Int,
    val totalConversations: Int,
    val activeToday: Int,
    val runningNow: Int,
    /** Fleet-wide conversation updates per day, oldest -> newest. */
    val conversationsByDay: List<Int>,
    /** Distinct agents that were active per day, oldest -> newest. */
    val agentsActiveByDay: List<Int>,
)

/** Everything the Home page renders. */
@Immutable
data class FleetOverview(
    val summary: FleetSummary,
    val agents: List<FleetAgentStat>,
    /** Fleet-wide conversations, newest first — the page's primary content. */
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
 * Header-click semantics: clicking the active column flips direction, clicking
 * a different column adopts that column's natural direction (names read A-Z,
 * counts and recency read biggest/newest first).
 */
fun FleetSort.toggled(next: FleetSortKey): FleetSort =
    if (next == key) copy(descending = !descending) else FleetSort(next, next.defaultDescending())

private fun FleetSortKey.defaultDescending(): Boolean = when (this) {
    FleetSortKey.Agent, FleetSortKey.Model -> false
    FleetSortKey.Conversations, FleetSortKey.LastActivity -> true
}

/**
 * Sorts the fleet. Agents that have never been active sort last under
 * [FleetSortKey.LastActivity] in either direction (an absent timestamp is not
 * "oldest", it is "unknown"), and name is always the stable tie-break so the
 * table never reshuffles between recompositions.
 */
fun sortFleet(agents: List<FleetAgentStat>, sort: FleetSort): List<FleetAgentStat> {
    val byName = compareBy<FleetAgentStat> { it.name.lowercase() }.thenBy { it.agentId }
    val ordered = when (sort.key) {
        FleetSortKey.Agent -> agents.sortedWith(byName)
        FleetSortKey.Model -> agents.sortedWith(
            compareBy<FleetAgentStat> { it.model.orEmpty().lowercase() }.then(byName),
        )
        FleetSortKey.Conversations -> agents.sortedWith(
            compareBy<FleetAgentStat> { it.conversationCount }.then(byName),
        )
        FleetSortKey.LastActivity -> {
            val (dated, undated) = agents.partition { it.lastActivity != null }
            val sortedDated = dated.sortedWith(
                compareBy<FleetAgentStat> { it.lastActivity!!.toEpochMilli() }.then(byName),
            )
            return if (sort.descending) {
                sortedDated.reversed() + undated.sortedWith(byName)
            } else {
                sortedDated + undated.sortedWith(byName)
            }
        }
    }
    return if (sort.descending) ordered.reversed() else ordered
}

/** Inputs for [buildFleetOverview] — all already live in the desktop shell. */
data class FleetOverviewParams(
    val conversations: List<DesktopConversationSummary>,
    val rosterAgents: List<Agent>,
    /** Agent ids currently mid-run (thinking conversation + active subagents). */
    val runningAgentIds: Set<String> = emptySet(),
    val now: Instant = Instant.now(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val days: Int = FLEET_ACTIVITY_DAYS,
    val hours: Int = FLEET_ACTIVITY_HOURS,
    /** How many rows the recent-conversations list keeps. */
    val recentLimit: Int = FLEET_RECENT_LIMIT,
)

/** Rows shown in the recent-conversations list before it stops. */
const val FLEET_RECENT_LIMIT: Int = 12

/**
 * Folds the shell's conversation list and agent roster into the fleet model.
 *
 * The agent set is the union of the roster and every agent referenced by a
 * conversation, so roster-only agents (bulk-imported, no chats yet) and
 * conversation-only agents (roster not yet refreshed) both get a row.
 */
fun buildFleetOverview(params: FleetOverviewParams): FleetOverview {
    val days = params.days.coerceAtLeast(1)
    val hours = params.hours.coerceAtLeast(1)
    val today = LocalDate.ofInstant(params.now, params.zone)
    val rosterById = params.rosterAgents.associateBy { it.id.value }
    val conversationsByAgent = params.conversations
        .filter { !it.agentId.isNullOrBlank() }
        .groupBy { it.agentId!! }

    val agentIds = LinkedHashSet<String>().apply {
        addAll(params.rosterAgents.map { it.id.value })
        addAll(conversationsByAgent.keys)
    }

    val agents = agentIds.map { agentId ->
        val convs = conversationsByAgent[agentId].orEmpty()
        val buckets = MutableList(days) { 0 }
        val hourBuckets = MutableList(hours) { 0 }
        var last: Instant? = null
        convs.forEach { conversation ->
            val at = parseConversationInstant(conversation.updatedAtLabel) ?: return@forEach
            if (last == null || at.isAfter(last)) last = at
            val index = dayBucketIndex(at, today, params.zone, days)
            if (index != null) buckets[index] = buckets[index] + 1
            val hourIndex = hourBucketIndex(at, params.now, hours)
            if (hourIndex != null) hourBuckets[hourIndex] = hourBuckets[hourIndex] + 1
        }
        FleetAgentStat(
            agentId = agentId,
            name = resolveAgentName(agentId, rosterById[agentId], convs),
            model = rosterById[agentId]?.model?.takeIf { it.isNotBlank() },
            conversationCount = convs.size,
            lastActivity = last,
            running = agentId in params.runningAgentIds,
            activityByDay = buckets,
            activityByHour = hourBuckets,
        )
    }

    val conversationsByDay = MutableList(days) { 0 }
    val agentsActiveByDay = MutableList(days) { 0 }
    agents.forEach { agent ->
        agent.activityByDay.forEachIndexed { index, count ->
            conversationsByDay[index] = conversationsByDay[index] + count
            if (count > 0) agentsActiveByDay[index] = agentsActiveByDay[index] + 1
        }
    }

    return FleetOverview(
        summary = FleetSummary(
            totalAgents = agents.size,
            totalConversations = params.conversations.size,
            activeToday = agents.count { stat ->
                stat.lastActivity?.let { LocalDate.ofInstant(it, params.zone) == today } == true
            },
            runningNow = agents.count { it.running },
            conversationsByDay = conversationsByDay,
            agentsActiveByDay = agentsActiveByDay,
        ),
        agents = agents,
        recent = buildRecentConversations(
            conversations = params.conversations,
            nameByAgentId = agents.associate { it.agentId to it.name },
            limit = params.recentLimit,
        ),
    )
}

/**
 * Fleet-wide conversations, newest first. Names are taken from the resolved
 * fleet rows so the list agrees with the table below it (a conversation that
 * carries the raw agent id as its `agentName` still shows the roster name).
 */
private fun buildRecentConversations(
    conversations: List<DesktopConversationSummary>,
    nameByAgentId: Map<String, String>,
    limit: Int,
): List<FleetRecentConversation> = conversations
    .sortedByDescending { conversationRecency(it.updatedAtLabel) }
    .take(limit.coerceAtLeast(0))
    .map { conversation ->
        FleetRecentConversation(
            conversationId = conversation.id,
            agentId = conversation.agentId,
            agentName = conversation.agentId?.let(nameByAgentId::get)
                ?: conversation.agentName.ifBlank { "Letta" },
            title = conversation.displayTitle(),
            preview = conversation.lastMessagePreview
                .trim()
                .takeUnless { it.equals("Loaded from backend", ignoreCase = true) }
                .orEmpty(),
            updatedAt = parseConversationInstant(conversation.updatedAtLabel),
            updatedAtLabel = conversation.updatedAtLabel,
        )
    }

/**
 * Recency key mirroring the shell's own ordering: locally queued rows are the
 * newest thing there is, and anything unparseable sorts to the bottom rather
 * than being guessed at.
 */
internal fun conversationRecency(label: String): Instant =
    parseConversationInstant(label)
        ?: if (label == "Queued") Instant.MAX else Instant.MIN

/**
 * Target for the Home chatbox: the focused agent's most recent conversation
 * when there is one, otherwise the fleet's most recent. Null means "nothing to
 * send into yet" and the caller stages the text in the composer instead.
 */
fun preferredComposerConversationId(
    conversations: List<DesktopConversationSummary>,
    preferredAgentId: String?,
): String? {
    val newest = { list: List<DesktopConversationSummary> ->
        list.maxByOrNull { conversationRecency(it.updatedAtLabel) }?.id
    }
    val focused = preferredAgentId?.let { id ->
        newest(conversations.filter { it.agentId == id })
    }
    return focused ?: newest(conversations)
}

/**
 * Conversation timestamps arrive as ISO-8601 strings, but the shell also uses
 * sentinel labels ("Queued", pre-formatted text) for local rows — those carry
 * no usable date and are excluded from the activity series rather than guessed.
 */
internal fun parseConversationInstant(label: String): Instant? =
    runCatching { Instant.parse(label) }.getOrNull()

/** Index into an oldest-first window of [days] ending today, or null if outside it. */
private fun dayBucketIndex(at: Instant, today: LocalDate, zone: ZoneId, days: Int): Int? {
    val date = LocalDate.ofInstant(at, zone)
    val ago = ChronoUnit.DAYS.between(date, today)
    if (ago < 0 || ago >= days) return null
    return (days - 1 - ago).toInt()
}

/** Index into an oldest-first window of [hours] ending now, or null if outside it. */
private fun hourBucketIndex(at: Instant, now: Instant, hours: Int): Int? {
    val ago = ChronoUnit.HOURS.between(at, now)
    if (ago < 0 || ago >= hours) return null
    return (hours - 1 - ago).toInt()
}

/**
 * Prefer a real name over an id: conversations sometimes carry the raw agent id
 * as `agentName` when name resolution missed at load time, and the roster
 * usually has the display name by then (mirrors `buildRailAgents`).
 */
private fun resolveAgentName(
    agentId: String,
    roster: Agent?,
    conversations: List<DesktopConversationSummary>,
): String {
    roster?.name?.takeIf { it.isNotBlank() && it != agentId }?.let { return it }
    conversations.firstNotNullOfOrNull { conversation ->
        conversation.agentName.takeIf { it.isNotBlank() && it != agentId }
    }?.let { return it }
    return agentId
}

/** Deterministic UTC helper for tests and for any headless rendering path. */
internal val FLEET_UTC: ZoneId = ZoneOffset.UTC

/**
 * Compact age label ("now", "5m", "3h", "2d") matching the rail/sidebar
 * timestamp idiom, but taking an already-parsed instant.
 */
internal fun relativeAge(at: Instant, now: Instant = Instant.now()): String {
    val seconds = java.time.Duration.between(at, now).seconds
    return when {
        seconds < 60 -> "now"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3_600}h"
        seconds < 604_800 -> "${seconds / 86_400}d"
        seconds < 2_592_000 -> "${seconds / 604_800}w"
        else -> "${seconds / 2_592_000}mo"
    }
}
