package com.letta.mobile.data.controller.extras.identity

/**
 * Where a tool caller's `ExternalToolCaller.agentId` came from (letta-mobile-n2stx.2.4). Every
 * construction site must choose; there is no default, because assuming [VERIFIED] would be a silent
 * hole. A security-sensitive tool (one that acts on per-agent state the agent must not reach for
 * another agent, such as `secret_request`) must refuse anything but [VERIFIED].
 */
enum class CallerIdentity {
    /**
     * Stamped by the host or runtime from state the agent process cannot influence: the runtime
     * scope the App Server put on an external tool call frame, or the scope of the live shell call a
     * Meridian tool token was issued for.
     */
    VERIFIED,

    /**
     * Taken from something the agent process controls or that this process cannot vouch for:
     * environment variables (Meridian env-scoped binding), a bare agent id handed to the legacy
     * `invoke(input, agentId)` entry, or a scope relayed from another host.
     */
    CLAIMED,
}
