package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
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
import com.letta.mobile.sharedui.resources.rows_role_inter_agent
import com.letta.mobile.sharedui.resources.rows_role_not_sent
import com.letta.mobile.sharedui.resources.rows_role_you
import com.letta.mobile.sharedui.resources.rows_send_again
import com.letta.mobile.ui.chat.surface.sendflight.rememberSendFlightTarget
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaPopupMenu
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatBubbleShapes
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.ChatRowType
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/** Expand/collapse and overflow state for one prompt bubble. */
@Stable
private class PromptCardState {
    var expanded by mutableStateOf(false)
    var overflowed by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
    val canToggle: Boolean get() = overflowed || expanded
}

/** The bubble's colours: the user's own, or the inter-agent tint when another agent sent it. */
private data class PromptBubbleColors(val container: Color, val content: Color, val label: Color)

@Composable
private fun promptBubbleColors(interAgent: Boolean): PromptBubbleColors {
    val scheme = MaterialTheme.colorScheme
    return if (interAgent) {
        PromptBubbleColors(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.onTertiaryContainer.copy(alpha = ChatRowAlpha.interAgentLabel))
    } else {
        PromptBubbleColors(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.onPrimaryContainer.copy(alpha = ChatRowAlpha.userRoleLabel))
    }
}

/**
 * letta-mobile-bglj6.1: the user's prompt as the Android timeline draws it (feature-chat
 * ChatMessageItem + MessageBubbleSurface): an end-aligned primaryContainer bubble, at most 88%
 * of the column and sized to its text, with a tight top-end corner and a "You" label. Another
 * agent's message reads "Inter-agent" in the tertiary tint, its provenance above the bubble.
 *
 * Kept from the shared prompt card: a long prompt clamps to three lines with an expand chevron,
 * a long press opens the message actions (Copy, and Send again when the owner can rerun), a
 * failed send reads "You · Not sent", and where a pointer can hover a copy action shows beside
 * the bubble.
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
    val interAgent = message.agentMessageProvenance != null
    val colors = promptBubbleColors(interAgent)
    val actionsLabel = stringResource(Res.string.rows_message_actions)
    val shape = ChatBubbleShapes.user()
    Column(
        modifier = Modifier.fillMaxWidth().hoverable(hoverSource),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        if (interAgent) ProvenanceLabel(message, callbacks)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val bubbleMaxWidth = maxWidth * ChatRowSpacing.bubbleMaxWidthFraction
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                Box {
                    Surface(
                        modifier = Modifier
                            .widthIn(max = bubbleMaxWidth)
                            .then(rememberSendFlightTarget(message.id, message.content))
                            .testTag(ChatRowTestTags.USER_PROMPT)
                            .clip(shape)
                            .combinedClickable(
                                onClickLabel = null,
                                onLongClickLabel = actionsLabel,
                                onLongClick = if (availability.hasActions) ({ state.menuOpen = true }) else null,
                                onClick = { if (state.canToggle) state.expanded = !state.expanded },
                            ),
                        shape = shape,
                        color = colors.container,
                        contentColor = colors.content,
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = ChatRowSpacing.bubblePaddingHorizontal,
                                vertical = ChatRowSpacing.bubblePaddingVertical,
                            ),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
                        ) {
                            PromptBody(message, state, colors, interAgent, callbacks, Modifier.weight(1f, fill = false))
                            PromptExpandToggle(state)
                        }
                    }
                    MessageActionsMenu(message, availability, state, callbacks)
                }
            }
        }
    }
}

@Composable
private fun PromptBody(
    message: UiMessage,
    state: PromptCardState,
    colors: PromptBubbleColors,
    interAgent: Boolean,
    callbacks: ChatRowCallbacks,
    modifier: Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ChatRowSpacing.messagePart)) {
        val role = stringResource(if (interAgent) Res.string.rows_role_inter_agent else Res.string.rows_role_you)
        Text(
            text = if (message.isSendFailed) stringResource(Res.string.rows_role_not_sent, role) else role,
            style = ChatRowType.roleLabel,
            color = if (message.isSendFailed) MaterialTheme.colorScheme.error else colors.label,
        )
        if (message.attachments.isNotEmpty()) {
            val images = remember(message.attachments) { message.attachments.toImmutableList() }
            ChatImageThumbnailStrip(tap = ImageTap(images, callbacks.onImageTap))
        }
        if (message.content.isNotBlank()) PromptText(message.content, state)
    }
}

@Composable
private fun PromptText(content: String, state: PromptCardState) {
    // Expanded height is capped with internal scrolling so the bubble never outgrows the
    // viewport. No SelectionContainer: it would swallow the bubble's toggle clicks, and the
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
private fun PromptExpandToggle(state: PromptCardState) {
    if (!state.canToggle) return
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
