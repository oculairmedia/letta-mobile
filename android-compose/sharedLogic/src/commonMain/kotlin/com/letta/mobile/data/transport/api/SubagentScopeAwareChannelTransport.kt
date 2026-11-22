package com.letta.mobile.data.transport.api

import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.transport.ChannelTransportDefaults
import com.letta.mobile.data.transport.ServerFrame

/**
 * Optional capability of a transport whose
 * `subagent.list` can target an explicit parent conversation.
 *
 * The host list is conversation-scoped. [IChannelTransport.sendSubagentList]
 * scopes to whichever conversation the transport is viewing when the RPC
 * fires, so a caller projecting a known conversation must not depend on that
 * timing. Transports without this capability keep the call-time scope; use
 * [sendSubagentListFor] to pick the right call.
 */
interface SubagentScopeAwareChannelTransport {
    /** Dismiss an exact scoped dispatch; unsupported transports fail closed. */
    suspend fun dismissSubagent(
        scope: SubagentParentScope,
        toolCallId: com.letta.mobile.runtime.ToolCallId,
    ): Result<List<com.letta.mobile.data.model.SubagentEntry>> =
        Result.failure(UnsupportedOperationException("durable subagent dismissal unavailable"))

    suspend fun sendSubagentListForScope(
        scope: SubagentParentScope,
        all: Boolean,
        timeoutMs: Long,
    ): ServerFrame.SubagentListResponse

    /**
     * True when the host pushes [scope]'s registry changes to this connection: it advertises the
     * registry push and this connection views [scope]'s conversation (pushes reach only viewers).
     * Asking such a host again while nothing changed would only poll. Default: no.
     */
    fun receivesSubagentPushes(scope: SubagentParentScope): Boolean = false
}

/** [SubagentScopeAwareChannelTransport.receivesSubagentPushes], false for any other transport. */
fun IChannelTransport.receivesSubagentPushesFor(scope: SubagentParentScope): Boolean =
    (this as? SubagentScopeAwareChannelTransport)?.receivesSubagentPushes(scope) == true

/** Scoped `subagent.list` when the transport supports it, else the call-time scope. */
suspend fun IChannelTransport.sendSubagentListFor(
    scope: SubagentParentScope,
    all: Boolean,
    timeoutMs: Long = ChannelTransportDefaults.DEFAULT_CRON_TIMEOUT_MS,
): ServerFrame.SubagentListResponse =
    (this as? SubagentScopeAwareChannelTransport)?.sendSubagentListForScope(scope, all, timeoutMs)
        ?: sendSubagentList(all, timeoutMs)
