package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.extras.identity.CallerIdentity
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.HostExternalTool
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.secrets.request.RequestingAgent
import com.letta.mobile.data.secrets.request.SecretRequestLimits
import com.letta.mobile.data.secrets.request.SecretServiceError
import com.letta.mobile.data.secrets.wire.SecretRequestRouteWire
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The `secret_request` tool: an agent asks the person to type a secret into a secure form on their
 * device ([SecretRequestRoute.REQUEST]) and polls for the receipt ([SecretRequestRoute.STATUS]).
 *
 * The requesting agent is the one the host runtime stamped on the call ([ExternalToolCaller.agentId],
 * which the model cannot choose), never an argument: any argument besides `action` and the route's
 * own fields is refused (`invalid_params`). A call whose identity is not
 * [CallerIdentity.VERIFIED] (for example Meridian's env-scoped binding) or that carries no agent is
 * `forbidden`, decided before any argument is read and spelled like every other `forbidden`. The result
 * is exactly the route's result, and an error is exactly its fixed refusal code: never a value, a
 * value's length, another agent's request or a message.
 */
internal class SecretRequestTool(private val handlers: SecretRequestHandlers) : HostExternalTool {
    override val name: String = NAME

    override val description: String = DESCRIPTION

    override val inputSchema: JsonObject = INPUT_SCHEMA

    /** Host tools are offered whatever the backend advertises; this value is never used as a gate. */
    override val capability: Capability = Capability.ImageHydration

    /** The legacy entry carries a bare agent id nothing vouches for: claimed, so always refused. */
    override suspend fun invoke(input: JsonObject, agent: String?): ExternalToolResult =
        invoke(input, ExternalToolCaller(CallerIdentity.CLAIMED, agent))

    override suspend fun invoke(input: JsonObject, caller: ExternalToolCaller): ExternalToolResult {
        val agent = verifiedAgentOf(caller) ?: return refusal(SecretServiceError.FORBIDDEN)
        val route = routeOf(input[ACTION]) ?: return refusal(SecretServiceError.INVALID_PARAMS)
        val params = JsonObject(input - ACTION)
        return runCatchingCancellable { handlers.handle(route, params, SecretRequestCaller.Agent(agent), VIA_TOOL) }
            .fold(onSuccess = ::toolResult, onFailure = { ExternalToolResult.Error(INTERNAL_ERROR) })
    }

    /** The tool's name, description and schema. */
    companion object {
        /** The name the agent calls the tool by. */
        const val NAME: String = "secret_request"

        private const val ACTION = "action"
        private const val CREATE = "create"
        private const val STATUS = "status"
        private const val VIA_TOOL = "tool"
        private const val INTERNAL_ERROR = "internal_error"

        /** What the model reads about the tool. */
        const val DESCRIPTION: String =
            "Ask the person using this app to type a secret (an API key, token or password) into a secure form on their " +
                "device; the host stores it in its keystore at the item and field you name. You never see the value, its " +
                "length or anything derived from it, so never ask for a secret in chat. " +
                "It is refused (forbidden) when the host cannot verify which agent is calling. " +
                "action: create {purpose, vaultItemName, fieldName} answers {requestId, status: \"pending\", expiresAtMs}. " +
                "action: status {requestId} answers {} while the person has not answered, then " +
                "{receipt: {status: saved|cancelled|expired|failed, itemId, fieldName, reason?}}. Poll status; a request " +
                "expires 15 minutes after it is made. purpose is shown to the person labelled as written by you: plain text " +
                "only (no markup, links, line breaks or invisible characters), at most 280 characters. vaultItemName is a " +
                "path such as team/openai; fieldName is a name such as api_key; adding a new field to an existing item is " +
                "refused (target_not_writable). Errors are fixed codes such as invalid_request:purpose_too_long or unknown_request."

        private val INPUT_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject(ACTION) {
                    put("type", "string")
                    putJsonArray("enum") {
                        add(CREATE)
                        add(STATUS)
                    }
                }
                stringProperty(SecretRequestRouteWire.PURPOSE, SecretRequestLimits.MAX_PURPOSE_LENGTH)
                stringProperty(SecretRequestRouteWire.VAULT_ITEM_NAME, SecretRequestLimits.MAX_ITEM_LENGTH)
                stringProperty(SecretRequestRouteWire.FIELD_NAME, SecretRequestLimits.MAX_FIELD_LENGTH)
                stringProperty(SecretRequestRouteWire.REQUEST_ID, REQUEST_ID_LENGTH)
            }
            putJsonArray("required") { add(ACTION) }
            put("additionalProperties", false)
        }

        private const val REQUEST_ID_LENGTH = 46

        private fun routeOf(action: kotlinx.serialization.json.JsonElement?): SecretRequestRoute? =
            when ((action as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                CREATE -> SecretRequestRoute.REQUEST
                STATUS -> SecretRequestRoute.STATUS
                else -> null
            }

        private fun refusal(error: SecretServiceError): ExternalToolResult =
            ExternalToolResult.Error(SecretRouteAnswer.refused(error).text)

        private fun toolResult(answer: SecretRouteAnswer): ExternalToolResult = when (answer) {
            is SecretRouteAnswer.Ok -> ExternalToolResult.Success(answer.result.toString())
            is SecretRouteAnswer.Refused -> ExternalToolResult.Error(answer.text)
        }
    }
}

/** The requesting agent when the host or runtime vouched for it; null for a claimed or missing identity. */
private fun verifiedAgentOf(caller: ExternalToolCaller): RequestingAgent? =
    caller.agentId?.takeIf { caller.identity == CallerIdentity.VERIFIED && it.isNotBlank() }?.let { RequestingAgent(AgentId(it)) }

private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(name: String, maxLength: Int) {
    putJsonObject(name) {
        put("type", "string")
        put("maxLength", maxLength)
    }
}
