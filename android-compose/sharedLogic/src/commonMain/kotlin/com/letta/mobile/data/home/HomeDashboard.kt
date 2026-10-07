package com.letta.mobile.data.home

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentSearchMatcher
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.ParsedSearchMessage
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.model.Tool
import kotlin.math.roundToInt

/**
 * The mobile dashboard half of the shared Home page (letta-mobile-c3np7.3.11): pinned shortcuts and
 * agents, the per-backend stat widgets and the global search, all platform-neutral.
 */

/**
 * Every destination a Home shortcut tile can open. [name] is the persistence key and matches the
 * Android dashboard's existing pin keys, so pins survive the move to the shared page. Hosts list the
 * subset they can open in the page options; pins outside it are kept but not drawn.
 */
enum class HomeShortcut(val label: String, val description: String?, val group: Group) {
    CONVERSATIONS("Conversations", null, Group.PRIMARY),
    AGENTS("Agents", null, Group.PRIMARY),
    TOOLS("Tools", null, Group.PRIMARY),
    BLOCKS("Blocks", null, Group.PRIMARY),
    TEMPLATES("Starter Agents", "Browse starter agents", Group.SECONDARY),
    ARCHIVES("Archives", "View archived data", Group.SECONDARY),
    FOLDERS("Folders", "Organize content", Group.SECONDARY),
    GROUPS("Groups", "Manage agent groups", Group.SECONDARY),
    PROVIDERS("Providers", "Configure LLM providers", Group.SECONDARY),
    IDENTITIES("Identities", "Manage identities", Group.SECONDARY),
    SCHEDULES("Schedules", "Scheduled tasks", Group.SECONDARY),
    RUNS("Runs", "Monitor agent runs", Group.SECONDARY),
    JOBS("Jobs", "Background jobs", Group.SECONDARY),
    MESSAGE_BATCHES("Message Batches", "Batch operations", Group.SECONDARY),
    MCP_SERVERS("MCP Servers", "External tool servers", Group.SECONDARY),
    BOT_SETTINGS("Bot Settings", "Configure bot", Group.SECONDARY),
    PROJECTS("Projects", "Project workspace", Group.SECONDARY),
    MODELS("Models", "Browse LLM models", Group.SECONDARY),
    USAGE("Usage", "Token analytics", Group.SECONDARY),
    FAVORITE_AGENT("Favorite Agent", "Quick chat", Group.SECONDARY),
    SETTINGS("Settings", "App preferences", Group.UTILITY),
    TELEMETRY("Telemetry", "Dev latency/event log", Group.UTILITY),
    SYSTEM_ACCESS("System Access", "Device access registry", Group.UTILITY),
    ABOUT("About", "App info & version", Group.UTILITY),
    ;

    enum class Group { PRIMARY, SECONDARY, UTILITY }
}

/** An agent reference the host resolves (the favorite agent, a pinned agent). */
@Immutable
data class HomeAgentRef(val id: String, val name: String)

/** One tile of the pinned grid; [key] is the qualified persistence key ("shortcut:X" / "agent:Y"). */
@Immutable
sealed interface HomePinnedItem {
    val key: String

    @Immutable
    data class Shortcut(val shortcut: HomeShortcut) : HomePinnedItem {
        override val key: String get() = shortcutKey(shortcut)
    }

    @Immutable
    data class Agent(val agent: HomeAgentRef) : HomePinnedItem {
        override val key: String get() = agentKey(agent.id)
    }

    companion object {
        private const val SHORTCUT_PREFIX = "shortcut:"
        private const val AGENT_PREFIX = "agent:"

        fun shortcutKey(shortcut: HomeShortcut): String = SHORTCUT_PREFIX + shortcut.name

        fun agentKey(agentId: String): String = AGENT_PREFIX + agentId

        fun parseShortcutKey(key: String): HomeShortcut? =
            key.takeIf { it.startsWith(SHORTCUT_PREFIX) }
                ?.removePrefix(SHORTCUT_PREFIX)
                ?.let { name -> HomeShortcut.entries.firstOrNull { it.name == name } }

        fun parseAgentKey(key: String): String? = key.takeIf { it.startsWith(AGENT_PREFIX) }?.removePrefix(AGENT_PREFIX)
    }
}

