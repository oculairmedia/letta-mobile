package com.letta.mobile.ui.chat.surface.timeline.rows

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatRenderItemState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import kotlinx.collections.immutable.toImmutableSet

internal fun rowContext(
    capabilities: ChatSurfaceCapabilities = ChatSurfaceCapabilities.Default,
    collapsedRunIds: Set<String> = emptySet(),
    expandedReasoning: Set<String> = emptySet(),
    activeApprovalRequestId: String? = null,
    newestMessageId: String? = null,
): ChatRowContext = ChatRowContext(
    newestMessageId = newestMessageId,
    itemState = ChatRenderItemState(
        isStreaming = false,
        activeApprovalRequestId = activeApprovalRequestId,
        collapsedRunIds = collapsedRunIds.toImmutableSet(),
        expandedReasoningMessageIds = expandedReasoning.toImmutableSet(),
    ),
    capabilities = capabilities,
)

internal fun rowCallbacks(
    actions: ChatActions = RecordingChatActions(),
    host: ChatSurfaceHost = ChatSurfaceHost(),
    resolveAgentName: (String) -> String? = { null },
    openSubagent: ((ChatSubagentTarget) -> Unit)? = null,
): ChatRowCallbacks = ChatRowCallbacks(
    actions = actions,
    host = host,
    onImageTap = { _, _ -> },
    resolveAgentName = resolveAgentName,
    openSubagent = openSubagent,
)

internal fun message(
    id: String,
    role: String,
    content: String,
    timestamp: String = "2026-07-19T12:00:00Z",
) = UiMessage(id = id, role = role, content = content, timestamp = timestamp)
