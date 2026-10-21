package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.chat.projection.extractSubagentDispatch
import com.letta.mobile.data.chat.projection.extractSubagentNotification
import com.letta.mobile.data.model.AppServerSubagentSnapshotAdapter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
 * Subagent registry projected from App Server runtime snapshots and parent
 * dispatch/notification stream events, not LettaShim HTTP.
 *
 * State is durable and keyed by the exact parent and dispatch. A snapshot can
 * omit a background task, so only explicit lifecycle evidence ends its chip.
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
     * Listed statuses are authoritative; an omitted entry is retained because
     * background tasks may be absent from a later parent turn's snapshot.
     */
    fun ingest(frame: AppServerInboundFrame) {
        when (frame) {
            is AppServerInboundFrame.StreamDelta -> ingestParentDelta(frame)
            is AppServerInboundFrame.UpdateSubagentState -> ingestSubagentState(frame)
            is AppServerInboundFrame.TurnFinished -> onParentTurnFinished(frame)
            else -> return
        }
    }

    /**
     * Told after every registry mutation, so the host can
     * push the conversation's snapshot to its viewers. Null pushes nothing.
     */
    @Volatile
    var changeListener: SubagentRegistryChangeListener? = null

    /**
     * The parent turn ended. Background subagents
     * legitimately outlive the turn that dispatched them, so this does
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
     * The snapshot pushed to viewers. Live chips plus
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

    private fun ingestParentDelta(frame: AppServerInboundFrame.StreamDelta) {
        if (!frame.subagentId.isNullOrBlank()) return
        val delta = frame.delta as? JsonObject ?: return
        val projection = ParentStreamProjection(registry)
        val entry = when ((delta["message_type"] as? JsonPrimitive)?.contentOrNull) {
            "tool_call_message" -> projection.dispatchEntry(frame, delta)
            "tool_return_message" -> projection.receiptEntry(frame, delta)
            "user_message" -> projection.terminalEntry(frame, delta)
            else -> null
        } ?: return
        registry.observe(
            SubagentChipObservation.fromEntry(
                entry, frame.runtime.conversationId, frame.runtime.agentId,
                SubagentChipSource.CONTROLLER_NATIVE, frame.eventSeq,
            ),
        )
        changeListener?.onConversationChanged(SubagentConversationKey(frame.runtime.agentId, frame.runtime.conversationId))
    }

    private class ParentStreamProjection(private val registry: DurableSubagentRegistry) {
        fun dispatchEntry(frame: AppServerInboundFrame.StreamDelta, delta: JsonObject): SubagentEntry? {
            val call = (delta["tool_call"] as? JsonObject)
                ?.takeIf { it.scalar("name")?.substringAfterLast('.') == "Agent" } ?: return null
            val id = call.scalar("tool_call_id") ?: return null
            // A replayed launch is never evidence that a terminal child started again.
            if (registry.record(frame.runtime.conversationId, frame.runtime.agentId, id) != null) return null
            return extractSubagentDispatch(id, call["arguments"].wireText().orEmpty(), null)?.let { dispatch ->
                SubagentEntry(
                    toolCallId = id, description = dispatch.description, subagentType = dispatch.subagentType,
                    status = SubagentStatus.RUNNING, parentAgentId = frame.runtime.agentId,
                    parentConversationId = frame.runtime.conversationId,
                )
            }
        }

        fun receiptEntry(frame: AppServerInboundFrame.StreamDelta, delta: JsonObject): SubagentEntry? {
            val id = delta.scalar("tool_call_id") ?: return null
            val existing = registry.record(frame.runtime.conversationId, frame.runtime.agentId, id)?.toEntry() ?: return null
            val body = delta["tool_return"].wireText() ?: return null
            val dispatch = extractSubagentDispatch(id, "{}", body)
            // Parse only labelled receipt identity lines, never result prose.
            val taskId = dispatch?.taskId ?: Regex("(?m)^Task running in background with task ID: (\\S+)\\s*$")
                .find(body)?.groupValues?.get(1)
            val childAgent = dispatch?.subagentAgentId ?: Regex("(?m)^Agent ID: (\\S+)\\s*$")
                .find(body)?.groupValues?.get(1)
            return taskId?.let {
                existing.copy(
                    taskId = existing.taskId ?: it,
                    subagentAgentId = existing.subagentAgentId ?: childAgent,
                )
            }
        }

        fun terminalEntry(frame: AppServerInboundFrame.StreamDelta, delta: JsonObject): SubagentEntry? {
            val notification = extractSubagentNotification(delta["content"].wireText().orEmpty()) ?: return null
            val status = SubagentStatus.normalize(notification.status)
                ?.takeIf { it != SubagentStatus.RUNNING } ?: return null
            // Task ids, tool ids and child agent ids are separate namespaces. Require
            // a unique dispatch in this exact parent scope; never use display titles.
            val matches = registry.replaySnapshot(frame.runtime.conversationId).filter { record ->
                record.agentId == frame.runtime.agentId &&
                    (notification.taskId != null || notification.toolCallId != null) &&
                    (notification.taskId == null || notification.taskId == record.taskId) &&
                    (notification.toolCallId == null || notification.toolCallId == record.toolCallId) &&
                    (notification.subagentAgentId == null || notification.subagentAgentId == record.subagentAgentId)
            }
            val entry = matches.singleOrNull()?.toEntry() ?: return null
            return entry.copy(status = status, durationMs = notification.durationMs ?: entry.durationMs)
        }

        private fun JsonObject.scalar(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        private fun JsonElement?.wireText(): String? = when (this) {
            is JsonPrimitive -> contentOrNull
            is JsonArray -> mapNotNull { (it as? JsonObject)?.scalar("text") }.joinToString("\n")
            is JsonObject -> toString()
            else -> null
        }
    }

    private fun ingestSubagentState(frame: AppServerInboundFrame.UpdateSubagentState) {
        val conversationId = frame.runtime.conversationId
        val agentId = frame.runtime.agentId
        for (raw in frame.subagents) {
            val entry = AppServerSubagentSnapshotAdapter.toEntry(
                raw, SubagentParentIdentity(conversationId, agentId),
            ) ?: continue
            registry.observe(
                SubagentChipObservation.fromEntry(
                    entry = entry,
                    conversationId = conversationId,
                    agentId = agentId,
                    source = SubagentChipSource.CONTROLLER_NATIVE,
                    generation = frame.eventSeq,
                ),
            )
        }
        // Snapshots can omit background tasks; absence is not terminal evidence.
        val changed = SubagentConversationKey(agentId, conversationId)
        val expired = registry.expireStale()
        changeListener?.onConversationChanged(changed)
        notifyExpired(expired.filterNot { SubagentConversationKey(it.agentId, it.conversationId) == changed })
    }

    fun ingestReceived(received: AppServerReceivedFrame) = ingest(received.frame)

    fun start(scope: CoroutineScope, events: Flow<AppServerReceivedFrame>): Job =
        scope.launch { events.collect { ingestReceived(it) } }

    /**
     * Fold a weaker producer's view in. Precedence is
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

    companion object {
        const val CAPABILITY = "subagent_registry_v1"

        /**
         * Advertised only by hosts that push the registry
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

/** Hears every registry mutation, per parent conversation. */
fun interface SubagentRegistryChangeListener {
    fun onConversationChanged(key: SubagentConversationKey)
}

/** A conversation whose subagent snapshot changed, scoped to its parent agent when known. */
data class SubagentConversationKey(val agentId: String?, val conversationId: String)
