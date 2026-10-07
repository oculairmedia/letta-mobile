package com.letta.mobile.ui.shell.rail

import androidx.compose.runtime.Immutable
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.agents.AgentRailGroup
import com.letta.mobile.data.model.DisplayNames
import kotlin.time.Instant

/** What an orb's hover card says: when the agent last spoke (raw ISO-8601, the host formats it) and what it said. */
@Immutable
data class ShellRailActivity(val updatedAtLabel: String, val preview: String)

/** Which agent is focused or working, and how each agent looks, keyed by agent id. */
@Immutable
data class ShellRailFocus(
    val selectedAgentId: String? = null,
    val thinkingAgentId: String? = null,
    val avatarStyleByAgentId: Map<String, Int> = emptyMap(),
    /** Agents with a mascot identity draw their silhouette in their colour instead of the gradient orb. */
    val identityByAgentId: Map<String, MascotIdentity> = emptyMap(),
    /** Latest conversation per agent, for the hover card. */
    val activityByAgentId: Map<String, ShellRailActivity> = emptyMap(),
    /** Agents the user pinned; an orb's menu offers to unpin them. */
    val pinnedAgentIds: Set<String> = emptySet(),
)

/** One orb on the rail: a stack of agents that share a name, resolved for display. */
@Immutable
data class ShellRailEntry(
    /** Stable key (the group's name). */
    val key: String,
    val name: String,
    /** The agent a click opens: the stack's selected member, else its first (most recent). */
    val agentId: String,
    val orbStyle: Int,
    val memberCount: Int = 1,
    val selected: Boolean = false,
    val thinking: Boolean = false,
    val identity: MascotIdentity? = null,
    val activity: ShellRailActivity? = null,
    /** Whether [agentId] is pinned. */
    val pinned: Boolean = false,
) {
    /** The letter a gradient orb shows. */
    val initial: String get() = name.firstOrNull()?.uppercase() ?: "?"

    /** Name, member count and working state, for the hover card's title. */
    val tooltip: String
        get() = buildString {
            append(name)
            if (memberCount > 1) append(" · $memberCount agents")
            if (thinking) append(" · thinking…")
        }
}

/** The rail: its orbs, whether Home is the open page, and whether it is expanded to show names. */
@Immutable
data class ShellAgentRailState(
    val entries: List<ShellRailEntry>,
    val homeSelected: Boolean = false,
    val expanded: Boolean = false,
    /** Agents the recents cut left off the rail; while non-zero the rail offers "All agents". */
    val hiddenAgentCount: Int = 0,
)

/**
 * What the rail asks its host to do. The orb menu (desktop: right-click, touch: long-press) always
 * offers Open; Pin / Unpin and Agent settings show when the host supplies them (null: it cannot).
 * [onShowAllAgents] opens the host's full agent list, reached from the rail's "+N" control.
 */
data class ShellAgentRailActions(
    val onAgentSelected: (String) -> Unit = {},
    val onHome: () -> Unit = {},
    val onNewSession: () -> Unit = {},
    val onAgentPinnedChange: ((agentId: String, pinned: Boolean) -> Unit)? = null,
    val onAgentSettings: ((agentId: String) -> Unit)? = null,
    val onShowAllAgents: (() -> Unit)? = null,
)

/** Pure mapping from the agent roster to rail orbs. */
object ShellRailMapping {
    /**
     * Collapses agents that share a display name (the many ephemeral spawns of one task) into one
     * stack, in first-appearance order. Placeholder "Agent <id>" names share nothing, so those agents
     * stay apart. The selected agent is already on screen (the panel's hero, the composer companion),
     * so it leaves the rail while selected and returns on switch.
     */
    fun groups(agents: List<Pair<String, String>>, selectedAgentId: String?): List<AgentRailGroup> =
        agents
            .filter { (id, _) -> id != selectedAgentId }
            .groupBy { (id, name) -> if (DisplayNames.isAgentFallback(name)) id else name }
            .map { (_, members) -> AgentRailGroup(name = members.first().second, agentIds = members.map { it.first }) }

    /** [groups] as orbs; an orb's colour is its saved avatar style, else its position. */
    fun entries(groups: List<AgentRailGroup>, focus: ShellRailFocus): List<ShellRailEntry> =
        groups.mapIndexed { index, group -> entry(group, index, focus) }

    fun entry(group: AgentRailGroup, index: Int, focus: ShellRailFocus): ShellRailEntry {
        val ids = group.agentIds
        val agentId = ids.firstOrNull { it == focus.selectedAgentId } ?: ids.first()
        return ShellRailEntry(
            key = group.name,
            name = group.name,
            agentId = agentId,
            orbStyle = ids.firstNotNullOfOrNull { focus.avatarStyleByAgentId[it] } ?: index,
            memberCount = ids.size,
            selected = focus.selectedAgentId != null && focus.selectedAgentId in ids,
            thinking = focus.thinkingAgentId != null && focus.thinkingAgentId in ids,
            identity = ids.firstNotNullOfOrNull { focus.identityByAgentId[it] },
            activity = ids.mapNotNull { focus.activityByAgentId[it] }.maxByOrNull { recency(it.updatedAtLabel) },
            pinned = agentId in focus.pinnedAgentIds,
        )
    }

    /** An activity label's instant: queued work sorts newest, unparseable text oldest. */
    fun recency(label: String): Instant =
        runCatching { Instant.parse(label) }.getOrNull()
            ?: if (label == "Queued") Instant.DISTANT_FUTURE else Instant.DISTANT_PAST
}
