package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
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
        return if (includeTerminal) {
            entries
        } else {
            entries.filter { it.status == SubagentStatus.RUNNING }
        }
    }

    override suspend fun todos(conversationId: String, toolCallId: String): SubagentTodosSnapshot? {
        // Todo snapshots are not yet projected from App Server events (m6oa1).
        // Return the live entry when known so callers can still resolve identity.
        val entry = byConversation[conversationId]?.get(toolCallId) ?: return null
        return SubagentTodosSnapshot(subagent = entry, todos = emptyList(), todosFound = false)
    }

    fun ingest(frame: AppServerInboundFrame) {
        if (frame !is AppServerInboundFrame.UpdateSubagentState) return
        val conversationId = frame.runtime.conversationId
        val bucket = byConversation.getOrPut(conversationId) { ConcurrentHashMap() }
        val seen = linkedSetOf<String>()
        for (raw in frame.subagents) {
            val entry = decodeEntry(raw, conversationId, frame.runtime.agentId) ?: continue
            bucket[entry.toolCallId] = entry
            seen += entry.toolCallId
        }
        // Frame is authoritative for the conversation snapshot: drop entries
        // that disappeared from the update (completed ones may linger client-side
        // via includeTerminal=true until the next full sync).
        val stale = bucket.keys.filter { it !in seen }
        stale.forEach { bucket.remove(it) }
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

    private fun decodeEntry(
        raw: JsonObject,
        conversationId: String,
        runtimeAgentId: String?,
    ): SubagentEntry? {
        val toolCallId = raw.string("toolCallId") ?: raw.string("tool_call_id") ?: return null
        return runCatching {
            AppServerProtocol.json.decodeFromJsonElement(SubagentEntry.serializer(), raw)
        }.getOrElse {
            SubagentEntry(
                toolCallId = toolCallId,
                description = raw.string("description").orEmpty(),
                subagentType = raw.string("subagentType") ?: raw.string("subagent_type").orEmpty(),
                status = raw.string("status") ?: SubagentStatus.RUNNING,
                taskId = raw.string("taskId") ?: raw.string("task_id"),
                subagentAgentId = raw.string("subagentAgentId") ?: raw.string("subagent_agent_id"),
                subagentConversationId = raw.string("subagentConversationId")
                    ?: raw.string("subagent_conversation_id"),
                parentRunId = raw.string("parentRunId") ?: raw.string("parent_run_id"),
                parentAgentId = raw.string("parentAgentId")
                    ?: raw.string("parent_agent_id")
                    ?: runtimeAgentId,
                parentConversationId = raw.string("parentConversationId")
                    ?: raw.string("parent_conversation_id")
                    ?: conversationId,
                startedAt = raw.string("startedAt") ?: raw.string("started_at"),
            )
        }.let { decoded ->
            decoded.copy(
                parentConversationId = decoded.parentConversationId ?: conversationId,
                parentAgentId = decoded.parentAgentId ?: runtimeAgentId,
            )
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    companion object {
        const val CAPABILITY = "subagent_registry_v1"
    }
}
