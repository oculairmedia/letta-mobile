package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.components.attached.LettaAttachedStatus
import com.letta.mobile.ui.components.attached.LettaAttachedStatusFrame
import com.letta.mobile.ui.components.field.lettaField
import com.letta.mobile.ui.components.field.lettaFieldBorder
import com.letta.mobile.ui.components.field.rememberLettaField
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

/** The prompt card's and the docked bar's shape; voice mode's outline follows it. */

/** The keyboard is up (always false on desktop, where there is no IME inset). */
@Composable
internal fun keyboardOpen(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0

/**
 * The full composer's prompt card: staged images, the prompt field and the control row, with
 * [statuses] attached to its edges ([LettaAttachedStatusFrame]).
 *
 * In [ChatSurfaceMode.FullScreen] a swipe up on the card raises
 * [ChatSurfaceIntent.OpenCanvas], except while the keyboard is open or a run streams.
 */
@Composable
internal fun ComposerPromptCard(model: ComposerModel, onAttachImage: () -> Unit, statuses: List<LettaAttachedStatus> = emptyList()) {
    // Order matters: widthIn BEFORE fillMaxWidth, or fillMaxWidth pins min == max and the cap is lost.
    LettaAttachedStatusFrame(statuses, MaterialTheme.shapes.large, Modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth()) {
        PromptCardSurface(model, onAttachImage)
    }
}

@Composable
private fun PromptCardSurface(model: ComposerModel, onAttachImage: () -> Unit) {
    val swipeEnabled = model.offersOpenCanvas && !model.streaming && !keyboardOpen()
    val field = rememberLettaField()
    val look = field.state()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .swipeUpToCanvas(enabled = swipeEnabled, onTrigger = { model.onIntent(ChatSurfaceIntent.OpenCanvas) })
            .composerOutline(MaterialTheme.shapes.large, model.uiState)
            .lettaField(field, look)
            .testTag(ComposerTestTags.CARD),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // A text input's border: a hairline at rest (the fill alone is barely a step off the page),
        // firmer under the pointer, the primary colour while anything in the card holds focus.
        border = lettaFieldBorder(look),
    ) {
        val fieldEndPadding = cornerContentPadding()
        Box {
            Column(
                modifier = Modifier.padding(
                    start = LettaDimens.Space.md,
                    end = LettaDimens.Space.md,
                    top = LettaDimens.Space.sm,
                    bottom = LettaDimens.Space.xs,
                ),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            ) {
                if (model.composer.attachments.isNotEmpty()) {
                    Box(Modifier.padding(end = fieldEndPadding)) {
                        ComposerAttachmentStrip(attachments = model.composer.attachments, onRemove = model.actions::removeAttachment)
                    }
                }
                ComposerTextField(model = model, style = FullFieldStyle, modifier = Modifier.padding(end = fieldEndPadding))
                ComposerControlRow(model = model, onAttachImage = onAttachImage)
            }
            ComposerTrailingActions(model, Modifier.align(Alignment.TopEnd))
        }
    }
}

/**
 * The docked bar: the same draft and the same send in one line, with an expand control that
 * raises [ChatSurfaceIntent.Expand] to open the full page. Off the chat page the agent's mascot
 * rides beside it ([ComposerCompanionRow]); on the chat page the docked panel seats it in its
 * top-centre badge instead ([LocalComposerCompanion] is off), so the bar keeps the full width.
 * [statuses] attach to the bar's edges as on the full card.
 */
@Composable
internal fun DockedComposerBar(model: ComposerModel, onAttachImage: () -> Unit, statuses: List<LettaAttachedStatus> = emptyList()) {
    ComposerCompanionRow(model) { DockedComposerBarSurface(model, onAttachImage, statuses) }
}

@Composable
private fun DockedComposerBarSurface(model: ComposerModel, onAttachImage: () -> Unit, statuses: List<LettaAttachedStatus>) {
    val minimised = LocalMinimisedBarLayout.current
    val fieldHeight = minimised?.fieldHeight
    LettaAttachedStatusFrame(statuses, MaterialTheme.shapes.large, Modifier.widthIn(max = composerBarMaxWidth()).fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            DockedBarRow(model, onAttachImage, fieldHeight)
            // Over the bar's own edges, so the strips are where the edge is drawn, however wide it runs.
            minimised?.handles?.let { handles -> Box(Modifier.matchParentSize(), content = handles) }
        }
    }
}

@Composable
private fun DockedBarRow(model: ComposerModel, onAttachImage: () -> Unit, fieldHeight: Dp?) {
    val field = rememberLettaField()
    val look = field.state()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .composerOutline(MaterialTheme.shapes.large, model.uiState)
            .lettaField(field, look)
            .testTag(ComposerTestTags.DOCKED_BAR),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = lettaFieldBorder(look),
    ) {
        Box {
            Row(
                modifier = Modifier
                    .heightIn(min = LettaDimens.Control.actionButton + LettaDimens.Space.sm * 2)
                    .padding(
                        start = LettaDimens.Space.sm,
                        end = trailingActionsWidth(),
                        top = LettaDimens.Space.xs,
                        bottom = LettaDimens.Space.xs,
                    ),
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
                // A dragged-taller bar keeps its other controls on the bottom line.
                verticalAlignment = if (fieldHeight == null) Alignment.CenterVertically else Alignment.Bottom,
            ) {
                ComposerExpandButton(onExpand = { model.onIntent(ChatSurfaceIntent.Expand) })
                ComposerPlusButton(model, onAttachImage)
                ComposerTextField(model = model, style = dockedFieldStyle(DockedFieldStyle, fieldHeight), modifier = Modifier.weight(1f))
                ComposerVoiceButton(model)
            }
            ComposerTrailingActions(model, Modifier.align(Alignment.TopEnd))
        }
    }
}

/**
 * The trailing action slot of the card and of the docked bar, in their top-end corner: the voice-mode
 * button where the page has voice mode, then Send (or Stop while a run streams), one round cluster
 * spaced like the bar's other controls. With no draft Send takes no room and the voice button rests
 * in the corner; Send pushes it left as it arrives. It is the one place a trailing action goes.
 *
 * Another trailing action goes in this row, before [ComposerActionButton], and its width into
 * [trailingActionsWidth], which the prompt field and the docked row reserve for the cluster at its
 * widest (with Send), so the field never reflows as Send comes and goes and nothing slides under it. The Touch bar's counterpart is its TouchTrailingSlot.
 */
@Composable
private fun ComposerTrailingActions(model: ComposerModel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(top = LettaDimens.Space.sm, end = LettaDimens.Space.sm)
            .testTag(ComposerTestTags.TRAILING_ACTIONS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ComposerVoiceModeButton(size = LettaDimens.Control.actionButton)
        // Send brings its own gap, so with no draft the voice button rests flush at the end.
        ComposerActionButton(
            model,
            Modifier.testTag(ComposerTestTags.SEND_SLOT),
            leadingGap = if (hasVoiceMode()) LettaDimens.Space.sm else 0.dp,
        )
    }
}

/**
 * The width [ComposerTrailingActions] takes: Send, the voice-mode button and its gap where the page
 * has voice mode, the slot's inset and the gap before what precedes it.
 */
@Composable
private fun trailingActionsWidth(): Dp {
    val send = LettaDimens.Control.actionButton + LettaDimens.Space.sm * 2
    return if (hasVoiceMode()) send + LettaDimens.Control.actionButton + LettaDimens.Space.sm else send
}

// The column already has md end padding; reserve the rest of the trailing slot.
@Composable
private fun cornerContentPadding(): Dp = trailingActionsWidth() - LettaDimens.Space.md
