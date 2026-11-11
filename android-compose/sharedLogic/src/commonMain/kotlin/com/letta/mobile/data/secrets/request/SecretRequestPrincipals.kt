package com.letta.mobile.data.secrets.request

import com.letta.mobile.data.model.AgentId

/** How a call reached the host, as the serving transport knows it (never as the caller claims it). */
enum class PrincipalOrigin {
    /** A human-initiated call on a paired device's authenticated control connection. */
    HUMAN_PAIRED_DEVICE,

    /** A message from another agent over agent-to-agent transport. */
    AGENT_A2A,

    /** Input that arrived through the App Server turn pipeline (a chat message or turn input). */
    AGENT_APP_SERVER_INPUT,

    /** A tool call made by an agent. */
    AGENT_TOOL_CALL,

    /** Host-internal maintenance. */
    HOST_INTERNAL,
    ;

    /** Whether this origin is a human acting on a paired device. */
    val isHuman: Boolean get() = this == HUMAN_PAIRED_DEVICE
}

/** The access level the serving transport verified for the caller. */
enum class PrincipalAccess {
    /** The paired device holds `admin.full`. */
    ADMIN_FULL,

    /** Anything less than `admin.full`. */
    LIMITED,
}

/**
 * Who answers a secret request (submit or cancel). Only the serving transport may build one, from
 * the identity it verified for the connection: the authenticated endpoint id of a paired device's
 * Iroh control connection (the admin RPC request context's authenticated endpoint), its verified
 * `admin.full` capability, and the fact that the call is a human-initiated admin RPC on that
 * connection. It is never built from request parameters, tool arguments, frame fields or anything
 * an agent can write.
 *
 * The service refuses every principal for which [mayAnswer] is false, including every agent origin.
 */
data class SecretRequestPrincipal(
    /** The verified endpoint id of the device. */
    val endpoint: VerifiedEndpointId,
    /** How the call reached the host. */
    val origin: PrincipalOrigin,
    /** The verified access level. */
    val access: PrincipalAccess,
) {
    /** Whether this principal may submit or cancel: a human on a paired device with `admin.full`. */
    val mayAnswer: Boolean get() = isHumanAdmin() && endpoint.isWellFormed

    private fun isHumanAdmin(): Boolean = origin.isHuman && access == PrincipalAccess.ADMIN_FULL
}

/**
 * The agent a request belongs to, as the host verified it (the agent whose turn made the
 * `secret.request` tool call), never as a tool argument names it. An agent may create requests and
 * observe the receipts of its own requests only.
 */
data class RequestingAgent(
    /** The verified agent id. */
    val agentId: AgentId,
)
