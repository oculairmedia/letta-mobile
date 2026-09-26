package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage

/**
 * True for an item the chat surface renders as a run: a grouped block, or a lone reply that already
 * holds its run's key.
 */
internal val ChatRenderItem.isRunItem: Boolean
    get() = when (this) {
        is ChatRenderItem.RunBlock -> true
        is ChatRenderItem.Single -> stableRunKey != null
    }

/**
 * What the live row showed that its settled copy must keep so the two renders are identical
 * (letta-mobile-qygvv.20, letta-mobile-sibr8): the LazyColumn key, the run id that expansion state
 * is keyed by, and the turn latency the run header's "Thought for Xs" title is derived from.
 *
 * The durable copy of a turn is named by the ledger and the live copy by the stream; on the App
 * Server they do not even share a run id, and the prompt's stored date comes from the server's clock
 * rather than the device's, so a duration recomputed at reconcile could read differently from the
 * one already on screen. Nothing here is persisted: a relaunch has no live row to agree with.
 */
internal data class LiveRowPresentation(
    val key: String,
    val runId: String?,
    val latencyMs: Long?,
)

/**
 * The live row that [events] settle, or null when none of them is on the overlay or the two copies
 * do not share a shape (a live run whose rows settled as separate bubbles keeps its own keys, since
 * several settled rows cannot all take one live key). A row is matched by the id either side knows
 * it under: its own server id, its canonical identity, a stream id aliased to it, or the otid a sent
 * prompt keeps from its optimistic echo to its stored row.
 */
internal fun ChatRenderItem.livePresentationFor(
    events: List<TimelineResidentEvent>,
    live: List<ChatRenderItem>,
    aliases: Map<String, TimelineMessageId>,
): LiveRowPresentation? {
    val names = events.flatMapTo(mutableSetOf()) { it.namesKnownTo(aliases) }
    if (names.isEmpty()) return null
    val match = live.firstOrNull { item -> item.rows().any { it.isNamedBy(names) } && sharesShapeWith(item) }
        ?: return null
    return LiveRowPresentation(match.key, match.runIdentity(), match.rows().firstNotNullOfOrNull { it.latencyMs })
}

/** [this] as the live row showed it; every other field is the settled row's own. */
internal fun ChatRenderItem.adopt(live: LiveRowPresentation): ChatRenderItem = when (this) {
    is ChatRenderItem.RunBlock -> copy(
        runId = live.runId ?: runId,
        keyOverride = live.key,
        messages = messages.map { (message, position) -> message.withLatencyOf(live) to position },
    )
    is ChatRenderItem.Single -> copy(
        stableRunId = if (stableRunKey != null) live.runId ?: stableRunId else stableRunId,
        keyOverride = live.key,
        message = message.withLatencyOf(live),
    )
}

private fun ChatRenderItem.sharesShapeWith(other: ChatRenderItem): Boolean = when {
    isRunItem || other.isRunItem -> isRunItem && other.isRunItem
    else -> rows().single().sameKindAs(other.rows().single())
}

private fun UiMessage.sameKindAs(other: UiMessage): Boolean =
    role == other.role && isReasoning == other.isReasoning

private fun ChatRenderItem.runIdentity(): String? = when (this) {
    is ChatRenderItem.RunBlock -> runId
    is ChatRenderItem.Single -> stableRunId
}

/** Only the row that carries the turn latency takes the live value; a missing live value keeps ours. */
private fun UiMessage.withLatencyOf(live: LiveRowPresentation): UiMessage =
    if (latencyMs == null || live.latencyMs == null) this else copy(latencyMs = live.latencyMs)

private fun TimelineResidentEvent.namesKnownTo(aliases: Map<String, TimelineMessageId>): List<String> =
    (listOf(serverId, identity.value, otid) + aliases.filterValues { it == identity }.keys).filter(String::isNotBlank)

private fun ChatRenderItem.rows(): List<UiMessage> = when (this) {
    is ChatRenderItem.RunBlock -> messages.map { it.first }
    is ChatRenderItem.Single -> listOf(message)
}

/** Reasoning rows are keyed `<serverId>:REASONING` so they cannot collide with their reply. */
private fun UiMessage.isNamedBy(names: Set<String>): Boolean =
    id in names || id.removeSuffix(":REASONING") in names || clientMessageId in names
