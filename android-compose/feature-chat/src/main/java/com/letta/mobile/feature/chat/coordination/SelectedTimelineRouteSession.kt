package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import kotlinx.coroutines.CoroutineScope

/**
 * Pairs captured-runtime readiness with presentation and the legacy observer.
 * Send still resolves through [SelectedChatRuntime.writer] after the same [SelectedChatRuntime.ready]
 * cache, so deferred conversations cannot open canonical paging or write the canonical store.
 */
internal class SelectedTimelineRouteSession(
    private val startLegacyObserver: (String) -> Unit,
    private val stopLegacyObserver: () -> Unit,
) {
    sealed class Presentation {
        data class Canonical(val value: ChatPagingPresentation) : Presentation()
        data object LegacyDeferred : Presentation()
    }

    suspend fun decide(
        runtime: SelectedChatRuntime?,
        conversationId: String,
        target: String?,
        scope: CoroutineScope,
        agentId: String,
        hostOpen: (suspend (String, String, String?, CoroutineScope) -> ChatPagingPresentation)?,
    ): Presentation {
        val route = runtime?.ready(conversationId) ?: SelectedTimelineRoute.Canonical
        if (route == SelectedTimelineRoute.LegacyDeferred) return Presentation.LegacyDeferred
        val opened = if (runtime != null) {
            runtime.open(conversationId, target, scope)
        } else {
            checkNotNull(hostOpen).invoke(agentId, conversationId, target, scope)
        }
        return Presentation.Canonical(opened)
    }

    fun activateDeferred(conversationId: String) {
        startLegacyObserver(conversationId)
    }

    fun retirePresentation() {
        stopLegacyObserver()
    }
}