/**
 * Resolves persisted pin [keys] into tiles. Shortcuts outside [availableShortcuts] are hidden (the
 * host cannot open them). An agent pin uses its live name from [agentNames]; while [agentsSettled] is
 * false a pin missing from it is still drawn under [persistedNames] so tiles do not flash away
 * mid-refresh, and once settled an unknown agent is an orphan from another backend and is hidden
 * (its key stays stored so switching back restores it).
 */
fun resolvePinnedItems(
    keys: List<String>,
    agentNames: Map<String, String>,
    agentsSettled: Boolean,
    availableShortcuts: Set<HomeShortcut> = HomeShortcut.entries.toSet(),
    persistedNames: Map<String, String> = emptyMap(),
): List<HomePinnedItem> = keys.distinct().mapNotNull { key ->
    HomePinnedItem.parseShortcutKey(key)?.let { shortcut ->
        return@mapNotNull HomePinnedItem.Shortcut(shortcut).takeIf { shortcut in availableShortcuts }
    }
    val agentId = HomePinnedItem.parseAgentKey(key) ?: return@mapNotNull null
    val name = agentNames[agentId] ?: persistedNames[agentId]?.takeUnless { agentsSettled }
    name?.let { HomePinnedItem.Agent(HomeAgentRef(agentId, it)) }
}

/** Token usage of one model over the usage window. */
@Immutable
data class ModelTokenUsage(val model: String, val totalTokens: Int, val sharePercent: Int)

/** Token usage over the trailing usage window, from sampled run steps. */
@Immutable
data class HomeUsageSummary(
    val totalTokens: Int,
    val averageTokensPerHour: Int,
    val sampledSteps: Int,
    val modelUsage: List<ModelTokenUsage> = emptyList(),
)

/** Folds run steps into a [HomeUsageSummary]; steps without a token count are skipped. */
object HomeUsageCalculator {
    private const val FALLBACK_MODEL_NAME = "Unknown model"
    private const val PERCENT = 100.0

    fun calculate(steps: List<Step>, windowHours: Int = USAGE_WINDOW_HOURS): HomeUsageSummary {
        val counted = steps.mapNotNull { step ->
            val tokens = step.totalTokens ?: ((step.promptTokens ?: 0) + (step.completionTokens ?: 0))
            if (tokens > 0) step to tokens else null
        }
        val total = counted.sumOf { it.second }
        val modelUsage = if (total <= 0) {
            emptyList()
        } else {
            counted
                .groupBy({ (step, _) -> step.model?.takeIf { it.isNotBlank() } ?: FALLBACK_MODEL_NAME }, { it.second })
                .map { (model, tokens) ->
                    val modelTotal = tokens.sum()
                    ModelTokenUsage(model, modelTotal, (modelTotal.toDouble() / total * PERCENT).roundToInt())
                }
                .sortedWith(compareByDescending<ModelTokenUsage> { it.totalTokens }.thenBy { it.model })
        }
        return HomeUsageSummary(
            totalTokens = total,
            averageTokensPerHour = if (windowHours > 0) (total.toDouble() / windowHours).roundToInt() else 0,
            sampledSteps = counted.size,
            modelUsage = modelUsage,
        )
    }
}

/** Hours the dashboard's usage summary covers. */
const val USAGE_WINDOW_HOURS: Int = 24

/**
 * Backend-wide counters the fleet model cannot derive from the conversation list. A null value
 * with [loading] false means the backend does not offer that figure, and its widget is hidden.
 */
@Immutable
data class HomeStats(
    val loading: Boolean = true,
    val toolCount: Int? = null,
    val blockCount: Int? = null,
    val usage: HomeUsageSummary? = null,
    val error: String? = null,
)

