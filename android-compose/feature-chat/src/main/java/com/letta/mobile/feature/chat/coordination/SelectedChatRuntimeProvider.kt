package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import kotlinx.coroutines.CoroutineScope

/** App supplies captured backend storage/transport ownership. Null explicitly means legacy/local. */
interface SelectedChatRuntimeProvider {
    /** Each subscription owns its runtime handles; null explicitly selects legacy/local. */
    fun runtimes(agentId: String): kotlinx.coroutines.flow.StateFlow<SelectedChatRuntime?>
}

interface SelectedChatRuntime {
    val generation: Long
    val config: com.letta.mobile.data.model.LettaConfig
    val descriptor: com.letta.mobile.runtime.BackendDescriptor
    val scope: CoroutineScope
    val writer: com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
    /** Must complete durable handoff or explicitly defer to the usable legacy route.
     * Errors never silently fall back.
     */
    suspend fun ready(conversationId: String): SelectedTimelineRoute
    suspend fun open(conversationId: String, target: String?, scope: CoroutineScope): ChatPagingPresentation
    suspend fun retire()
}

enum class SelectedTimelineRoute { Canonical, LegacyDeferred }
