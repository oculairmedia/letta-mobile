package com.letta.mobile.ui.screens.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.components.LatencyText
import com.letta.mobile.ui.components.MessageBubbleShape
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.chatColors
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatTypography
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A message renders bubble-less (just markdown on the page background) when
 * it's plain assistant prose — no tool calls, no generated UI, no approval
 * card, no attachments. Anything structured keeps its card chrome so the
 * boundaries stay legible.
 */
internal fun UiMessage.shouldRenderBubbleLess(): Boolean {
    // letta-mobile-5s1n: error frames must render with the error-container
    // bubble chrome, never bubble-less.
    if (isError) return false
    if (
        !toolCalls.isNullOrEmpty() &&
        generatedUi == null &&
        approvalRequest == null &&
        approvalResponse == null &&
        attachments.isEmpty()
    ) {
        return true
    }
    if (role != "assistant") return false
    if (!toolCalls.isNullOrEmpty()) return false
    if (generatedUi != null) return false
    if (approvalRequest != null) return false
    if (approvalResponse != null) return false
    if (attachments.isNotEmpty()) return false
    return true
}

/**
 * A modifier that detects long-press gestures without consuming short taps.
 *
 * Unlike [Modifier.combinedClickable] or [detectTapGestures], this handler uses
 * [awaitFirstDown] with `requireUnconsumed = false` and never consumes the down
 * event for short taps. This allows child composables (e.g., mermaid diagram's
 * tap-to-fullscreen) to receive their own tap events, while the parent still
 * gets long-press-to-copy behavior.
 */