/** The searchable catalog the local half of Home search filters. */
@Immutable
data class HomeSearchCatalog(
    val agents: List<Agent> = emptyList(),
    val tools: List<Tool> = emptyList(),
    val blocks: List<Block> = emptyList(),
    /** False while the agent list is still loading; pins of unknown agents stay visible until then. */
    val agentsSettled: Boolean = false,
)

/** The search box and its results: local matches at once, message hits when the remote search returns. */
@Immutable
data class HomeSearchState(
    val query: String = "",
    val agents: List<Agent> = emptyList(),
    val tools: List<Tool> = emptyList(),
    val blocks: List<Block> = emptyList(),
    val messages: List<ParsedSearchMessage> = emptyList(),
    /** True while the remote message search for [query] is in flight. */
    val searchingMessages: Boolean = false,
) {
    val isActive: Boolean get() = query.isNotBlank()

    val isEmpty: Boolean get() = agents.isEmpty() && tools.isEmpty() && blocks.isEmpty() && messages.isEmpty()
}

/** The local (instant) part of Home search over [catalog]. */
fun searchHomeCatalog(catalog: HomeSearchCatalog, query: String): HomeSearchState {
    if (query.isBlank()) return HomeSearchState(query = query)
    val needle = query.trim().lowercase()
    fun String?.hit(): Boolean = this?.lowercase()?.contains(needle) == true
    return HomeSearchState(
        query = query,
        agents = AgentSearchMatcher.filter(catalog.agents, query),
        tools = catalog.tools.filter { it.name.hit() || it.description.hit() },
        blocks = catalog.blocks.filter { it.label.hit() || it.description.hit() || it.value.hit() },
    )
}

/** One counter tile on Home: the fleet's four, then whichever backend figures this host has. */
@Immutable
data class HomeStatTile(
    val label: String,
    val value: String,
    val caption: String? = null,
    /** Only a tile whose value is a point on this series draws it ("active today" of "active per day"). */
    val series: List<Int>? = null,
)

/**
 * The stat strip. Backend figures show a dash while loading and drop out once the backend is known
 * not to offer them, so a host never draws a tile that will stay empty.
 */
fun HomePageState.statTiles(): List<HomeStatTile> {
    val summary = fleet.summary
    val fleetTiles = listOf(
        HomeStatTile("Agents", summary.agentsLabel, caption = "roster truncated by backend".takeIf { summary.rosterTruncated }),
        HomeStatTile("Conversations", summary.conversationsLabel),
        HomeStatTile("Active today", summary.activeToday.toString(), "agents active · ${summary.agentsActiveByDay.size}d", summary.agentsActiveByDay),
        HomeStatTile("Running now", summary.runningNow.toString()),
    )
    val usage = stats.usage
    val backendTiles = listOfNotNull(
        backendTile("Tools", stats.toolCount?.let(::formatGroupedNumber)),
        backendTile("Blocks", stats.blockCount?.let(::formatGroupedNumber)),
        backendTile("Tokens · ${USAGE_WINDOW_HOURS}h", usage?.let { formatGroupedNumber(it.totalTokens) })
            ?.copy(caption = usage?.let(::usageCaption)),
    )
    return fleetTiles + backendTiles
}

private fun HomePageState.backendTile(label: String, value: String?): HomeStatTile? = when {
    value != null -> HomeStatTile(label, value)
    stats.loading -> HomeStatTile(label, "—")
    else -> null
}

private fun usageCaption(usage: HomeUsageSummary): String {
    val rate = "~${formatGroupedNumber(usage.averageTokensPerHour)}/h"
    val top = usage.modelUsage.firstOrNull() ?: return rate
    return "$rate · ${top.model.substringAfterLast('/')} ${top.sharePercent}%"
}

/** "12,345" - thousands grouped with commas, platform-neutral. */
fun formatGroupedNumber(value: Int): String {
    val digits = kotlin.math.abs(value.toLong()).toString()
    val grouped = digits.reversed().chunked(DIGIT_GROUP).joinToString(",").reversed()
    return if (value < 0) "-$grouped" else grouped
}

private const val DIGIT_GROUP = 3
