package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.secrets.request.PrincipalAccess
import com.letta.mobile.data.secrets.request.PrincipalOrigin
import com.letta.mobile.data.secrets.request.RequestingAgent
import com.letta.mobile.data.secrets.request.SecretRequestPrincipal
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.data.secrets.request.VerifiedEndpointId

/**
 * Who a call comes from, as the transport that carried it established it. Neither kind is ever
 * built from params or tool arguments:
 *
 * - [Agent] is built only by the `secret_request` tool, from the agent id the host runtime stamped
 *   on the tool call ([com.letta.mobile.data.controller.extras.ExternalToolCaller.agentId]);
 * - [Connection] is built only by the admin RPC registration, from the request context the serving
 *   Iroh connection made. No connection is ever an agent.
 */
internal sealed interface SecretRequestCaller {
    /** The requesting agent of a tool call. */
    class Agent(
        /** The verified agent. */
        val agent: RequestingAgent,
    ) : SecretRequestCaller

    /** An admin RPC connection. */
    class Connection(
        /** What the serving connection verified. */
        val context: AdminRpcRequestContext,
    ) : SecretRequestCaller
}

/**
 * The human side of a connection: the principal the service checks and the agents the paired
 * device may see ([AdminRpcRequestContext.authorizedAgentIds], null = every agent).
 */
internal class HumanCaller private constructor(
    /** The verified human principal. */
    val principal: SecretRequestPrincipal,
    private val agentScope: Set<String>?,
) {
    /** Whether [record] belongs to an agent this device may see. */
    fun sees(record: SecretRequestRecord): Boolean = agentScope?.contains(record.details.requestingAgent.value) ?: true

    /** Derivation from the connection. */
    companion object {
        private val ENDPOINT_ID = Regex("[0-9a-f]{64}")

        /**
         * The human principal [context] establishes, or null. All of these must hold, each taken
         * from the serving connection: authenticated; a well-formed verified endpoint id; a paired
         * device by its stored record (a bootstrap session, such as a host-local process holding an
         * admin token, is not: owner decision 7); and `admin.full` among the granted capabilities.
         */
        fun of(context: AdminRpcRequestContext): HumanCaller? {
            val endpoint = context.authenticatedEndpointId?.takeIf { context.authenticated } ?: return null
            val verified = listOf(
                ENDPOINT_ID.matches(endpoint),
                context.pairedDevice,
                IrohPeerCapabilities.ADMIN_FULL in context.grantedCapabilities,
            ).all { it }
            if (!verified) return null
            val principal = SecretRequestPrincipal(
                VerifiedEndpointId(endpoint),
                PrincipalOrigin.HUMAN_PAIRED_DEVICE,
                PrincipalAccess.ADMIN_FULL,
            )
            return HumanCaller(principal, context.authorizedAgentIds)
        }
    }
}
