package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AppServerSubagentSnapshotAdapter
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentParentIdentity
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.subagents.DurableSubagentRegistry
import com.letta.mobile.data.subagents.SubagentChipObservation
import com.letta.mobile.data.subagents.SubagentChipSource
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Subagent registry projected from App Server runtime events
 * (`update_subagent_state`), not LettaShim HTTP.
 *
 * lgns8.22.8: this is now a thin adapter over [DurableSubagentRegistry]. It
 * used to hold a process-lifetime `ConcurrentHashMap` and DELETE any chip
 * absent from the latest snapshot, which meant (a) a controller restart lost
 * every chip while its workers kept running and (b) a chip could vanish
 * mid-flight. Both are gone: state is durable + keyed, and a chip missing from
 * the authoritative snapshot is RECONCILED to
 * [com.letta.mobile.data.subagents.SubagentChipState.ORPHANED] with telemetry
 * instead of being dropped.
 *
 * Pass a registry backed by
 * [com.letta.mobile.data.subagents.FileSubagentRegistryStore] to get restart
 * survival; the default is in-memory so tests and ephemeral probes stay cheap.
 */
class ControllerSubagentRegistrySource(
    val registry: DurableSubagentRegistry = DurableSubagentRegistry(),
) : SubagentRegistrySource {

    override suspend fun list(conversationId: String, includeTerminal: Boolean): List<SubagentEntry> =
        registry.snapshot(conversationId, includeTerminal).map { it.toEntry() }

    override suspend fun todos(conversationId: String, toolCallId: String): SubagentTodosSnapshot? {
        // Todo snapshots are not yet projected from App Server events (m6oa1).
        val record = registry.findByToolCall(conversationId, toolCallId) ?: return null
        val entry = record.toEntry()
        val activity = entry.activity
        val activityLines = activity?.lines.orEmpty()
        val activityTodos = activityLines.mapIndexed { index, line ->
            com.letta.mobile.data.model.SubagentTodo(
                content = line,
                status = activityTodoStatus(
                    entry,
                    ActivityTodoPosition(index, activityLines.lastIndex, activity?.truncated == true),
                ),
                activeForm = line,
            )
        }
        return SubagentTodosSnapshot(
            subagent = entry,
            todos = activityTodos,
            todosFound = activityTodos.isNotEmpty(),
        )
    }

    /**
     * Fold one authoritative `update_subagent_state` snapshot in.
     *
     * The frame is the source of truth for this conversation, so after
     * observing every entry we [DurableSubagentRegistry.reconcile] against the
     * ids it carried: anything persisted but absent is orphaned, not deleted.
     */
    fun ingest(frame: AppServerInboundFrame) {
        when (frame) {
            is AppServerInboundFrame.UpdateSubagentState -> ingestSubagentState(frame)
            is AppServerInboundFrame.TurnFinished -> onParentTurnFinished(frame)
            else -> return
        }
    }

    /**
     * letta-mobile-fxoew.2: told after every registry mutation, so the host can
     * push the conversation's snapshot to its viewers. Null pushes nothing.
     */
    @Volatile
    var changeListener: SubagentRegistryChangeListener? = null

    /**
     * letta-mobile-fxoew.3: the parent turn ended. Background subagents
     * legitimately outlive the turn that dispatched them (5hihw), so this does
     * NOT end running chips: [DurableSubagentRegistry.markParentTurnEnded] stays
     * a lifecycle no-op. The turn end is only a trigger for the stale-chip TTL,
     * which ends chips nobody has reported for [DurableSubagentRegistry.STALE_RUNNING_AFTER_MS].
     */
    private fun onParentTurnFinished(frame: AppServerInboundFrame.TurnFinished) {
        registry.markParentTurnEnded(frame.runtime.conversationId)
        notifyExpired(registry.expireStale())
    }

    private fun notifyExpired(expired: List<com.letta.mobile.data.subagents.SubagentChipRecord>) {
        expired.map { SubagentConversationKey(it.agentId, it.conversationId) }.distinct().forEach { key ->
            changeListener?.onConversationChanged(key)
        }
    }

    /**
     * letta-mobile-fxoew.2: the snapshot pushed to viewers. Live chips plus
     * terminal chips that ended within [PUSH_TERMINAL_LINGER_MS], so a
     * completion reaches the ring without replaying the whole terminal history.
     * The key's agent narrows to one parent (conversation id `default` is
     * shared between agents); a null agent keeps every parent.
     */
    fun pushSnapshot(key: SubagentConversationKey, now: Long): List<SubagentEntry> =
        registry.replaySnapshot(key.conversationId)
            .filter { key.agentId == null || it.agentId == key.agentId }
            .filter { !it.state.isTerminal || now - (it.terminalAtEpochMs ?: 0L) < PUSH_TERMINAL_LINGER_MS }
            .map { it.toEntry() }

    private fun ingestSubagentState(frame: AppServerInboundFrame.UpdateSubagentState) {
        val conversationId = frame.runtime.conversationId
        val agentId = frame.runtime.agentId
        val seen = linkedSetOf<String>()
        for (raw in frame.subagents) {
            val entry = decodeEntry(raw, ParentIds(conversationId, agentId)) ?: continue
            registry.observe(
                SubagentChipObservation.fromEntry(
                    entry = entry,
                    conversationId = conversationId,
                    agentId = agentId,
                    source = SubagentChipSource.CONTROLLER_NATIVE,
                    generation = frame.eventSeq,
                ),
            )
            seen += entry.toolCallId
        }
        registry.reconcile(conversationId, seen, generation = frame.eventSeq)
        val changed = SubagentConversationKey(agentId, conversationId)
        val expired = registry.expireStale()
        changeListener?.onConversationChanged(changed)
        notifyExpired(expired.filterNot { SubagentConversationKey(it.agentId, it.conversationId) == changed })
    }

    fun ingestReceived(received: AppServerReceivedFrame) = ingest(received.frame)

    fun start(scope: CoroutineScope, events: Flow<AppServerReceivedFrame>): Job =
        scope.launch { events.collect { ingestReceived(it) } }

    /**
     * letta-mobile-7vs4s: fold a weaker producer's view in. Precedence is
     * enforced inside the registry — these can create a chip the controller has
     * not seen yet, but can never overwrite a controller-native fact.
     */
    fun ingestFromSource(
        conversationId: String,
        agentId: String?,
        entries: List<SubagentEntry>,
        source: SubagentChipSource,
    ) {
        entries.forEach { entry ->
            registry.observe(
                SubagentChipObservation.fromEntry(
                    entry = entry,
                    conversationId = conversationId,
                    agentId = agentId,
                    source = source,
                ),
            )
        }
        if (entries.isNotEmpty()) changeListener?.onConversationChanged(SubagentConversationKey(agentId, conversationId))
    }

    /**
     * Replay-on-reconnect snapshot for the client fanout. Idempotent: chips are
     * keyed, so replaying repeatedly converges instead of duplicating.
     */
    fun replaySnapshot(conversationId: String): List<SubagentEntry> =
        registry.replaySnapshot(conversationId).map { it.toEntry() }

    /** Test / bootstrap hook. */
    fun replaceConversation(conversationId: String, entries: List<SubagentEntry>) {
        registry.clear()
        ingestFromSource(
            conversationId = conversationId,
            agentId = entries.firstOrNull()?.parentAgentId,
            entries = entries,
            source = SubagentChipSource.CONTROLLER_NATIVE,
        )
    }

    private data class ActivityTodoPosition(
        val index: Int,
        val lastIndex: Int,
        val truncated: Boolean,
    )

    private fun activityTodoStatus(entry: SubagentEntry, position: ActivityTodoPosition): String {
        if (entry.status != SubagentStatus.RUNNING) return "completed"
        if (position.truncated) return "completed"
        return if (position.index == position.lastIndex) "in_progress" else "completed"
    }

    private data class ParentIds(val conversationId: String, val agentId: String?)

    private fun decodeEntry(raw: JsonObject, parents: ParentIds): SubagentEntry? =
        AppServerSubagentSnapshotAdapter.toEntry(
            raw,
            SubagentParentIdentity(parents.conversationId, parents.agentId),
        )

    companion object {
        const val CAPABILITY = "subagent_registry_v1"

        /**
         * letta-mobile-fxoew.2: advertised only by hosts that push the registry
         * (`subagents_updated` on every mutation plus a replay on viewer join).
         * Deliberately NOT [CAPABILITY]: hosts already in production advertise
         * that for `subagent.list` alone, and a client that silenced its own
         * correlator on it would lose every live chip against those hosts.
         */
        const val PUSH_CAPABILITY = "subagent_registry_push_v1"

        /** How long a terminal chip stays in pushed snapshots after it ended. */
        const val PUSH_TERMINAL_LINGER_MS: Long = 60_000L
    }
}

/** letta-mobile-fxoew.2: hears every registry mutation, per parent conversation. */
fun interface SubagentRegistryChangeListener {
    fun onConversationChanged(key: SubagentConversationKey)
}

/** A conversation whose subagent snapshot changed, scoped to its parent agent when known. */
data class SubagentConversationKey(val agentId: String?, val conversationId: String)
