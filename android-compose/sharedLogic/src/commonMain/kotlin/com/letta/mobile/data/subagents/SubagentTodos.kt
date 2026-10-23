package com.letta.mobile.data.subagents

import com.letta.mobile.data.model.parseAgentTodos
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Read the child's authored plan from Letta Code's cumulative `tool_calls[{id,name,args}]`.
 * The native history is append-only for a dispatch; its position survives reconnects
 * and controller restarts, unlike connection-local event sequence numbers. Tool calls
 * without a plan are activity only. No child-agent or parent-tool identity is inferred.
 */
fun SubagentChipObservation.withNativeTodos(snapshot: JsonObject): SubagentChipObservation {
    val calls = snapshot["tool_calls"] as? JsonArray ?: return this
    var observation = this
    calls.forEachIndexed { index, element ->
        val call = element as? JsonObject ?: return@forEachIndexed
        val name = (call["name"] as? JsonPrimitive)?.content ?: return@forEachIndexed
        val arguments = when (val args = call["args"]) {
            is JsonPrimitive -> args.takeIf { it.isString }?.content
            is JsonObject -> args.toString()
            else -> null
        } ?: return@forEachIndexed
        val plan = parseAgentTodos(name, arguments) ?: return@forEachIndexed
        observation = observation.copy(todos = plan, todoRevision = index + 1)
    }
    return observation
}
