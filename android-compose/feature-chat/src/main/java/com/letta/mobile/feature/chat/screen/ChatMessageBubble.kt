package com.letta.mobile.feature.chat.screen

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.render.resolveRenderer
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.chat.render.bubbleStyle
import com.letta.mobile.ui.chat.render.chatLongPressTimeoutMillis
import com.letta.mobile.ui.components.LatencyText
import com.letta.mobile.ui.components.MessageBubbleShape
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.chatColors
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatTypography
import kotlinx.collections.immutable.toImmutableList

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
    if (subagentNotification != null) return false
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
 * gets long-press message actions.
 *
 * The hold threshold is doubled relative to the platform default (see
 * [chatLongPressTimeoutMillis]) so incidental touches during scrolling don't
 * trigger the long-press.
 */
internal fun Modifier.longPressPassthrough(
    accessibilityLabel: String = "",
    onLongPress: (() -> Unit)?,
): Modifier = LongPressInteraction.applyTo(
    modifier = this,
    accessibilityLabel = accessibilityLabel,
    onLongPress = onLongPress,
)

private class LongPressInteraction(
    private val onLongPress: () -> Unit,
) {
    companion object {
        fun applyTo(
            modifier: Modifier,
            accessibilityLabel: String,
            onLongPress: (() -> Unit)?,
        ): Modifier {
            if (onLongPress == null) return modifier
            return LongPressInteraction(onLongPress).applyTo(
                modifier = modifier,
                accessibilityLabel = accessibilityLabel,
            )
        }
    }

    fun applyTo(
        modifier: Modifier,
        accessibilityLabel: String,
    ): Modifier = modifier
        .semantics(mergeDescendants = false) {
            onLongClick(label = accessibilityLabel) {
                onLongPress()
                true
            }
        }
        .onPreviewKeyEvent { event -> handleKeyEvent(event.nativeKeyEvent) }
        .focusable()
        .pointerInput(Unit) { detectLongPress() }

    private fun handleKeyEvent(event: AndroidKeyEvent): Boolean =
        if (event.opensContextActions()) {
            onLongPress()
            true
        } else {
            false
        }

    private fun AndroidKeyEvent.opensContextActions(): Boolean {
        if (action != AndroidKeyEvent.ACTION_UP) return false
        if (keyCode == AndroidKeyEvent.KEYCODE_MENU) return true
        return keyCode == AndroidKeyEvent.KEYCODE_F10 && isShiftPressed
    }

    private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectLongPress() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            handleGestureCompletion(cancelledBeforeLongPressTimeout(down))
        }
    }

    private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope
        .cancelledBeforeLongPressTimeout(down: PointerInputChange): Boolean =
        withTimeoutOrNull(
            chatLongPressTimeoutMillis(viewConfiguration.longPressTimeoutMillis),
        ) {
            awaitReleaseOrCancellation(
                pointerId = down.id,
                initialPosition = down.position,
            )
        } != null

    private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope
        .awaitReleaseOrCancellation(
            pointerId: PointerId,
            initialPosition: Offset,
        ) {
        while (true) {
            // Observe the final pass so scrolling ancestors have a chance to
            // consume movement before message actions are recognized.
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == pointerId } ?: return
            val positionChanged = change.position != change.previousPosition
            val movedPastTouchSlop =
                (change.position - initialPosition).getDistance() > viewConfiguration.touchSlop
            if (!change.pressed) return
            if (change.isConsumed && positionChanged) return
            if (movedPastTouchSlop) return
        }
    }

    private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope
        .handleGestureCompletion(releasedBeforeTimeout: Boolean) {
        if (releasedBeforeTimeout) return
        onLongPress()
        consumeUntilReleased()
    }

    private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.consumeUntilReleased() {
        // Consume only after recognizing the long-click so release cannot
        // also activate an attachment or Mermaid child.
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
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
    longClickLabel: String = "",
    // letta-mobile-1k3ge restore: tap an attached image to open the fullscreen
    // viewer. (attachments, tappedIndex) -> open viewer. Null = not tappable.
    onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)? = null,
) {
    val isUser = message.role == "user"
    val isLastAssistant = isStreaming && message.role == "assistant"
    val style = bubbleStyle(role = message.role, isStreaming = isLastAssistant, isError = message.isError)
    val colors = MaterialTheme.chatColors
    val dimens = MaterialTheme.chatDimens
    val typo = MaterialTheme.chatTypography
    val renderer = remember(message.role, message.toolCalls, message.generatedUi, message.subagentNotification) { resolveRenderer(message) }
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
    // letta-mobile-4ouwd: gate animateContentSize on 'has been mounted at
    // a stable size for at least one frame.' First-paint composition can
    // produce a measurable size delta when content does multi-pass layout
    // (tables compute column widths in a second pass; some markdown blocks
    // similarly). Without this gate, animateContentSize latches onto that
    // first-paint delta and the user sees the bubble 'grow up' to its
    // final size for table-containing messages.
    //
    // The flag flips on the second non-zero size measurement, so the
    // mid-stream growth animation (the original reason this exists) still
    // fires for subsequent text chunks landing in the streaming bubble.
    val hasFirstPaintSettled = remember(message.id) { mutableStateOf(false) }
    val lastObservedHeight = remember(message.id) { mutableStateOf(0) }
    val bubbleSizeAnimation = if (isLastAssistant && !isPinchingForBubble && hasFirstPaintSettled.value) {
        Modifier.animateContentSize(
            animationSpec = ChatMotion.streamingSizeSpec,
        )
    } else {
        Modifier
    }
    val firstPaintGate = Modifier.onSizeChanged { size ->
        val height = size.height
        if (height <= 0) return@onSizeChanged
        if (lastObservedHeight.value == 0) {
            lastObservedHeight.value = height
        } else if (!hasFirstPaintSettled.value) {
            // Saw at least two non-zero measurements → first paint settled.
            hasFirstPaintSettled.value = true
            lastObservedHeight.value = height
        }
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
            }).then(bubbleSizeAnimation).then(firstPaintGate),
            verticalArrangement = Arrangement.spacedBy(dimens.messageSpacing),
        ) {
            // Suppress the role label header for bubble-less assistant prose
            // — the avatar above the message already identifies the speaker
            // and the label adds visual noise. Bubbled messages (tools,
            // approvals, generated UI) keep the label so the kind of content
            // is obvious.
            if ((groupPosition == GroupPosition.First || groupPosition == GroupPosition.None) && !bubbleLess) {
                Text(
                    text = message.displayRoleLabel(style.roleLabel),
                    style = typo.roleLabel,
                    color = style.roleColor,
                )
            }
            // letta-mobile-vcky.b: dropped the AccessTime clock icon next to
            // pending-message role labels. The bottom-of-list ThinkingShader
            // is now the single in-flight indicator — per-bubble pending
            // chrome doubled up with it and added visual noise (especially
            // on bubble-less assistant prose where the icon floated above
            // the body with no anchor).

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
                MessageAttachmentsGrid(
                    attachments = stableAttachments,
                    onImageClick = onAttachmentImageTap?.let { cb ->
                        { index -> cb(stableAttachments, index) }
                    },
                )
            }

            val textColor = when {
                message.isError -> MaterialTheme.colorScheme.onErrorContainer
                bubbleLess -> MaterialTheme.colorScheme.onSurface
                isUser -> colors.userText
                else -> colors.agentText
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
                    onAttachmentImageTap = onAttachmentImageTap,
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
                        modifier = Modifier.padding(top = LettaSpacing.XXXS),
                    )
                }
            }
        }
    }

    if (bubbleLess) {
        // Plain assistant prose: no Surface, no rounded shape — markdown
        // floats directly on the page background and gets the full available
        // content width. Keep the long-press message-action affordance.
        Box(
            modifier = if (onLongClick != null) {
                Modifier.longPressPassthrough(longClickLabel, onLongClick)
            } else Modifier,
        ) {
            contentColumn()
        }
    } else {
        val bubbleShape = MessageBubbleShape(radius = LettaSpacing.BUBBLE_RADIUS, isFromUser = isUser, groupPosition = groupPosition)
        Surface(
            shape = bubbleShape,
            color = style.containerColor,
            tonalElevation = 0.dp,
            modifier = if (onLongClick != null) {
                Modifier
                    .clip(bubbleShape)
                    .longPressPassthrough(longClickLabel, onLongClick)
            } else Modifier,
        ) {
            contentColumn()
        }
    }
}
