package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The `admin_rpc` pair through which an Iroh client reads and changes the
 * permission mode of a conversation's runtime on its host (the host runs the turns, so its runtime's
 * mode is the one that decides which tool calls wait on the person).
 *
 * - `permission_mode.get {agent_id, conversation_id}` -> `{mode}` (`mode` absent when the host has
 *   not started that runtime and no client chose one).
 * - `permission_mode.set {agent_id, conversation_id, mode}` -> `{mode, confirmed}`: the host puts the
 *   runtime in [mode] (starting it when needed) through `change_device_state` and keeps it there for
 *   later starts; `confirmed` is true once the App Server runs it in that mode.
 */
object PermissionModeRpc {
    /** Advertised by a host that serves both methods. */
    const val CAPABILITY: String = "permission_mode_v1"

    const val GET: String = "permission_mode.get"
    const val SET: String = "permission_mode.set"
    val methods: Set<String> = setOf(GET, SET)

    const val AGENT_ID = "agent_id"
    const val CONVERSATION_ID = "conversation_id"
    const val MODE = "mode"
    const val CONFIRMED = "confirmed"

    fun params(agentId: String, conversationId: String, mode: AppServerPermissionMode? = null): JsonObject = buildJsonObject {
        put(AGENT_ID, agentId)
        put(CONVERSATION_ID, conversationId)
        mode?.let { put(MODE, it.wireValue()) }
    }

    fun result(mode: AppServerPermissionMode?, confirmed: Boolean? = null): JsonObject = buildJsonObject {
        mode?.let { put(MODE, it.wireValue()) }
        confirmed?.let { put(CONFIRMED, it) }
    }

    /** The mode a result names; null when absent or not a mode this client knows. */
    fun modeOf(result: JsonElement?): AppServerPermissionMode? =
        ((result as? JsonObject)?.get(MODE) as? JsonPrimitive)?.contentOrNull?.let(AppServerPermissionMode::fromWireValue)

    fun confirmedOf(result: JsonElement?): Boolean =
        ((result as? JsonObject)?.get(CONFIRMED) as? JsonPrimitive)?.booleanOrNull == true

    /** The wire name of [mode], as `runtime_start` and `change_device_state` spell it. */
    fun wire(mode: AppServerPermissionMode): String = mode.wireValue()

    /**
     * The host's push after a CONFIRMED `set`, to every viewer of the conversation,
     * so a change made on one client reaches the chips of the others without reopening the chat:
     * `permission_mode {agent_id, conversation_id, mode, at}`.
     */
    const val FRAME_TYPE: String = "permission_mode"

    fun encodeFrame(agentId: String, conversationId: String, mode: AppServerPermissionMode, at: String): JsonObject = buildJsonObject {
        put("v", 1)
        put("type", FRAME_TYPE)
        put("id", "permission-mode-$conversationId-$at")
        put("ts", at)
        put(AGENT_ID, agentId)
        put(CONVERSATION_ID, conversationId)
        put(MODE, mode.wireValue())
        put("at", at)
    }
}
