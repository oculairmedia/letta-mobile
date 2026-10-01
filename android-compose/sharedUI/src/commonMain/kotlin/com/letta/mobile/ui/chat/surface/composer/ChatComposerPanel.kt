package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.QueuedSendActions
import com.letta.mobile.ui.chat.QueuedSendsPanel
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.surface.ChatSurfacePlatform
import com.letta.mobile.ui.theme.LettaDimens

/**
 * letta-mobile-bglj6.1: the shared composer: queued sends, the prompt card, autocomplete,
 * attachments, model/context/working-directory chrome, send and stop.
 *
 * In [ChatSurfaceMode.Docked] it is the canvas's chat bar (one line, same draft, same send,
 * with an expand control raising [ChatSurfaceIntent.Expand]); in [ChatSurfaceMode.FullScreen]
 * (and the floating panel) it is the page's composer, where swipe-up on the prompt card raises
 * [ChatSurfaceIntent.OpenCanvas].
 */
@Composable
internal fun ChatComposerPanel(
    composer: ChatComposerUiState,
    uiState: ChatUiState,
    actions: ChatActions,
    capabilities: ChatSurfaceCapabilities,
    host: ChatSurfaceHost,
    platform: ChatSurfacePlatform,
    mode: ChatSurfaceMode,
    onIntent: (ChatSurfaceIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val decisions = remember(composer, uiState.isStreaming, uiState.isCancellingRun) {
        ComposerDecisions.of(composer, uiState)
    }
    val model = ComposerModel(
        composer = composer,
        uiState = uiState,
        actions = actions,
        capabilities = capabilities,
        host = host,
        platform = platform,
        mode = mode,
        onIntent = onIntent,
        decisions = decisions,
    )
    val attachImage = rememberComposerImagePicker(
        ComposerImagePickerTarget(
            maxAttachments = composer.maxAttachments,
            pendingCount = composer.attachments.size,
            onPicked = actions::attachImage,
            onError = actions::reportComposerError,
        ),
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        ComposerAboveCard(model)
        if (mode == ChatSurfaceMode.Docked) {
            DockedComposerBar(model, attachImage)
        } else {
            ComposerCompanionRow(model) { ComposerPromptCard(model, attachImage) }
            ComposerHintRow(visible = composerHintVisible(composer.text, composer.attachments.isNotEmpty()))
        }
    }
}

/** What stacks above the prompt: the queue, the error, the working directory, suggestions. */
@Composable
private fun ComposerAboveCard(model: ComposerModel) {
    val actions = model.actions
    QueuedSendsPanel(
        queue = model.uiState.sendQueue,
        actions = remember(actions) {
            QueuedSendActions(
                onCancel = actions::cancelQueuedSend,
                onSendNow = actions::sendQueuedNow,
                onResume = { actions.resumeSendQueue() },
            )
        },
        modifier = Modifier.widthIn(max = ChatColumnMaxWidth),
    )
    model.composer.error?.let { message -> ComposerErrorRow(message = message, onDismiss = actions::clearComposerError) }
    // The dock stays one compact bar; the directory is chrome of the full page.
    if (model.showWorkingDirectory && model.mode != ChatSurfaceMode.Docked) {
        model.composer.workingDirectory?.let { ComposerWorkingDirectoryRow(it, model.host.pickWorkingDirectory) }
    }
    ComposerSuggestions(model.composer, model.decisions.autocomplete, actions)
}
