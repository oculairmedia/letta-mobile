package com.letta.mobile.data.controller.extras.scope

/**
 * Where a tool caller's agent and conversation scope came from, and so whether anything may be granted on it.
 *
 * Only a scope the host itself established is [RUNTIME_STAMPED]: the App Server's runtime stamp on a tool-call
 * frame, or a Meridian live-call token this host issued for one in-flight call. Everything else is [CLAIMED]: ids
 * the agent can set (shell environment variables, tool arguments), or supplied by a client. A claimed scope still
 * names who a call says it is for ACL checks, but grants derived from that scope (a conversation's canvas mount)
 * never apply to it. Callers are [CLAIMED] unless built from a runtime stamp.
 */
enum class CallerScopeTrust {
    /** Stamped by the App Server runtime or vouched for by a live-call token this host issued. */
    RUNTIME_STAMPED,

    /** Claimed by the caller (environment, arguments, client), never verified by this host. */
    CLAIMED,
}
