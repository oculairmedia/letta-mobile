package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * letta-mobile-by4vm.121.18: existing on-device agents follow the device model.
 *
 * An agent made for the phone's model names that model's bridge handle
 * (`lmstudio/google/gemma-3n-E2B-it-litert-lm`). When the device model changes (Settings: E2B to
 * E4B), the bridge serves only the new one, so the old handle fails every turn with
 * `Unknown model … for provider "lmstudio"`. A conversation can hide that for a while: one with its
 * own model override (the chat picker's per-conversation switch) keeps working until the user opens
 * one without. The policy: every agent and conversation override whose model is an on-device handle
 * other than the served one moves to the served handle. A model-server handle (an LM Studio or
 * other OpenAI-compatible model, cloud models) is never touched: only on-device handles follow.
 *
 * Shared on purpose: the rule is about agents and models, not the platform that applies it.
 */
data class OnDeviceModelFollowPlan(
    val servedHandle: String,
    val agents: List<AgentId> = emptyList(),
    val conversations: List<ConversationId> = emptyList(),
) {
    val isEmpty: Boolean get() = agents.isEmpty() && conversations.isEmpty()
}

object OnDeviceModelFollowPolicy {
    private const val BRIDGE_PREFIX = "lmstudio/"

    // On-device runtimes: LiteRT-LM model ids and MediaPipe .task files.
    private val OnDeviceModel = Regex("""(?i)(litert|\.task$)""")

    /** Whether [handle] names an on-device model on the runtime's bridge (not a model server). */
    fun isOnDeviceHandle(handle: String?): Boolean =
        handle != null && handle.startsWith(BRIDGE_PREFIX) && OnDeviceModel.containsMatchIn(handle.removePrefix(BRIDGE_PREFIX))

    /** What must move to [servedHandle], from the agent and conversation records the server lists. */
    fun plan(servedHandle: String, agents: List<JsonObject>, conversations: List<JsonObject>): OnDeviceModelFollowPlan =
        OnDeviceModelFollowPlan(
            servedHandle = servedHandle,
            agents = agents.filter { it.followsTo(servedHandle) }.mapNotNull { it.id()?.let(::AgentId) },
            conversations = conversations.filter { it.followsTo(servedHandle) }.mapNotNull { it.id()?.let(::ConversationId) },
        )

    private fun JsonObject.followsTo(servedHandle: String): Boolean {
        val model = string("model")
        return model != servedHandle && isOnDeviceHandle(model)
    }

    private fun JsonObject.id(): String? = string("id")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/**
 * Applies [OnDeviceModelFollowPolicy] over an App Server: lists agents and conversations, moves the
 * stale ones to the served handle, and returns what it moved. Run when the runtime starts with a
 * device model (which is also when a Settings model change takes effect).
 */
class OnDeviceModelFollower(
    private val client: AppServerClient,
    private val requestId: (String) -> String,
) {
    suspend fun follow(servedHandle: String): OnDeviceModelFollowPlan {
        require(OnDeviceModelFollowPolicy.isOnDeviceHandle(servedHandle)) { "$servedHandle is not an on-device model handle" }
        val agents = client.agentList(AppServerCommand.AgentList(requestId("agents"), query = page()))
        val conversations = client.conversationList(AppServerCommand.ConversationList(requestId("conversations"), query = page()))
        check(agents.success) { agents.error ?: "agent_list failed" }
        check(conversations.success) { conversations.error ?: "conversation_list failed" }
        val plan = OnDeviceModelFollowPolicy.plan(servedHandle, agents.agents.objects(), conversations.conversations.objects())
        val body = buildJsonObject { put("model", servedHandle) }
        plan.agents.forEach { id ->
            val response = client.agentUpdate(AppServerCommand.AgentUpdate(requestId("agent"), id.value, body))
            check(response.success) { response.error ?: "agent_update ${id.value} failed" }
        }
        plan.conversations.forEach { id ->
            val response = client.conversationUpdate(AppServerCommand.ConversationUpdate(requestId("conversation"), id.value, body))
            check(response.success) { response.error ?: "conversation_update ${id.value} failed" }
        }
        return plan
    }

    /** letta-code's local store reads `limit` only as a number (a string falls back to 20). */
    private fun page(): JsonObject = buildJsonObject {
        put("limit", PAGE_LIMIT)
        put("include_hidden", true)
    }

    private fun JsonArray?.objects(): List<JsonObject> = this.orEmpty().filterIsInstance<JsonObject>()

    private companion object {
        /** Far above what a phone holds; one page keeps the follow a single pass. */
        const val PAGE_LIMIT = 1_000
    }
}
