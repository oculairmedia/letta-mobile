package com.letta.mobile.data.transport.api

import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.transport.ChannelTransportDefaults
import com.letta.mobile.data.transport.ServerFrame

/**
 * letta-mobile-fxoew.5: optional capability of a transport whose
 * `subagent.list` can target an explicit parent conversation.
 *
 * The host list is conversation-scoped. [IChannelTransport.sendSubagentList]
 * scopes to whichever conversation the transport is viewing when the RPC
 * fires, so a caller projecting a known conversation must not depend on that
 * timing. Transports without this capability keep the call-time scope; use
 * [sendSubagentListFor] to pick the right call.
 */
interface SubagentScopeAwareChannelTransport {
    suspend fun sendSubagentListForScope(
        scope: SubagentParentScope,
        all: Boolean,
        timeoutMs: Long,
    ): ServerFrame.SubagentListResponse
}

/** Scoped `subagent.list` when the transport supports it, else the call-time scope. */
suspend fun IChannelTransport.sendSubagentListFor(
    scope: SubagentParentScope,
    all: Boolean,
    timeoutMs: Long = ChannelTransportDefaults.DEFAULT_CRON_TIMEOUT_MS,
): ServerFrame.SubagentListResponse =
    (this as? SubagentScopeAwareChannelTransport)?.sendSubagentListForScope(scope, all, timeoutMs)
        ?: sendSubagentList(all, timeoutMs)
