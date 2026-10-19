package com.letta.mobile.data.subagents

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.isBackgroundSubagent
import kotlin.time.Instant

/**
 *  (PR4): the ONE projection of the subagent registry that
 * every subagent surface renders - the desktop Background tasks pane and the
 * Android roster sheet - so both clients show the same subagents in the same
 * states with the same words.
 *
 * Pure and clock-injected. It never invents a lifecycle: status comes from the
 * host ([SubagentEntry.status]); the only derived state is [SubagentRosterStatus.QUIET],
 * a running subagent with no host-observed progress for [QUIET_AFTER_MS].
 */
data class SubagentRoster(
    /** User-facing work still in flight, nested children right after their parent. */
    val running: List<SubagentRosterItem>,
    /** User-facing work that ended and was not cleared. */
    val finished: List<SubagentRosterItem>,
    /** Reflection / dream runs: kept out of the way, listed separately. */
    val background: List<SubagentRosterItem>,
) {
    val runningCount: Int get() = running.size
    val isEmpty: Boolean get() = running.isEmpty() && finished.isEmpty() && background.isEmpty()

    companion object {
        val EMPTY = SubagentRoster(emptyList(), emptyList(), emptyList())
    }
}

enum class SubagentRosterStatus {
    RUNNING,

    /** Running, but the host has seen no progress for a while. Not a failure. */
    QUIET,
    COMPLETED,
    FAILED,
    CANCELLED,
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

data class SubagentRosterItem(
    val key: String,
    /** The dispatch's description, else its type. */
    val title: String,
    /** The display type ("General-purpose", "Reflection"). */
    val typeLabel: String,
    val status: SubagentRosterStatus,
    val identity: SubagentIdentity,
    /** Tool calls made so far; null when the host does not report it. */
    val toolCallCount: Int?,
    /** Latest bounded activity lines, oldest first. */
    val activity: List<String>,
    /** Running: time since start. Ended: the run's duration when reported. */
    val elapsedMs: Long?,
    val model: String?,
    val childAgentId: String?,
    val childConversationId: String?,
    /** 0 for a direct subagent, 1 for a subagent's own subagent (letta-code >= 0.34.3). */
    val depth: Int,
    val parentKey: String?,
    /** Dispatched with run_in_background. */
    val runsInBackground: Boolean,
    /** The parent tool call that dispatched it (todo sheet / dispatch card correlation). */
    val toolCallId: String,
) {
    /** The child's conversation is known; while running it usually is not yet. */
    val canOpenConversation: Boolean get() = !childConversationId.isNullOrBlank() && !childAgentId.isNullOrBlank()
}

/** A running subagent with no host-observed progress for this long reads as quiet. */
const val QUIET_AFTER_MS: Long = 5 * 60_000L

fun projectSubagentRoster(
    entries: List<SubagentEntry>,
    now: Long,
    clearedKeys: Set<String> = emptySet(),
): SubagentRoster {
    if (entries.isEmpty()) return SubagentRoster.EMPTY
    val unique = entries.distinctBy(SubagentEntry::rosterKey)
    val backgroundKeys = unique.filter { it.isBackgroundSubagent() }.mapTo(HashSet()) { it.rosterKey() }
    val nested = nestChildren(unique.map { it.toRosterItem(now) }, unique)
    val (background, foreground) = nested.partition { it.key in backgroundKeys }
    return SubagentRoster(
        running = foreground.filter { !it.status.isTerminal },
        finished = foreground.filter { it.status.isTerminal && it.key !in clearedKeys },
        background = background.filter { !it.status.isTerminal || it.key !in clearedKeys },
    )
}

/**
 * User-facing subagents still in flight: what an "N running" affordance shows.
 * Reflection / dream runs are not counted (they used to light the desktop
 * background-tasks orb after every turn).
 */
fun List<SubagentEntry>.visibleRunningCount(): Int = count { entry ->
    !entry.isBackgroundSubagent() &&
        SubagentStatus.normalize(entry.status).let { it == null || it == SubagentStatus.RUNNING }
}

/** Stable identity shared by every surface (and the Clear action). */
fun SubagentEntry.rosterKey(): String =
    toolCallId.takeIf { it.isNotBlank() }
        ?: taskId?.takeIf { it.isNotBlank() }
        ?: "${startedAt.orEmpty()}_${subagentType}_$description"

/**
 * One nested level (letta-code 0.34.3): an entry whose parent agent is another
 * entry's child agent is that entry's child. Children follow their parent;
 * order is otherwise the registry's.
 */
private fun nestChildren(items: List<SubagentRosterItem>, entries: List<SubagentEntry>): List<SubagentRosterItem> {
    val keyByChildAgent = items.mapNotNull { item -> item.childAgentId?.let { it to item.key } }.toMap()
    val parentAgentByKey = entries.associate { it.rosterKey() to it.parentAgentId }
    val withParents = items.map { item ->
        val parentKey = parentAgentByKey[item.key]?.let(keyByChildAgent::get)?.takeIf { it != item.key }
        if (parentKey == null) item else item.copy(depth = 1, parentKey = parentKey)
    }
    val childrenByParent = withParents.filter { it.parentKey != null }.groupBy { it.parentKey }
    val ordered = withParents.filter { it.parentKey == null }.flatMap { root -> listOf(root) + childrenByParent[root.key].orEmpty() }
    // Deeper nesting than one level is not expected; anything not placed stays visible, flat.
    val placed = ordered.mapTo(HashSet()) { it.key }
    return ordered + withParents.filter { it.key !in placed }
}

private fun SubagentEntry.toRosterItem(now: Long): SubagentRosterItem {
    val status = rosterStatus(now)
    return SubagentRosterItem(
        key = rosterKey(),
        title = description.takeIf { it.isNotBlank() } ?: subagentType.takeIf { it.isNotBlank() } ?: "Subagent",
        typeLabel = subagentType,
        status = status,
        identity = identity(),
        toolCallCount = toolCallCount,
        activity = activity?.lines.orEmpty(),
        elapsedMs = elapsed(status, now),
        model = model?.takeIf { it.isNotBlank() },
        childAgentId = subagentAgentId?.takeIf { it.isNotBlank() },
        childConversationId = subagentConversationId?.takeIf { it.isNotBlank() },
        depth = 0,
        parentKey = null,
        runsInBackground = isBackground == true,
        toolCallId = toolCallId,
    )
}

private fun SubagentEntry.rosterStatus(now: Long): SubagentRosterStatus = when (SubagentStatus.normalize(status)) {
    SubagentStatus.COMPLETED -> SubagentRosterStatus.COMPLETED
    SubagentStatus.FAILED -> SubagentRosterStatus.FAILED
    SubagentStatus.CANCELLED -> SubagentRosterStatus.CANCELLED
    // RUNNING and any unknown status: still in flight until the host says otherwise.
    else -> {
        val last = lastActivityAtMs
        if (last != null && now - last >= QUIET_AFTER_MS) SubagentRosterStatus.QUIET else SubagentRosterStatus.RUNNING
    }
}

private fun SubagentEntry.identity(): SubagentIdentity = subagentIdentityOf(subagentType, listOf(subagentAgentId, toolCallId))

private fun SubagentEntry.elapsed(status: SubagentRosterStatus, now: Long): Long? {
    if (status.isTerminal) return durationMs?.takeIf { it > 0 }
    val started = startedAtEpochMs() ?: return null
    return (now - started).takeIf { it >= 0 }
}

/** startedAt arrives as epoch millis (native host) or ISO-8601 (older producers). */
fun SubagentEntry.startedAtEpochMs(): Long? {
    val raw = startedAt?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    raw.toLongOrNull()?.let { return if (it < 1_000_000_000_000L) it * 1_000L else it }
    return runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull()
}

/** "1m 28s" / "12s" / "1h 4m". */
fun formatSubagentElapsed(ms: Long): String {
    val seconds = ms / 1_000
    return when {
        seconds >= 3_600 -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}
