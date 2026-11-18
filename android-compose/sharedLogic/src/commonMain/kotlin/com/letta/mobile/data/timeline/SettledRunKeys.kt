package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.runKey
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
 * A run item keyed `run-<id>-from-<its oldest message id>`: a function of the item's own records,
 * never of which other blocks of the run share its snapshot or where a page edge fell. The live
 * overlay and the settled pages both key runs this way, so a settled block holding exactly the
 * records of a live block computes that block's key, and a block cut in two keeps the key on the
 * part that holds its oldest record. Items that are not runs keep their key.
 */
internal fun ChatRenderItem.keyedByOwnRecords(): ChatRenderItem = when (this) {
    is ChatRenderItem.RunBlock -> keyedByOwnRecords()
    is ChatRenderItem.Single -> stableRunId?.takeIf { stableRunKey != null }
        ?.let { copy(keyOverride = "${runKey(it)}-from-${message.id}") }
        ?: this
}

/** This block keyed `run-<id>-from-<its oldest message id>`. */
internal fun ChatRenderItem.RunBlock.keyedByOwnRecords(): ChatRenderItem.RunBlock =
    copy(keyOverride = "${runKey(runId)}-from-${messages.first().first.id}")

/**
 * What the live row showed that its settled copy must keep so the two renders are identical
 * the LazyColumn key, the run id that expansion state
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
    /** Already-painted member identities, indexed by their durable UI ids. */
    val messageKeys: Map<String, String> = emptyMap(),
)

/**
 * The live row that [events] settle, or null when none of them is on the overlay. A row is matched
 * by the id either side knows it under: its own server id, its canonical identity, a stream id
 * aliased to it, or the otid a sent prompt keeps from its optimistic echo to its stored row.
 *
 * A live row of the same shape wins. Failing that, a settled run takes the place of the live bubble
 * it absorbed (a reply streamed with no run settles inside its prompt's run): a fresh key there
 * would draw the reply twice for a frame and then drop the live row, the flash at the end of a turn
 *. The other way round never adopts: a live run whose rows settled as
 * separate bubbles keeps its own keys, since several settled rows cannot all take one live key.
 *
 * Whether the settled row may take the key is the caller's to decide (SettledLiveAdoptions takes
 * it only from a live row it holds in full).
 */
internal fun ChatRenderItem.livePresentationFor(
    events: List<TimelineResidentEvent>,
    live: List<ChatRenderItem>,
    aliases: Map<String, TimelineMessageId>,
): LiveRowPresentation? {
    val names = events.flatMapTo(mutableSetOf()) { it.namesKnownTo(aliases) }
    if (names.isEmpty()) return null
    val named = live.filter { item -> item.messagesInOrder().any { it.isNamedBy(names) } }
    val match = named.firstOrNull(::sharesShapeWith) ?: named.firstOrNull { isRunItem && !it.isRunItem } ?: return null
    return LiveRowPresentation(
        match.key, match.runIdentity(), match.messagesInOrder().firstNotNullOfOrNull { it.latencyMs },
        memberPresentations(events, match.messagesInOrder(), aliases),
    )
}

/** Carry the child identities through the same presentation cache as their owning row. */
private fun ChatRenderItem.memberPresentations(
    events: List<TimelineResidentEvent>,
    live: List<UiMessage>,
    aliases: Map<String, TimelineMessageId>,
): Map<String, String> = events.mapNotNull { event ->
    val durableNames = setOf(event.identity.value, event.serverId)
    val durable = messagesInOrder().singleOrNull {
        it.id in durableNames || it.id.removeSuffix(":REASONING") in durableNames
    } ?: return@mapNotNull null
    val names = event.namesKnownTo(aliases).toSet()
    val source = live.singleOrNull { it.sameKindAs(durable) && it.isNamedBy(names) } ?: return@mapNotNull null
    durable.id to (source.clientMessageId?.takeIf(String::isNotBlank) ?: source.id)
}.toMap()

/** Whether no live row under this key shows a record that [events] do not hold. */
internal fun LiveRowPresentation.isOnlyHeldWithin(
    events: List<TimelineResidentEvent>,
    live: List<ChatRenderItem>,
    aliases: Map<String, TimelineMessageId>,
): Boolean {
    val names = events.flatMapTo(mutableSetOf()) { it.namesKnownTo(aliases) }
    return live.filter { it.key == key }.all { row -> row.messagesInOrder().all { it.isNamedBy(names) } }
}

/** [this] as the live row showed it; every other field is the settled row's own. */
internal fun ChatRenderItem.adopt(live: LiveRowPresentation): ChatRenderItem = when (this) {
    is ChatRenderItem.RunBlock -> copy(
        runId = live.runId ?: runId,
        keyOverride = live.key,
        messages = messages.map { (message, position) -> message.withLatencyOf(live).withPresentationIdentityOf(live) to position },
    )
    is ChatRenderItem.Single -> copy(
        stableRunId = if (stableRunKey != null) live.runId ?: stableRunId else stableRunId,
        keyOverride = live.key,
        message = message.withLatencyOf(live).withPresentationIdentityOf(live),
    )
}

/** This is UI identity only; [UiMessage.id] remains the canonical action/lookup target. */
private fun UiMessage.withPresentationIdentityOf(live: LiveRowPresentation): UiMessage =
    live.messageKeys[id]?.let { copy(clientMessageId = it) } ?: this

private fun ChatRenderItem.sharesShapeWith(other: ChatRenderItem): Boolean = when {
    isRunItem || other.isRunItem -> isRunItem && other.isRunItem
    else -> messagesInOrder().single().sameKindAs(other.messagesInOrder().single())
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

internal fun TimelineResidentEvent.namesKnownTo(aliases: Map<String, TimelineMessageId>): List<String> =
    (listOf(serverId, identity.value, otid) + aliases.filterValues { it == identity }.keys).filter(String::isNotBlank)

/** Whether every message [this] draws is one of [names]. */
internal fun ChatRenderItem.isWhollyNamedBy(names: Set<String>): Boolean = messagesInOrder().all { it.isNamedBy(names) }

/**
 * [this] without the messages one of [names] already names, or null when it names them all. A run
 * keeps its key and the messages left, in order.
 */
internal fun ChatRenderItem.withoutRecordsNamedBy(names: Set<String>): ChatRenderItem? = when (this) {
    is ChatRenderItem.Single -> takeUnless { message.isNamedBy(names) }
    is ChatRenderItem.RunBlock -> {
        val rest = messages.filterNot { it.first.isNamedBy(names) }
        when (rest.size) {
            0 -> null
            messages.size -> this
            else -> copy(messages = rest)
        }
    }
}

/** Reasoning rows are keyed `<serverId>:REASONING` so they cannot collide with their reply. */
private fun UiMessage.isNamedBy(names: Set<String>): Boolean =
    id in names || id.removeSuffix(":REASONING") in names || clientMessageId in names
