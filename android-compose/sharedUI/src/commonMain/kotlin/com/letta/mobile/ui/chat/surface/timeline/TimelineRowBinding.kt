package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.toChatRenderItemState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowCallbacks
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowContext

/**
 * letta-mobile-bglj6.1: the per-row inputs, built once per state change and handed to every row.
 *
 * Rows read only [ChatRowContext]. Every row gets the SAME [base] instance, so a settled row's
 * inputs compare equal across a streaming tick and the row skips. Only the row holding the
 * streaming message gets [streaming], which carries the streaming id.
 */
@Immutable
internal class TimelineRowContexts(
    val base: ChatRowContext,
    private val streaming: ChatRowContext?,
    /** The conversation's newest message; the row holding it gets [ChatRowContext.newestMessageId]. */
    private val newestMessageId: String? = null,
) {
    private val newest: ChatRowContext? = newestMessageId?.let { base.copy(newestMessageId = it) }
    private val streamingNewest: ChatRowContext? = newestMessageId?.let { streaming?.copy(newestMessageId = it) }

    /**
     * These contexts with [id] as the conversation's newest message. A list whose newest row is
     * not the owner state's last message (the paged route, whose live overlay leads) names its
     * own, so a settled run's header never lingers over a newer prompt.
     */
    fun withNewest(id: String?): TimelineRowContexts =
        if (id == newestMessageId) this else TimelineRowContexts(base, streaming, id)

    fun forItem(item: ChatRenderItem): ChatRowContext {
        val holdsNewest = newestMessageId != null && item.containsMessageId(newestMessageId)
        val streamingId = streaming?.streamingMessageId
        val holdsStreaming = streamingId != null && item.containsMessageId(streamingId)
        return when {
            holdsStreaming && holdsNewest -> streamingNewest ?: base
            holdsStreaming -> streaming ?: base
            holdsNewest -> newest ?: base
            else -> base
        }
    }
}

/** The newest assistant message while a run streams; null when idle. */
internal fun streamingMessageIdOf(state: ChatUiState): String? = streamingAssistantOf(state)?.id

/**
 * The reply the running turn is writing: an assistant message after the newest user prompt.
 * Right after a send nothing answers that prompt yet, so this is null; picking the previous
 * reply instead would mark it streaming and replay its reveal from an empty string.
 */
internal fun streamingAssistantOf(state: ChatUiState): UiMessage? {
    if (!state.isStreaming) return null
    for (i in state.messages.indices.reversed()) {
        val message = state.messages[i]
        when (message.role) {
            "assistant" -> return message
            "user" -> return null
        }
    }
    return null
}

@Composable
internal fun rememberRowContexts(
    state: ChatUiState,
    capabilities: ChatSurfaceCapabilities,
    appearance: ChatSurfaceAppearance,
    fontScale: Float,
): TimelineRowContexts {
    val itemState = state.toChatRenderItemState()
    val streamingId = streamingMessageIdOf(state)
    val newestId = state.messages.lastOrNull()?.id
    return remember(
        itemState,
        streamingId,
        newestId,
        state.a2uiSurfaces,
        state.a2uiResolvedActionCounters,
        appearance.displayMode,
        appearance.toolDetails,
        capabilities,
        fontScale,
    ) {
        val base = ChatRowContext(
            itemState = itemState,
            a2uiSurfaces = state.a2uiSurfaces,
            a2uiResolvedActionCounters = state.a2uiResolvedActionCounters,
            displayMode = appearance.displayMode,
            capabilities = capabilities,
            fontScale = fontScale,
            toolDetails = appearance.toolDetails,
        )
        TimelineRowContexts(base, streamingId?.let { base.copy(streamingMessageId = it) }, newestId)
    }
}

/**
 * ONE [ChatRowCallbacks] for the page. Its identity never changes, so rows stay skippable; the
 * lambdas read the latest [actions]/[host] through updated state.
 */
@Composable
internal fun rememberRowCallbacks(
    actions: ChatActions,
    host: ChatSurfaceHost,
    onImageTap: (images: List<UiImageAttachment>, index: Int) -> Unit,
): ChatRowCallbacks {
    val currentTap = rememberUpdatedState(onImageTap)
    return remember(actions, host) {
        rowCallbacksFor(actions, host) { images, index -> currentTap.value(images, index) }
    }
}

/**
 * Binds the rows' host affordances: agent display names and the subagent opener come from
 * [host]; a host without them leaves the short id label and hides the subagent affordance.
 */
internal fun rowCallbacksFor(
    actions: ChatActions,
    host: ChatSurfaceHost,
    onImageTap: (images: List<UiImageAttachment>, index: Int) -> Unit,
): ChatRowCallbacks {
    val resolveName = host.resolveAgentName
    val open = host.openSubagent
    return ChatRowCallbacks(
        actions = actions,
        host = host,
        onImageTap = onImageTap,
        resolveAgentName = resolveName ?: { null },
        openSubagent = open?.let { { target -> it(target.toolCallId, target.subagentAgentId, target.description) } },
    )
}
