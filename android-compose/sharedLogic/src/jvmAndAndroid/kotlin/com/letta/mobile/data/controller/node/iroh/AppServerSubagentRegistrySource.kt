package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.repository.subagent.ParentContext
import com.letta.mobile.data.repository.subagent.SubagentCorrelator
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Process-scoped projection of the App Server's authoritative runtime stream.
 * State is partitioned by parent conversation before it can be read by admin RPC.
 */
class AppServerSubagentRegistrySource(
    client: AppServerClient,
    scope: CoroutineScope,
) : SubagentRegistrySource {
    private val mutex = Mutex()
    private val entriesByConversation = mutableMapOf<String, LinkedHashMap<String, SubagentEntry>>()
    private val todosByConversation = mutableMapOf<String, MutableMap<String, List<SubagentTodo>>>()
    private val correlators = mutableMapOf<String, SubagentCorrelator>()

    init {
        scope.launch {
            client.events.collect { received -> ingest(received.frame) }
        }
    }

    override suspend fun list(conversationId: String, includeTerminal: Boolean): List<SubagentEntry> =
        mutex.withLock {
            entriesByConversation[conversationId].orEmpty().values
                .filter { includeTerminal || it.status == SubagentStatus.RUNNING }
                .toList()
        }

    override suspend fun todos(conversationId: String, toolCallId: String): SubagentTodosSnapshot? =
        mutex.withLock {
            val entry = entriesByConversation[conversationId]?.get(toolCallId) ?: return@withLock null
            val todos = todosByConversation[conversationId]?.get(toolCallId)
            SubagentTodosSnapshot(entry, todos.orEmpty(), todos != null)
        }

    internal suspend fun ingest(frame: AppServerInboundFrame) {
        when (frame) {
            is AppServerInboundFrame.UpdateSubagentState -> ingestSnapshot(frame)
            is AppServerInboundFrame.StreamDelta -> ingestDelta(frame)
            else -> Unit
        }
    }

    private suspend fun ingestSnapshot(frame: AppServerInboundFrame.UpdateSubagentState) = mutex.withLock {
        val conversationId = frame.runtime.conversationId
        val previous = entriesByConversation[conversationId].orEmpty()
        val snapshot = LinkedHashMap<String, SubagentEntry>()
        frame.subagents.mapNotNull { parseEntry(it, frame.runtime.agentId, conversationId) }
            .forEach { entry -> snapshot[entry.toolCallId] = mergeEntry(previous[entry.toolCallId], entry) }
        entriesByConversation[conversationId] = snapshot
        todosByConversation[conversationId]?.keys?.retainAll(snapshot.keys)
    }

    private suspend fun ingestDelta(frame: AppServerInboundFrame.StreamDelta) = mutex.withLock {
        val delta = frame.delta as? JsonObject ?: return@withLock
        val conversationId = frame.runtime.conversationId
        val messageType = delta.string("message_type") ?: return@withLock
        val toolCall = delta["tool_call"] as? JsonObject
        when (messageType) {
            "tool_call_message", "approval_request_message" -> {
                val name = toolCall?.string("name") ?: return@withLock
                val toolCallId = toolCall.string("tool_call_id") ?: delta.string("tool_call_id") ?: return@withLock
                if (name == AGENT_TOOL) {
                    val correlator = correlators.getOrPut(conversationId, ::SubagentCorrelator)
                    correlator.onAgentDispatch(
                        toolCallId = toolCallId,
                        arguments = toolCall["arguments"]?.toString() ?: delta["arguments"]?.toString(),
                        parent = ParentContext(frame.runtime.agentId, conversationId, delta.string("run_id")),
                    )
                    mergeCorrelator(conversationId, correlator)
                } else if (name == TODO_WRITE_TOOL) {
                    val owner = resolveSubagent(conversationId, frame.subagentId) ?: return@withLock
                    parseTodos(toolCall["arguments"] ?: delta["arguments"])?.let { todos ->
                        todosByConversation.getOrPut(conversationId, ::mutableMapOf)[owner.toolCallId] = todos
                    }
                }
            }
            "tool_return_message" -> {
                val toolCallId = toolCall?.string("tool_call_id") ?: delta.string("tool_call_id") ?: return@withLock
                val correlator = correlators.getOrPut(conversationId, ::SubagentCorrelator)
                correlator.onAgentReturn(
                    toolCallId,
                    ParentContext(frame.runtime.agentId, conversationId, delta.string("run_id")),
                )
                mergeCorrelator(conversationId, correlator)
            }
        }
    }

    private fun mergeCorrelator(conversationId: String, correlator: SubagentCorrelator) {
        val entries = entriesByConversation.getOrPut(conversationId, ::LinkedHashMap)
        correlator.snapshot().forEach { entry -> entries[entry.toolCallId] = mergeEntry(entries[entry.toolCallId], entry) }
    }

    private fun resolveSubagent(conversationId: String, subagentId: String?): SubagentEntry? {
        if (subagentId == null) return null
        return entriesByConversation[conversationId]?.values?.firstOrNull {
            subagentId == it.toolCallId || subagentId == it.taskId ||
                subagentId == it.subagentAgentId || subagentId == it.subagentConversationId
        }
    }

    private fun parseEntry(raw: JsonObject, parentAgentId: String, parentConversationId: String): SubagentEntry? {
        runCatching { JSON.decodeFromJsonElement<SubagentEntry>(raw) }.getOrNull()?.let { decoded ->
            return decoded.copy(
                parentAgentId = decoded.parentAgentId ?: parentAgentId,
                parentConversationId = decoded.parentConversationId ?: parentConversationId,
            )
        }
        val toolCallId = raw.string("toolCallId", "tool_call_id") ?: return null
        return SubagentEntry(
            toolCallId = toolCallId,
            description = raw.string("description").orEmpty(),
            subagentType = raw.string("subagentType", "subagent_type").orEmpty(),
            status = raw.string("status") ?: SubagentStatus.RUNNING,
            taskId = raw.string("taskId", "task_id"),
            subagentAgentId = raw.string("subagentAgentId", "subagent_agent_id", "agent_id"),
            subagentConversationId = raw.string("subagentConversationId", "subagent_conversation_id", "conversation_id"),
            parentRunId = raw.string("parentRunId", "parent_run_id", "run_id"),
            parentAgentId = raw.string("parentAgentId", "parent_agent_id") ?: parentAgentId,
            parentConversationId = raw.string("parentConversationId", "parent_conversation_id") ?: parentConversationId,
            startedAt = raw.string("startedAt", "started_at"),
        )
    }

    private fun parseTodos(arguments: JsonElement?): List<SubagentTodo>? = runCatching {
        val root = when (arguments) {
            is JsonObject -> arguments
            is JsonPrimitive -> JSON.parseToJsonElement(arguments.content).jsonObject
            else -> return null
        }
        val todos = root["todos"] as? JsonArray ?: return null
        todos.map { element ->
            val todo = element.jsonObject
            SubagentTodo(
                content = todo.string("content").orEmpty(),
                status = todo.string("status").orEmpty(),
                activeForm = todo.string("activeForm", "active_form").orEmpty(),
            )
        }
    }.getOrNull()

    private fun mergeEntry(existing: SubagentEntry?, incoming: SubagentEntry): SubagentEntry =
        if (existing == null) incoming else incoming.copy(
            description = incoming.description.ifEmpty { existing.description },
            subagentType = incoming.subagentType.ifEmpty { existing.subagentType },
            taskId = incoming.taskId ?: existing.taskId,
            subagentAgentId = incoming.subagentAgentId ?: existing.subagentAgentId,
            subagentConversationId = incoming.subagentConversationId ?: existing.subagentConversationId,
            parentRunId = incoming.parentRunId ?: existing.parentRunId,
            parentAgentId = incoming.parentAgentId ?: existing.parentAgentId,
            parentConversationId = incoming.parentConversationId ?: existing.parentConversationId,
            startedAt = incoming.startedAt ?: existing.startedAt,
        )

    private fun JsonObject.string(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull }

    private companion object {
        const val AGENT_TOOL = "Agent"
        const val TODO_WRITE_TOOL = "TodoWrite"
        val JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
