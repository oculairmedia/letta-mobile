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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.graphics.Shape
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
import com.letta.mobile.ui.chat.surface.touchStyle
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Expand/collapse and overflow state for one prompt bubble. */
@Stable
private class PromptCardState {
    var expanded by mutableStateOf(false)
    var overflowed by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
    val canToggle: Boolean get() = overflowed || expanded
}

/**
 * The bubble's look: the user's own colours and "You", or the inter-agent tint and "Inter-agent"
 * when another agent sent it; and the bubble's shape.
 */
private data class PromptBubbleStyle(
    val container: Color,
    val content: Color,
    val label: Color,
    val role: StringResource,
    val shape: Shape,
)

@Composable
private fun promptBubbleStyle(interAgent: Boolean): PromptBubbleStyle {
    val scheme = MaterialTheme.colorScheme
    val shape = ChatBubbleShapes.user()
    return if (interAgent) {
        PromptBubbleStyle(
            container = scheme.tertiaryContainer,
            content = scheme.onTertiaryContainer,
            label = scheme.onTertiaryContainer.copy(alpha = ChatRowAlpha.interAgentLabel),
            role = Res.string.rows_role_inter_agent,
            shape = shape,
        )
    } else {
        PromptBubbleStyle(
            container = scheme.primaryContainer,
            content = scheme.onPrimaryContainer,
            label = scheme.onPrimaryContainer.copy(alpha = ChatRowAlpha.userRoleLabel),
            role = Res.string.rows_role_you,
            shape = shape,
        )
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
    val style = promptBubbleStyle(interAgent)
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
                    PromptBubbleSurface(
                        style = style,
                        modifier = Modifier
                            .widthIn(max = bubbleMaxWidth)
                            // By the otid, as the list keys the row: the server's ack swaps the
                            // optimistic id mid-flight, and the row must keep its claim.
                            .then(rememberSendFlightTarget(message.sendFlightKey(), message.content))
                            .testTag(ChatRowTestTags.USER_PROMPT)
                            .clip(style.shape)
                            .promptBubbleClicks(state, hasActions = availability.hasActions),
                    ) {
                        PromptBody(message, state, style, callbacks)
                        PromptExpandToggle(state)
                    }
                    MessageActionsMenu(message, availability, state, callbacks)
                }
            }
        }
    }
}

/** The send-flight claim's key: the otid when the message has one, else its id. */
private fun UiMessage.sendFlightKey(): String {
    return clientMessageId?.takeIf { it.isNotBlank() } ?: id
}

/** A tap toggles a clamped prompt; a long press opens the message actions when there are any. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.promptBubbleClicks(state: PromptCardState, hasActions: Boolean): Modifier {
    val actionsLabel = stringResource(Res.string.rows_message_actions)
    return combinedClickable(
        onClickLabel = null,
        onLongClickLabel = actionsLabel,
        onLongClick = if (hasActions) ({ state.menuOpen = true }) else null,
        onClick = { if (state.canToggle) state.expanded = !state.expanded },
    )
}

/** The bubble itself: its tint and shape, its body laid out in a padded row. */
@Composable
private fun PromptBubbleSurface(
    style: PromptBubbleStyle,
    modifier: Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = style.shape,
        color = style.container,
        contentColor = style.content,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = ChatRowSpacing.bubblePaddingHorizontal,
                vertical = ChatRowSpacing.bubblePaddingVertical,
            ),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            content = content,
        )
    }
}

@Composable
private fun RowScope.PromptBody(
    message: UiMessage,
    state: PromptCardState,
    style: PromptBubbleStyle,
    callbacks: ChatRowCallbacks,
) {
    Column(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(ChatRowSpacing.messagePart)) {
        val role = stringResource(style.role)
        Text(
            text = if (message.isSendFailed) stringResource(Res.string.rows_role_not_sent, role) else role,
            style = ChatRowType.roleLabel,
            color = if (message.isSendFailed) MaterialTheme.colorScheme.error else style.label,
        )
        if (message.attachments.isNotEmpty()) PromptImages(message, callbacks)
        if (message.content.isNotBlank()) PromptText(message.content, state)
    }
}

@Composable
private fun PromptImages(message: UiMessage, callbacks: ChatRowCallbacks) {
    val images = remember(message.attachments) { message.attachments.toImmutableList() }
    // Touch draws them as the legacy Android bubble did: across the bubble's width, large
    // enough to look at (letta-mobile-bglj6.1.9); desktop keeps its compact strip.
    if (touchStyle()) {
        ChatPromptImageGrid(tap = ImageTap(images, callbacks.onImageTap), modifier = Modifier.fillMaxWidth())
    } else {
        ChatImageThumbnailStrip(tap = ImageTap(images, callbacks.onImageTap))
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
