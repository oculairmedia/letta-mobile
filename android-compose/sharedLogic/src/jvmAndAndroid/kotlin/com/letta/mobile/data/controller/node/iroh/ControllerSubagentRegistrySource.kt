package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Subagent registry projected from App Server runtime events
 * (`update_subagent_state`), not LettaShim HTTP.
 *
 * Phase 4: production wires this instead of the retired LettaShim HTTP
 * subagent registry. Full lifecycle/todo parity remains epic `m6oa1`; this
 * source is the controller-native owner that fails soft (empty/null) until
 * events hydrate.
 */
class ControllerSubagentRegistrySource : SubagentRegistrySource {
    private val byConversation = ConcurrentHashMap<String, ConcurrentHashMap<String, SubagentEntry>>()

    override suspend fun list(conversationId: String, includeTerminal: Boolean): List<SubagentEntry> {
        val entries = byConversation[conversationId]?.values?.toList().orEmpty()
        return if (includeTerminal) entries else entries.filter { it.status == SubagentStatus.RUNNING }
    }

    override suspend fun todos(conversationId: String, toolCallId: String): SubagentTodosSnapshot? {
        // Todo snapshots are not yet projected from App Server events (m6oa1).
        val entry = byConversation[conversationId]?.get(toolCallId) ?: return null
        return SubagentTodosSnapshot(subagent = entry, todos = emptyList(), todosFound = false)
    }

    fun ingest(frame: AppServerInboundFrame) {
        if (frame !is AppServerInboundFrame.UpdateSubagentState) return
        val conversationId = frame.runtime.conversationId
        val bucket = byConversation.getOrPut(conversationId) { ConcurrentHashMap() }
        val seen = linkedSetOf<String>()
        for (raw in frame.subagents) {
            val entry = decodeEntry(raw, ParentIds(conversationId, frame.runtime.agentId)) ?: continue
            bucket[entry.toolCallId] = entry
            seen += entry.toolCallId
        }
        bucket.keys.filter { it !in seen }.forEach { bucket.remove(it) }
        if (bucket.isEmpty()) byConversation.remove(conversationId, bucket)
    }

    fun ingestReceived(received: AppServerReceivedFrame) = ingest(received.frame)

    fun start(scope: CoroutineScope, events: Flow<AppServerReceivedFrame>): Job =
        scope.launch { events.collect { ingestReceived(it) } }

    /** Test / bootstrap hook. */
    fun replaceConversation(conversationId: String, entries: List<SubagentEntry>) {
        if (entries.isEmpty()) {
            byConversation.remove(conversationId)
            return
        }
        val bucket = ConcurrentHashMap<String, SubagentEntry>()
        entries.forEach { bucket[it.toolCallId] = it }
        byConversation[conversationId] = bucket
    }

    private data class ParentIds(val conversationId: String, val agentId: String?)

    private fun decodeEntry(raw: JsonObject, parents: ParentIds): SubagentEntry? {
        val toolCallId = raw.firstString("toolCallId", "tool_call_id") ?: return null
        // Manual decode only — kotlinx serializer throws on partial/nonstandard
        // keys and that exception cost shows up on every update_subagent_state.
        return manualEntry(raw, toolCallId, parents)
    }

    private fun manualEntry(raw: JsonObject, toolCallId: String, parents: ParentIds): SubagentEntry =
        SubagentEntry(
            toolCallId = toolCallId,
            description = raw.firstString("description").orEmpty(),
            subagentType = raw.firstString("subagentType", "subagent_type").orEmpty(),
            status = raw.firstString("status") ?: SubagentStatus.RUNNING,
            taskId = raw.firstString("taskId", "task_id"),
            subagentAgentId = raw.firstString("subagentAgentId", "subagent_agent_id"),
            subagentConversationId = raw.firstString("subagentConversationId", "subagent_conversation_id"),
            parentRunId = raw.firstString("parentRunId", "parent_run_id"),
            parentAgentId = raw.firstString("parentAgentId", "parent_agent_id") ?: parents.agentId,
            parentConversationId = raw.firstString("parentConversationId", "parent_conversation_id")
                ?: parents.conversationId,
            startedAt = raw.firstString("startedAt", "started_at"),
        )

    private fun JsonObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }

    companion object {
        const val CAPABILITY = "subagent_registry_v1"
    }
}
