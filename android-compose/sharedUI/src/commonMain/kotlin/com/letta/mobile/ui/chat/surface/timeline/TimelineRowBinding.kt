package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiImageAttachment
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
) {
    fun forItem(item: ChatRenderItem): ChatRowContext {
        val id = streaming?.streamingMessageId ?: return base
        return if (item.containsMessageId(id)) streaming else base
    }
}

/** The newest assistant message while a run streams; null when idle. */
internal fun streamingMessageIdOf(state: ChatUiState): String? =
    if (state.isStreaming) state.messages.lastOrNull { it.role == "assistant" }?.id else null

@Composable
internal fun rememberRowContexts(
    state: ChatUiState,
    capabilities: ChatSurfaceCapabilities,
    appearance: ChatSurfaceAppearance,
    fontScale: Float,
): TimelineRowContexts {
    val itemState = state.toChatRenderItemState()
    val streamingId = streamingMessageIdOf(state)
    return remember(
        itemState,
        streamingId,
        state.a2uiSurfaces,
        state.a2uiResolvedActionCounters,
        appearance.displayMode,
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
        )
        TimelineRowContexts(base, streamingId?.let { base.copy(streamingMessageId = it) })
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
        ChatRowCallbacks(actions = actions, host = host, onImageTap = { images, index -> currentTap.value(images, index) })
    }
}
