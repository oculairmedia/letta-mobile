package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.ui.chat.render.ChatRenderItemState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf

/**
 * letta-mobile-bglj6.1: the slim, stable per-row inputs. Rows read only this (never the
 * whole ChatUiState) so a streaming tick on the newest row doesn't recompose settled rows.
 */
@Immutable
internal data class ChatRowContext(
    val itemState: ChatRenderItemState,
    /** The assistant message currently streaming, or null when the run is idle. */
    val streamingMessageId: String? = null,
    val a2uiSurfaces: ImmutableMap<String, A2uiSurfaceState> = persistentMapOf(),
    val a2uiResolvedActionCounters: ImmutableMap<String, Int> = persistentMapOf(),
    val displayMode: ChatDisplayMode = ChatDisplayMode.Interactive,
    val capabilities: ChatSurfaceCapabilities = ChatSurfaceCapabilities.Default,
    val fontScale: Float = 1f,
)

/**
 * The row-level intents, bound once per page from [ChatActions] and [ChatSurfaceHost]. A
 * plain class held in `remember`, so its identity is stable and rows stay skippable.
 */
@Stable
internal class ChatRowCallbacks(
    val actions: ChatActions,
    val host: ChatSurfaceHost,
    /** Opens the full-screen image viewer on [index] of [images]. */
    val onImageTap: (images: List<UiImageAttachment>, index: Int) -> Unit,
)
