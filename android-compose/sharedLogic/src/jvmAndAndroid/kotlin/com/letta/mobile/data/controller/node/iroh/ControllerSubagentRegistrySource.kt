package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AppServerSubagentSnapshotAdapter
import com.letta.mobile.data.model.SubagentEntry
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
        return SubagentTodosSnapshot(subagent = record.toEntry(), todos = emptyList(), todosFound = false)
    }

    /**
     * Fold one authoritative `update_subagent_state` snapshot in.
     *
     * The frame is the source of truth for this conversation, so after
     * observing every entry we [DurableSubagentRegistry.reconcile] against the
     * ids it carried: anything persisted but absent is orphaned, not deleted.
     */
    fun ingest(frame: AppServerInboundFrame) {
        if (frame !is AppServerInboundFrame.UpdateSubagentState) return
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

    private data class ParentIds(val conversationId: String, val agentId: String?)

    private fun decodeEntry(raw: JsonObject, parents: ParentIds): SubagentEntry? =
        AppServerSubagentSnapshotAdapter.toEntry(
            raw = raw,
            parentConversationId = parents.conversationId,
            parentAgentId = parents.agentId,
        )

    companion object {
        const val CAPABILITY = "subagent_registry_v1"
    }
}
