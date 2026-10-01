package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.theme.ChatComposerDimens
import com.letta.mobile.ui.theme.LettaDimens

private val FullFieldStyle = ComposerFieldStyle(
    testTag = ComposerTestTags.INPUT,
    maxHeight = ChatComposerDimens.promptMaxHeight,
    singleLine = false,
    maxLines = 5,
)

private val DockedFieldStyle = ComposerFieldStyle(
    testTag = ComposerTestTags.DOCKED_INPUT,
    maxHeight = ChatComposerDimens.dockedFieldMaxHeight,
    singleLine = true,
    maxLines = 1,
)

/** The keyboard is up (always false on desktop, where there is no IME inset). */
@Composable
internal fun keyboardOpen(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0

/**
 * The full composer's prompt card: staged images, the prompt field and the control row.
 *
 * In [ChatSurfaceMode.FullScreen] a swipe up on the card raises
 * [ChatSurfaceIntent.OpenCanvas], except while the keyboard is open or a run streams.
 */
@Composable
internal fun ComposerPromptCard(model: ComposerModel, onAttachImage: () -> Unit) {
    val swipeEnabled = model.offersOpenCanvas && !model.streaming && !keyboardOpen()
    Surface(
        // Order matters: widthIn BEFORE fillMaxWidth, or fillMaxWidth pins min == max and the cap is lost.
        modifier = Modifier
            .widthIn(max = ChatColumnMaxWidth)
            .fillMaxWidth()
            .swipeUpToCanvas(enabled = swipeEnabled, onTrigger = { model.onIntent(ChatSurfaceIntent.OpenCanvas) })
            .testTag(ComposerTestTags.CARD),
        shape = RoundedCornerShape(LettaDimens.Radius.lg),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // Low-contrast hairline: the fill alone is barely a step off the page background.
        border = BorderStroke(
            LettaDimens.Stroke.hairline,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            ComposerAttachmentStrip(attachments = model.composer.attachments, onRemove = model.actions::removeAttachment)
            ComposerTextField(model = model, style = FullFieldStyle)
            ComposerControlRow(model = model, onAttachImage = onAttachImage)
        }
    }
}

/**
 * The docked bar: the same draft and the same send in one line, with an expand control that
 * raises [ChatSurfaceIntent.Expand] to open the full page.
 */
@Composable
internal fun DockedComposerBar(model: ComposerModel, onAttachImage: () -> Unit) {
    Surface(
        modifier = Modifier
            .widthIn(max = ChatColumnMaxWidth)
            .fillMaxWidth()
            .testTag(ComposerTestTags.DOCKED_BAR),
        shape = RoundedCornerShape(LettaDimens.Radius.lg),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            LettaDimens.Stroke.hairline,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ComposerExpandButton(onExpand = { model.onIntent(ChatSurfaceIntent.Expand) })
            ComposerPlusButton(model, onAttachImage)
            ComposerTextField(model = model, style = DockedFieldStyle, modifier = Modifier.weight(1f))
            ComposerVoiceButton(model)
            ComposerActionButton(model)
        }
    }
}
