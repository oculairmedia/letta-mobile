package com.letta.mobile.feature.chat.subagent

import com.letta.mobile.data.model.SubagentTodo
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * letta-mobile-7vs4s: gate the active-subagent bar to EMPTY while the active
 * agent is bound to the LOCAL embedded runtime.
 *
 * WHY: the production active-subagent bar is fed by [WsActiveSubagentSource],
 * which reads the shim's per-socket subagent registry over the WS. On the
 * LOCAL embedded runtime there is NO shim WS subagent feed, so the WS source
 * never receives the running→terminal transitions its [WsActiveSubagentSource.LingerAccumulator]
 * needs to clear a chip. The result was phantom chips that appeared on every
 * prompt and never cleared (confirmed on the production app, local runtime).
 *
 * This wrapper delegates to the real [delegate] source but, while
 * [isLocalBound] is true, suppresses the emitted set to empty so local-runtime
 * turns produce no phantom perpetual chips. When bound to a remote shim it
 * passes the real registry through unchanged. The gate is REACTIVE (driven by
 * [isLocalBoundFlow]) so switching an agent between local and remote flips the
 * bar live without reconstructing the source.
 *
 * Resolver methods (todos / resolveConversationId / resolveSubagent) delegate
 * to [delegate]; while local-bound the emitted set is empty so the bar won't
 * surface a chip to resolve anyway.
 */
class LocalAwareActiveSubagentSource(
    private val delegate: ActiveSubagentSource,
    isLocalBoundFlow: StateFlow<Boolean>,
    scope: CoroutineScope,
) : ActiveSubagentSource {

    override val activeSubagents: StateFlow<ImmutableList<ActiveSubagent>> =
        combine(delegate.activeSubagents, isLocalBoundFlow) { subagents, localBound ->
            if (localBound) persistentListOf() else subagents
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = persistentListOf(),
        )

    override suspend fun todos(toolCallId: String): Result<List<SubagentTodo>> =
        delegate.todos(toolCallId)

    override suspend fun resolveConversationId(subagent: ActiveSubagent): Result<String?> =
        delegate.resolveConversationId(subagent)

    override suspend fun resolveSubagent(id: String): Result<ActiveSubagent?> =
        delegate.resolveSubagent(id)

    private companion object {
        // Mirror WsActiveSubagentSource's share keep-alive so the gate does not
        // tear down its upstream during brief unsubscribes (recompositions).
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
