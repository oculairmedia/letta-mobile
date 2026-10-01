package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.chat.projection.MessageActionAvailability
import com.letta.mobile.data.chat.projection.messageActionAvailability
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_collapse_prompt
import com.letta.mobile.sharedui.resources.rows_copy
import com.letta.mobile.sharedui.resources.rows_copy_message
import com.letta.mobile.sharedui.resources.rows_expand_prompt
import com.letta.mobile.sharedui.resources.rows_message_actions
import com.letta.mobile.sharedui.resources.rows_not_sent
import com.letta.mobile.sharedui.resources.rows_send_again
import com.letta.mobile.ui.chat.surface.sendflight.rememberSendFlightTarget
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaPopupMenu
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/** Expand/collapse and overflow state for one prompt card. */
@Stable
private class PromptCardState {
    var expanded by mutableStateOf(false)
    var overflowed by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
    val canToggle: Boolean get() = overflowed || expanded
}

/**
 * letta-mobile-bglj6.1: the user prompt, lifted from desktop's UserPrompt: a full-width,
 * opaque card (the timeline pins it as a sticky header), clamped to three lines with an
 * expand control when the text overflows, compact image thumbnails, and a hover-revealed
 * copy action.
 *
 * Android behaviour added: a long press opens the message actions (Copy, and Send again when
 * the owner can rerun: shared MessageActionPolicy), and a failed send reads "Not sent".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserPromptRow(
    message: UiMessage,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
) {
    val state = remember(message.id) { PromptCardState() }
    val availability = remember(message, context.capabilities.rerun) {
        messageActionAvailability(message, message.content, sendAgainAvailable = context.capabilities.rerun)
    }
    val hoverSource = remember(message.id) { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val edgeColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = ChatRowAlpha.promptEdge)
    val actionsLabel = stringResource(Res.string.rows_message_actions)
    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = LettaDimens.Space.sm)
                .then(rememberSendFlightTarget(message.id, message.content))
                .testTag(ChatRowTestTags.USER_PROMPT)
                .hoverable(hoverSource)
                .clip(RoundedCornerShape(LettaDimens.Radius.md))
                .combinedClickable(
                    onClickLabel = null,
                    onLongClickLabel = actionsLabel,
                    onLongClick = if (availability.hasActions) ({ state.menuOpen = true }) else null,
                    onClick = { if (state.canToggle) state.expanded = !state.expanded },
                )
                .drawBehind {
                    // Sides and bottom only: the pinned card's top edge would double up against
                    // the pane's own boundary.
                    val stroke = LettaDimens.Stroke.hairline.toPx()
                    val radius = LettaDimens.Space.md.toPx()
                    clipRect(top = radius) {
                        drawRoundRect(edgeColor, cornerRadius = CornerRadius(radius, radius), style = Stroke(stroke))
                    }
                },
            shape = RoundedCornerShape(LettaDimens.Radius.md),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(
                    start = LettaDimens.Space.lg,
                    end = LettaDimens.Space.sm,
                    top = LettaDimens.Space.md,
                    bottom = LettaDimens.Space.md,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            ) {
                PromptBody(message, state, callbacks)
                PromptTrailing(message, state, hovered)
            }
        }
        MessageActionsMenu(message, availability, state, callbacks)
    }
}

@Composable
private fun RowScope.PromptBody(message: UiMessage, state: PromptCardState, callbacks: ChatRowCallbacks) {
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        ProvenanceLabel(message, callbacks)
        if (message.content.isNotBlank()) PromptText(message.content, state)
        if (message.isSendFailed) {
            Text(
                text = stringResource(Res.string.rows_not_sent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (message.attachments.isNotEmpty()) {
            val images = remember(message.attachments) { message.attachments.toImmutableList() }
            ChatImageThumbnailStrip(tap = ImageTap(images, callbacks.onImageTap), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PromptText(content: String, state: PromptCardState) {
    // Expanded height is capped with internal scrolling so the card never outgrows the
    // viewport. No SelectionContainer: it would swallow the card's toggle clicks, and the
    // copy action carries the full text.
    Box(
        modifier = if (state.expanded) {
            Modifier.heightIn(max = ChatRowDimens.promptExpandedMaxHeight).verticalScroll(rememberScrollState())
        } else {
            Modifier
        },
    ) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (state.expanded) Int.MAX_VALUE else ChatRowDimens.promptCollapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!state.expanded) state.overflowed = it.hasVisualOverflow },
        )
    }
}

@Composable
private fun PromptTrailing(message: UiMessage, state: PromptCardState, hovered: Boolean) {
    if (state.canToggle) {
        val description = stringResource(if (state.expanded) Res.string.rows_collapse_prompt else Res.string.rows_expand_prompt)
        Box(
            modifier = Modifier
                .sizeIn(minWidth = LettaDimens.Space.xxl, minHeight = LettaDimens.Space.xxl)
                .clip(CircleShape)
                .clickable { state.expanded = !state.expanded },
            contentAlignment = Alignment.Center,
        ) {
            DisclosureChevron(expanded = state.expanded, contentDescription = description)
        }
    }
    if (message.content.isNotBlank()) {
        CopyIconButton(
            CopyAction(
                text = message.content,
                contentDescription = stringResource(Res.string.rows_copy_message),
                emphasized = false,
                visible = hovered,
            ),
        )
    }
}

@Composable
private fun MessageActionsMenu(
    message: UiMessage,
    availability: MessageActionAvailability,
    state: PromptCardState,
    callbacks: ChatRowCallbacks,
) {
    if (!state.menuOpen) return
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val sendAgain = stringResource(Res.string.rows_send_again)
    val copy = stringResource(Res.string.rows_copy)
    val items = buildList {
        if (availability.canSendAgain) {
            add(LettaMenuItem(label = sendAgain, icon = LettaIcons.Send) { callbacks.actions.rerun(message) })
        }
        if (availability.canCopy) {
            add(LettaMenuItem(label = copy, icon = LettaIcons.Copy) { clipboard.setText(AnnotatedString(message.content)) })
        }
    }
    LettaPopupMenu(expanded = true, onDismiss = { state.menuOpen = false }, items = items)
}