internal fun Modifier.longPressPassthrough(
    onLongPress: (() -> Unit)?,
): Modifier {
    if (onLongPress == null) return this
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val upBeforeTimeout = withTimeoutOrNull(
                viewConfiguration.longPressTimeoutMillis,
            ) {
                // Spin until the pointer lifts (short tap) — do not consume.
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.any { !it.pressed }) break
                }
            }
            if (upBeforeTimeout == null) {
                // Timeout expired before lift → long press.
                onLongPress()
            }
            // Short tap: nothing consumed → child composables handle normally.
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubbleSurface(
    message: UiMessage,
    groupPosition: GroupPosition,
    isStreaming: Boolean,
    onGeneratedUiMessage: ((String) -> Unit)? = null,
    onApprovalDecision: ((String, List<String>, Boolean, String?) -> Unit)? = null,
    approvalInFlight: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val isUser = message.role == "user"
    val isLastAssistant = isStreaming && message.role == "assistant"
    val style = bubbleStyle(role = message.role, isStreaming = isLastAssistant, isError = message.isError)
    val colors = MaterialTheme.chatColors
    val dimens = MaterialTheme.chatDimens
    val typo = MaterialTheme.chatTypography
    val renderer = remember(message.role, message.toolCalls, message.generatedUi) { resolveRenderer(message) }
    val bubbleLess = message.shouldRenderBubbleLess()

    // letta-mobile-d2z6.s1 (Emmanuel 2026-04-26 01:28 EDT): ease bubble
    // height growth as streaming chunks land. Short 60ms LinearEasing
    // tween — fast enough that successive chunks (typically 80–150ms
    // apart) don't stack into compounding wobble, but long enough that
    // the user's eye perceives "growing" rather than "popping".
    //
    // Pinch suppresses the animation entirely (avoids height-interp
    // cascades across many bubbles during the gesture, see
    // letta-mobile-5e0f).
    //
    // Non-streaming, non-pinching bubbles get NO animateContentSize on
    // the Surface itself — historically that fought with the per-bubble
    // collapse/reasoning animations downstream. The Surface stays
    // size-stable; only mid-stream growth is animated.
    val isPinchingForBubble = LocalChatIsPinching.current
    val bubbleSizeAnimation = if (isLastAssistant && !isPinchingForBubble) {
        Modifier.animateContentSize(
            animationSpec = ChatMotion.streamingSizeSpec,
        )
    } else {
        Modifier
    }

    val contentColumn: @Composable () -> Unit = {
        Column(
            modifier = (if (bubbleLess) {
                // No surface chrome → no horizontal padding; the message
                // list's own contentPadding is the only side gutter.
                Modifier.padding(vertical = dimens.bubblePaddingVertical)
            } else {
                Modifier.padding(
                    horizontal = dimens.bubblePaddingHorizontal,
                    vertical = dimens.bubblePaddingVertical,
                )
            }).then(bubbleSizeAnimation),
            verticalArrangement = Arrangement.spacedBy(dimens.messageSpacing),
        ) {
            // Suppress the role label header for bubble-less assistant prose
            // — the avatar above the message already identifies the speaker
            // and the label adds visual noise. Bubbled messages (tools,
            // approvals, generated UI) keep the label so the kind of content
            // is obvious.
            if ((groupPosition == GroupPosition.First || groupPosition == GroupPosition.None) && !bubbleLess) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = message.displayRoleLabel(style.roleLabel),
                        style = typo.roleLabel,
                        color = style.roleColor,
                    )
                    if (message.isPending) {
                        Icon(
                            imageVector = LettaIcons.AccessTime,
                            contentDescription = stringResource(R.string.screen_chat_pending_content_description),
                            modifier = Modifier
                                .size(LettaIconSizing.Inline)
                                .alpha(0.7f),
                            tint = style.roleColor,
                        )
                    }
                }
            } else if (bubbleLess && message.isPending) {
                // Still surface the pending indicator for bubble-less
                // messages — render as a standalone small icon row above the
                // prose so it doesn't disappear silently.
                Icon(
                    imageVector = LettaIcons.AccessTime,
                    contentDescription = stringResource(R.string.screen_chat_pending_content_description),
                    modifier = Modifier
                        .size(LettaIconSizing.Inline)
                        .alpha(0.6f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val approvalRequest = message.approvalRequest
            if (approvalRequest != null) {
                if (message.toolCalls.isNullOrEmpty()) {
                    ApprovalRequestCard(
                        approval = approvalRequest,
                        isSubmitting = approvalInFlight,
                        onDecision = onApprovalDecision,
                    )
                    return@Column
                }
            }

            if (message.approvalResponse != null) {
                ApprovalResponseCard(message = message)
                return@Column
            }

            if (message.attachments.isNotEmpty()) {
                // UiMessage still exposes `attachments` as raw List to avoid
                // rippling an ImmutableList migration through MessageMapper
                // and every sync code-path; wrap at the call-site so the
                // composable sees a stable param type (o7ob.2.6).
                val stableAttachments = remember(message.attachments) {
                    message.attachments.toImmutableList()
                }
                MessageAttachmentsGrid(attachments = stableAttachments)
            }

            val textColor = if (bubbleLess) {
                MaterialTheme.colorScheme.onSurface
            } else if (isUser) {
                colors.userText
            } else {
                colors.agentText
            }
            if (message.content.isNotBlank() || message.attachments.isEmpty()) {
                // letta-mobile-6p4o.1: forward isStreaming to renderers so
                // assistant prose can be clamped to word boundaries while
                // mid-stream and decorated with a streaming cursor.
                renderer.Render(
                    message = message,
                    textColor = textColor,
                    modifier = Modifier,
                    onGeneratedUiMessage = onGeneratedUiMessage,
                    isStreaming = isLastAssistant,
                )
            }
            if (!message.toolCalls.isNullOrEmpty()) {
                ApprovalRequestControls(
                    approval = approvalRequest,
                    isSubmitting = approvalInFlight,
                    onDecision = onApprovalDecision,
                )
            }
            if (!isLastAssistant && message.role == "assistant" && !message.isReasoning) {
                message.latencyMs?.let { latencyMs ->
                    LatencyText(
                        latencyMs = latencyMs.toFloat(),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }

    if (bubbleLess) {
        // Plain assistant prose: no Surface, no rounded shape — markdown
        // floats directly on the page background and gets the full available
        // content width. Keep the long-press affordance for copy.
        Box(
            modifier = if (onLongClick != null) {
                Modifier.longPressPassthrough(onLongClick)
            } else Modifier,
        ) {
            contentColumn()
        }
    } else {
        val bubbleShape = MessageBubbleShape(radius = 12.dp, isFromUser = isUser, groupPosition = groupPosition)
        Surface(
            shape = bubbleShape,
            color = style.containerColor,
            tonalElevation = 0.dp,
            modifier = if (onLongClick != null) {
                Modifier
                    .clip(bubbleShape)
                    .longPressPassthrough(onLongClick)
            } else Modifier,
        ) {
            contentColumn()
        }
    }
}
