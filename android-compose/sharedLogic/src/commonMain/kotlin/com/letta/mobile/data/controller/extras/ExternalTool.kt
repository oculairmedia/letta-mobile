package com.letta.mobile.data.controller.extras

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.extras.identity.CallerIdentity
import kotlinx.serialization.json.JsonObject

/**
 * External tool abstraction for App Server controller-owned tools.
 *
 * External tools are startup-bound on runtime_start and registered by the controller
 * to extend the App Server's capabilities. Each tool corresponds to a RemoteCapabilities
 * flag and is only registered when that capability is advertised.
 *
 * PROTOCOL:
 * - Tools are registered via runtime_start (external_tools field)
 * - App Server sends ExternalToolCallRequest when a tool is invoked
 * - Controller responds with ExternalToolCallResponse (result or error)
 *
 * USAGE:
 * ```kotlin
 * val tool = ImageHydrationTool()
 * val result = tool.invoke(inputArgs)
 * ```
 */
interface ExternalTool {
    /**
     * The tool's unique name (used in tool_name field of ExternalToolCallRequest).
     */
    val name: String

    /**
     * Human-readable description of what this tool does.
     */
    val description: String

    /**
     * JSON schema for the tool's input parameters.
     *
     * This schema is sent to the App Server during registration and defines
     * the structure of the `input` field in ExternalToolCallRequest.
     *
     * If null, the tool accepts no parameters.
     */
    val inputSchema: JsonObject?

    /**
     * Which RemoteCapabilities flag advertises this tool.
     *
     * The tool is only registered when this capability is enabled.
     */
    val capability: Capability

    /**
     * Invokes the tool with the given input arguments.
     *
     * @param input The input arguments from the ExternalToolCallRequest
     * @param agentId The id of the agent invoking this tool, when known (derived
     *   from the `runtime` field of the inbound request). Tools that don't need
     *   agent context ignore it. Default `null` preserves the pre-letting
     *   agent-context contract for the existing extra tools (image_hydration,
     *   goals, etc.) which never needed to know the caller.
     * @return The tool result (success or error)
     */
    suspend fun invoke(input: JsonObject, agentId: String? = null): ExternalToolResult

    /**
     * [invoke] with the whole runtime scope of the call. A tool that needs to know which
     * conversation the agent is running in (the host's canvas tools, which let an agent open its
     * own conversation's canvas) overrides this; every other tool gets the agent id alone.
     */
    suspend fun invoke(input: JsonObject, caller: ExternalToolCaller): ExternalToolResult = invoke(input, caller.agentId)
}

/**
 * Who a tool call comes from: the runtime scope the App Server stamped on the request, which the
 * model cannot choose. [agentId] and [conversationId] are null when the frame carried no scope.
 * [identity] says whether [agentId] is [CallerIdentity.VERIFIED] or only [CallerIdentity.CLAIMED].
 *
 * [toolCallId] is the request's `tool_call_id`: the id the model's tool call has in the run, the
 * same one the timeline's TOOL_CALL event and its return are keyed. A
 * tool that must answer a retried call the same way (canvas_compose derives its artifact id from
 * it) reads it; null when the call came some other way.
 *
 * Anyone may build a [claimed] caller. A verified one is made only inside this module, by
 * [verified], and only where the host or runtime itself vouches for the scope: the App Server
 * dispatcher (for a client that owns its App Server connection) and the Meridian live-call binder.
 * A guard test fails if any other production file calls it. There is no public constructor and no
 * `copy`: [withArguments] keeps verification only while the identity fields stay the same.
 */
class ExternalToolCaller private constructor(
    /** Whether [agentId] was stamped by the host or runtime, or only claimed. */
    val identity: CallerIdentity,
    /** The agent the call is for, or null when the frame carried no scope. */
    val agentId: String?,
    /** The conversation the call is for, or null. */
    val conversationId: String?,
    /** The model's tool call id, or null. */
    val toolCallId: String?,
) {
    /** Whether the host or runtime vouched for [agentId] ([CallerIdentity.VERIFIED]). */
    val isVerified: Boolean get() = identity == CallerIdentity.VERIFIED

    /**
     * This caller with other values. It stays verified only when [agentId] and [conversationId]
     * are unchanged; changing either makes it claimed, so a verified scope cannot be moved to
     * another agent or conversation. [toolCallId] is not identity and may change freely.
     */
    fun withArguments(
        agentId: String? = this.agentId,
        conversationId: String? = this.conversationId,
        toolCallId: String? = this.toolCallId,
    ): ExternalToolCaller {
        val sameScope = agentId == this.agentId && conversationId == this.conversationId
        return ExternalToolCaller(if (sameScope) identity else CallerIdentity.CLAIMED, agentId, conversationId, toolCallId)
    }

    @Suppress("NoAnyType") // The equals signature.
    override fun equals(other: Any?): Boolean = other is ExternalToolCaller && fields() == other.fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String =
        "ExternalToolCaller(identity=$identity, agentId=$agentId, conversationId=$conversationId, toolCallId=$toolCallId)"

    private fun fields(): Fields = Fields(identity, agentId, conversationId, toolCallId)

    /** Value equality without exposing a data class (and its `copy`). */
    private data class Fields(
        val identity: CallerIdentity,
        val agentId: String?,
        val conversationId: String?,
        val toolCallId: String?,
    )

    /** The only ways to make a caller. */
    companion object {
        /** A caller whose scope nothing vouches for: an argument, an environment variable, a relayed frame. */
        fun claimed(agentId: String?, conversationId: String? = null, toolCallId: String? = null): ExternalToolCaller =
            ExternalToolCaller(CallerIdentity.CLAIMED, agentId, conversationId, toolCallId)

        /**
         * A caller whose scope the host or runtime vouches for. Only the App Server dispatcher and
         * the Meridian live-call binder may call this (pinned by a guard test).
         */
        internal fun verified(agentId: String?, conversationId: String? = null, toolCallId: String? = null): ExternalToolCaller =
            ExternalToolCaller(CallerIdentity.VERIFIED, agentId, conversationId, toolCallId)
    }
}

/**
 * Host-owned external tool that is available because of the client device, not
 * because the remote App Server advertised an extension capability.
 */
interface HostExternalTool : ExternalTool

/**
 * Result of an external tool invocation.
 */
sealed interface ExternalToolResult {
    /**
     * Tool invocation succeeded.
     *
     * @param content The result content (text, JSON, etc.)
     */
    data class Success(val content: String) : ExternalToolResult

    /**
     * Tool invocation failed.
     *
     * @param error The error message
     */
    data class Error(val error: String) : ExternalToolResult
}
