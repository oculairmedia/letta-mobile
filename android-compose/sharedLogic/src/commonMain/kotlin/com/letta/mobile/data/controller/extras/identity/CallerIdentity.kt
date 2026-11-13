package com.letta.mobile.data.controller.extras.identity

/**
 * Where a tool caller's `ExternalToolCaller.agentId` came from. A caller is built as claimed by
 * anyone (`claimedToolCaller`) and as [VERIFIED] only by the App Server dispatcher and the
 * Meridian live-call binder (`verifiedToolCaller`, internal and pinned by a guard test).
 * A security-sensitive tool (one that acts on per-agent state the agent must not reach for
 * another agent, such as `secret_request`) must refuse anything but [VERIFIED].
 */
enum class CallerIdentity {
    /**
     * Stamped by the host or runtime from state the agent process cannot influence: the runtime
     * scope the App Server put on an external tool call frame, delivered by a client that owns its
     * App Server connection, or the scope of the live shell call a Meridian tool token was issued
     * for. (A Meridian token can be requested by any local process of the same OS user, so this
     * does not separate agents that share one OS user.)
     */
    VERIFIED,

    /**
     * Taken from something the agent process controls or that this process cannot vouch for:
     * environment variables (Meridian env-scoped binding), a bare agent id handed to the legacy
     * `invoke(input, agentId)` entry, or a scope relayed from another host.
     */
    CLAIMED,
}
