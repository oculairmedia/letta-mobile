package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Immutable
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform

/**
 * letta-mobile-bglj6.1: everything one composer frame reads, bundled so the parts take one
 * parameter instead of nine. Rebuilt per composition of [ChatComposerPanel].
 */
@Immutable
internal data class ComposerModel(
    val composer: ChatComposerUiState,
    val uiState: ChatUiState,
    val actions: ChatActions,
    val capabilities: ChatSurfaceCapabilities,
    val host: ChatSurfaceHost,
    val platform: ChatSurfacePlatform,
    val mode: ChatSurfaceMode,
    val onIntent: (ChatSurfaceIntent) -> Unit,
    val decisions: ComposerDecisions,
) {
    val streaming: Boolean get() = uiState.isStreaming

    /** The working-directory row shows only for owners whose backend has one. */
    val showWorkingDirectory: Boolean
        get() = capabilities.workingDirectory && composer.workingDirectory != null

    val showModel: Boolean get() = capabilities.modelSwitch && composer.model != null

    /** "Open canvas" belongs to the full-screen page: docked, the canvas is already on screen. */
    val offersOpenCanvas: Boolean get() = mode == ChatSurfaceMode.FullScreen

    /** Sends (or, during a run with nothing to queue, stops). */
    fun runAction() {
        when (decisions.action) {
            ComposerAction.Stop -> actions.stopRun()
            ComposerAction.Send -> if (decisions.sendEnabled) actions.send()
        }
    }
}
