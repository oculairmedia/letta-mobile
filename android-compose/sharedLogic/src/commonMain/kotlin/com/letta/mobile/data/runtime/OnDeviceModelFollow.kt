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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Existing on-device agents follow the device model.
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
 * The same pass heals a context window letta-code cannot work with. letta-code 0.34.14 takes an
 * agent's (or conversation's) `model_settings.context_window_limit` as the model's window
 * (letta.js:116099 `resolvePiModelForAgent`), and its pi-ai layer clamps every request's output to
 * `window - prompt estimate - 4,096` (letta.js:26056 `clampMaxTokensToContext`,:26105
 * `CONTEXT_SAFETY_TOKENS = 4096`). A limit at the engine's real window (4,096 or 8,192, stored on
 * agents while #2094 advertised it) therefore asks the model for 1 token. On-device records get
 * [OnDeviceServedModel.lettaCodeWindow] instead: the engine window plus pi-ai's margin, so the clamp
 * leaves exactly the engine's room; the bridge itself enforces the engine window (overflow 422).
 *
 * Shared on purpose: the rule is about agents and models, not the platform that applies it.
 */

/** The model the runtime serves now: its bridge handle and the engine's context window (Settings "Max tokens"). */
data class OnDeviceServedModel(val handle: String, val contextTokens: Int) {
    /** The `context_window_limit` letta-code needs so its 4,096-token safety margin leaves the engine's window. */
    val lettaCodeWindow: Int get() = contextTokens + PI_AI_CONTEXT_SAFETY_TOKENS

    private companion object {
        /** letta-code 0.34.14 pi-ai `CONTEXT_SAFETY_TOKENS` (letta.js:26105). */
        const val PI_AI_CONTEXT_SAFETY_TOKENS = 4_096
    }
}

data class OnDeviceModelFollowPlan(
    val served: OnDeviceServedModel,
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

    /** What must move to [served] (model, window), from the agent and conversation records the server lists. */
    fun plan(served: OnDeviceServedModel, agents: List<JsonObject>, conversations: List<JsonObject>): OnDeviceModelFollowPlan {
        val agentModels = agents.associate { (it.id() ?: "") to it.string("model") }
        return OnDeviceModelFollowPlan(
            served = served,
            agents = agents.filter { it.needsFollow(served, it.string("model")) }.mapNotNull { it.id()?.let(::AgentId) },
            conversations = conversations
                // A conversation without its own model runs on its agent's.
                .filter { it.needsFollow(served, it.string("model") ?: agentModels[it.string("agent_id")]) }
                .mapNotNull { it.id()?.let(::ConversationId) },
        )
    }

    /** The update every planned record gets: the served model and the window letta-code can work with. */
    fun updateBody(served: OnDeviceServedModel): JsonObject = buildJsonObject {
        put("model", served.handle)
        put("model_settings", buildJsonObject { put("context_window_limit", served.lettaCodeWindow) })
    }

    private fun JsonObject.needsFollow(served: OnDeviceServedModel, model: String?): Boolean {
        if (!isOnDeviceHandle(model)) return false
        val window = ((this["model_settings"] as? JsonObject)?.get("context_window_limit") as? JsonPrimitive)?.intOrNull
        val ownModel = string("model")
        return (ownModel != null && ownModel != served.handle) || (window != null && window < served.lettaCodeWindow)
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
    suspend fun follow(served: OnDeviceServedModel): OnDeviceModelFollowPlan {
        require(OnDeviceModelFollowPolicy.isOnDeviceHandle(served.handle)) { "${served.handle} is not an on-device model handle" }
        val agents = client.agentList(AppServerCommand.AgentList(requestId("agents"), query = page()))
        val conversations = client.conversationList(AppServerCommand.ConversationList(requestId("conversations"), query = page()))
        requireDone(agents.success, agents.error)
        requireDone(conversations.success, conversations.error)
        val plan = OnDeviceModelFollowPolicy.plan(served, agents.agents.objects(), conversations.conversations.objects())
        val body = OnDeviceModelFollowPolicy.updateBody(served)
        plan.agents.forEach { id ->
            client.agentUpdate(AppServerCommand.AgentUpdate(requestId("agent"), id.value, body)).let { requireDone(it.success, it.error) }
        }
        plan.conversations.forEach { id ->
            client.conversationUpdate(AppServerCommand.ConversationUpdate(requestId("conversation"), id.value, body))
                .let { requireDone(it.success, it.error) }
        }
        return plan
    }

    /** letta-code's local store reads `limit` only as a number (a string falls back to 20). */
    private fun requireDone(success: Boolean, error: String?) = check(success) { error ?: "App Server request failed" }

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
