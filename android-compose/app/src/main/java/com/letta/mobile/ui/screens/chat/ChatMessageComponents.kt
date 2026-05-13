package com.letta.mobile.ui.screens.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.data.model.ToolReturnStatus
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolApprovalDecision
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.tooloutput.ToolOutputParser
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.components.MessageBubbleShape
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.LatencyText
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.components.TextInputDialog
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.chatBubbleSender
import com.letta.mobile.ui.theme.chatColors
import com.letta.mobile.ui.theme.chatDimens
import com.letta.mobile.ui.theme.chatTypography
import com.letta.mobile.ui.theme.dialogSectionHeading
import com.letta.mobile.ui.theme.listItemMetadata
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.theme.scaledBy
import com.letta.mobile.ui.theme.sectionTitle
import java.util.LinkedHashMap
import kotlinx.collections.immutable.toImmutableList

private const val REASONING_PREVIEW_MAX_LENGTH = 96
private const val TOOL_CALL_ENTRANCE_ANIMATION_HISTORY_SIZE = 512

private val toolCallEntranceAnimationHistory =
    RecentStringSet(TOOL_CALL_ENTRANCE_ANIMATION_HISTORY_SIZE)

private fun UiMessage.displayRoleLabel(defaultLabel: String): String {
    val toolCall = toolCalls?.singleOrNull()
    if (toolCall == null) {
        return if (role == "tool") {
            if (content.isNotBlank()) {
                "Tool output"
            } else {
                "Tool activity"
            }
        } else {
            defaultLabel
        }
    }
    return toolCall.name
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatMessageItem(
    message: UiMessage,
    groupPosition: GroupPosition,
    isStreaming: Boolean,
    reasoningCollapsed: Boolean = true,
    onToggleReasoning: (() -> Unit)? = null,
    onGeneratedUiMessage: ((String) -> Unit)? = null,
    onRerunMessage: ((UiMessage) -> Unit)? = null,
    rerunEnabled: Boolean = true,
    onApprovalDecision: ((String, List<String>, Boolean, String?) -> Unit)? = null,
    approvalInFlight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == "user"
    val showAvatar = false
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.action_copy)
    val copyText = remember(message) { buildMessageCopyText(message) }
    var showMessageActions by remember { mutableStateOf(false) }
    val hasUserActions = isUser && onRerunMessage != null
    val onLongClick: (() -> Unit)? = when {
        hasUserActions -> { { showMessageActions = true } }
        copyText.isNotBlank() -> { { copyToClipboard(context, copyLabel, copyText) } }
        else -> null
    }

    ActionSheet(
        show = showMessageActions,
        onDismiss = { showMessageActions = false },
        title = "Message actions",
    ) {
        if (hasUserActions && rerunEnabled) {
            ActionSheetItem(
                text = "Run again",
                icon = LettaIcons.Refresh,
                onClick = {
                    showMessageActions = false
                    onRerunMessage(message)
                },
            )
        }
        if (copyText.isNotBlank()) {
            ActionSheetItem(
                text = copyLabel,
                icon = LettaIcons.Copy,
                onClick = {
                    showMessageActions = false
                    copyToClipboard(context, copyLabel, copyText)
                },
            )
        }
    }

    // New layout: avatar floats ABOVE the bubble rather than occupying a
    // 40dp-wide gutter next to it. Assistant/tool/reasoning bubbles can then
    // stretch to the full content-area width — a noticeable win on phones
    // where the old gutter consumed ~10% of horizontal space per message.
    // User bubbles stay right-aligned and sized-to-content; their avatar
    // aligns to the right over the bubble.
    val avatarAlignment = if (isUser) Alignment.End else Alignment.Start

    if (message.isReasoning) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            if (showAvatar) {
                MessageAvatar(role = "assistant")
                Spacer(modifier = Modifier.height(4.dp))
            }
            MessageReasoning(
                message = message,
                isStreaming = isStreaming,
                collapsed = reasoningCollapsed,
                onToggleCollapsed = onToggleReasoning,
            )
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = avatarAlignment,
    ) {
        if (showAvatar) {
            MessageAvatar(role = message.role)
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Assistant / tool bubbles take the full content-area width so code
        // blocks, markdown tables, and long messages can breathe. User
        // bubbles are capped by bubbleMaxWidthFraction and sized-to-content,
        // keeping them visually distinct as right-aligned cards.
        val bubbleModifier = if (isUser) {
            Modifier.fillMaxWidth(MaterialTheme.chatDimens.bubbleMaxWidthFraction)
        } else {
            Modifier.fillMaxWidth()
        }
        Column(
            horizontalAlignment = avatarAlignment,
            modifier = bubbleModifier,
        ) {
            MessageBubbleSurface(
                message = message,
                groupPosition = groupPosition,
                isStreaming = isStreaming,
                onGeneratedUiMessage = onGeneratedUiMessage,
                onApprovalDecision = onApprovalDecision,
                approvalInFlight = approvalInFlight,
                onLongClick = onLongClick,
            )
        }
    }
}

/**
 * A message renders bubble-less (just markdown on the page background) when
 * it's plain assistant prose — no tool calls, no generated UI, no approval
 * card, no attachments. Anything structured keeps its card chrome so the
 * boundaries stay legible.
 */
private fun UiMessage.shouldRenderBubbleLess(): Boolean {
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
private fun Modifier.longPressPassthrough(
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
private fun MessageBubbleSurface(
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

@Composable
private fun ApprovalRequestCard(
    approval: UiApprovalRequest,
    isSubmitting: Boolean,
    onDecision: ((String, List<String>, Boolean, String?) -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.screen_chat_approval_request_title),
            style = MaterialTheme.chatTypography.toolLabel,
        )
        Text(
            text = stringResource(R.string.screen_chat_approval_request_body),
            style = MaterialTheme.chatTypography.toolDetail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        approval.toolCalls.forEach { toolCall ->
            ToolCallCard(
                toolCall = UiToolCall(
                    name = toolCall.name,
                    arguments = toolCall.arguments,
                    result = null,
                    toolCallId = toolCall.toolCallId,
                ),
                approvalStateOverride = ToolApprovalState.RequestingInput,
                keepExpanded = true,
            )
        }
        ApprovalActionRow(
            approval = approval,
            isSubmitting = isSubmitting,
            onDecision = onDecision,
        )
    }
}

@Composable
private fun ApprovalRequestControls(
    approval: UiApprovalRequest?,
    isSubmitting: Boolean,
    onDecision: ((String, List<String>, Boolean, String?) -> Unit)?,
) {
    var rememberedApproval by remember { mutableStateOf(approval) }
    LaunchedEffect(approval) {
        if (approval != null) rememberedApproval = approval
    }

    AnimatedVisibility(
        visible = approval != null && rememberedApproval != null,
        enter = ChatMotion.expandEnter(),
        exit = ChatMotion.expandExit(),
    ) {
        rememberedApproval?.let { visibleApproval ->
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.screen_chat_approval_request_body),
                    style = MaterialTheme.chatTypography.toolDetail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ApprovalActionRow(
                    approval = visibleApproval,
                    isSubmitting = isSubmitting,
                    onDecision = onDecision,
                )
            }
        }
    }
}

@Composable
private fun ApprovalActionRow(
    approval: UiApprovalRequest,
    isSubmitting: Boolean,
    onDecision: ((String, List<String>, Boolean, String?) -> Unit)?,
) {
    var showRejectDialog by remember { mutableStateOf(false) }
    val toolCallIds = remember(approval) { approval.toolCalls.map { it.toolCallId } }

    TextInputDialog(
        show = showRejectDialog,
        title = stringResource(R.string.screen_chat_approval_reject_title),
        label = stringResource(R.string.screen_chat_approval_reason_label),
        confirmText = stringResource(R.string.screen_chat_approval_reject_action),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { reason ->
            showRejectDialog = false
            onDecision?.invoke(approval.requestId, toolCallIds, false, reason)
        },
        onDismiss = { showRejectDialog = false },
        placeholder = stringResource(R.string.screen_chat_approval_reason_placeholder),
        singleLine = false,
        minLines = 3,
        validate = { true },
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { showRejectDialog = true },
            enabled = !isSubmitting && onDecision != null,
        ) {
            Text(stringResource(R.string.screen_chat_approval_reject_action))
        }
        androidx.compose.material3.Button(
            onClick = {
                onDecision?.invoke(approval.requestId, toolCallIds, true, null)
            },
            enabled = !isSubmitting && onDecision != null,
        ) {
            Text(
                if (isSubmitting) stringResource(R.string.screen_chat_approval_submitting)
                else stringResource(R.string.screen_chat_approval_approve_action)
            )
        }
    }
}

@Composable
private fun ApprovalResponseCard(message: UiMessage) {
    val approval = message.approvalResponse ?: return

    // Defensive: only render when an explicit decision was made. The mapper
    // already drops auto-approval echoes (Letta server emits approve=null in
    // bypassPermissions sessions), but if any other code path constructs a
    // UiApprovalResponse with all-null decisions we still must not paint it
    // as "Rejected" — that's how the long-standing mis-labeling bug surfaced.
    val explicitDecisions = listOfNotNull(approval.approved) +
        approval.approvals.mapNotNull { it.approved }
    if (explicitDecisions.isEmpty()) return

    val approved = explicitDecisions.any { it }
    val title = if (approved) {
        stringResource(R.string.screen_chat_approval_approved_title)
    } else {
        stringResource(R.string.screen_chat_approval_rejected_title)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = MaterialTheme.chatTypography.toolLabel)
        approval.reason?.takeIf { it.isNotBlank() }?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.chatTypography.toolDetail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun MessageAvatar(
    role: String,
    modifier: Modifier = Modifier,
) {
    val isUser = role == "user"
    val icon = when (role) {
        "tool" -> LettaIcons.Tool
        "assistant" -> LettaIcons.Agent
        else -> null
    }

    // Smaller, lighter-weight avatar — 24dp instead of 32dp, with a thin
    // outline for the assistant (matches the leading AI-app convention of a
    // ringed sparkle/icon floating above the response) and a filled pill for
    // the user / tool roles (preserves the current visual weight for those).
    val avatarSize = 24.dp

    if (isUser || icon == null) {
        // Filled pill for user (and any unknown roles): preserves the current
        // "Y" badge / role-letter look.
        val containerColor = MaterialTheme.chatColors.userBubble
        val contentColor = MaterialTheme.chatColors.userText
        Surface(
            modifier = modifier.size(avatarSize),
            shape = MaterialTheme.shapes.small,
            color = containerColor,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(LettaIconSizing.Inline),
                    )
                } else {
                    Text(
                        text = "Y",
                        style = MaterialTheme.typography.chatBubbleSender,
                        color = contentColor,
                    )
                }
            }
        }
    } else {
        // Ringed icon for assistant / tool — no fill, subtle outline.
        val ringColor = MaterialTheme.colorScheme.outlineVariant
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        Box(
            modifier = modifier
                .size(avatarSize)
                .clip(MaterialTheme.shapes.small)
                .background(Color.Transparent)
                .border(
                    width = 1.dp,
                    color = ringColor,
                    shape = MaterialTheme.shapes.small,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
internal fun MessageReasoning(
    message: UiMessage,
    isStreaming: Boolean,
    collapsed: Boolean,
    onToggleCollapsed: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val previewText = remember(message.content) { message.content.reasoningPreview() }
    val isCollapsed = collapsed && !isStreaming
    val clickLabel = if (isCollapsed) "Expand reasoning" else "Collapse reasoning"

    // letta-mobile-d2z6: gate animateContentSize on !isStreaming. While
    // assistant tokens are arriving the reasoning bubble grows on every
    // frame; the default 150ms FastOutSlowIn animation produces visible
    // wobble that compounds with the RunBlock layout. The animation is
    // still useful for the user-initiated collapse/expand toggle, so we
    // keep it gated rather than removing it outright.
    //
    // letta-mobile-5e0f.r2: also suppress during pinch-to-zoom so we
    // don't get height-interpolation cascades across many bubbles per
    // pinch frame.
    //
    // letta-mobile-d2z6.s1 (Emmanuel 2026-04-26 01:28 EDT — "add easing
    // to the chunks coming on so it's smoother"): instead of fully
    // suppressing the animation while streaming, swap to a SHORT linear
    // tween (60ms) that's faster than typical token inter-arrival
    // (~80–150ms). Each chunk's height delta interpolates briefly
    // instead of snapping in, but the spec is short enough that
    // successive chunks don't stack into compounding wobble — the
    // animation always finishes (or near-finishes) before the next
    // chunk arrives. The collapse/expand toggle keeps the default
    // (longer, eased) spec by virtue of the !isStreaming branch.
    val isPinching = LocalChatIsPinching.current
    val sizeAnimation = when {
        isPinching -> Modifier
        isStreaming -> Modifier.animateContentSize(
            animationSpec = ChatMotion.streamingSizeSpec,
        )
        else -> Modifier.animateContentSize(animationSpec = ChatMotion.contentSizeSpec)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(sizeAnimation)
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = onToggleCollapsed != null,
                    onClickLabel = clickLabel,
                ) { onToggleCollapsed?.invoke() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AnimatedVisibility(
                visible = isStreaming,
                enter = ChatMotion.horizontalEnter(),
                exit = ChatMotion.horizontalExit(),
            ) {
                @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                LoadingIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = if (isStreaming) "Thinking…" else "Reasoning",
                style = MaterialTheme.typography.sectionTitle,
                color = if (isStreaming) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = if (isCollapsed) previewText else "Shown",
                style = MaterialTheme.typography.listItemSupporting,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = LettaIcons.ExpandMore,
                contentDescription = clickLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (onToggleCollapsed != null) 0.8f else 0.4f),
                modifier = Modifier
                    .size(LettaIconSizing.Inline)
                    .rotate(if (isCollapsed) 0f else 180f),
            )
        }

        AnimatedVisibility(
            visible = !isCollapsed,
            enter = ChatMotion.verticalEnter(slideDivisor = 4),
            exit = ChatMotion.verticalExit(slideDivisor = 4),
        ) {
            val lineColor = if (isStreaming) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
            Column(
                modifier = Modifier
                    .padding(top = 16.dp, start = 8.dp, bottom = 4.dp)
                    .drawBehind {
                        drawLine(
                            color = lineColor,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 3.dp.toPx(),
                        )
                    }
                    .padding(start = 14.dp),
            ) {
                // letta-mobile-d2z6 (root cause): MarkdownText re-parses on
                // every content change and re-emits a fresh subtree, which
                // causes the bubble to visibly flicker on each streaming
                // chunk. Use plain Text during streaming and snap to
                // formatted markdown when the stream ends.
                if (isStreaming) {
                    val smoothedContent = rememberSmoothedStreamingText(
                        rawText = message.content,
                        isStreaming = true,
                    )
                    Text(
                        text = smoothedContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    MarkdownText(
                        text = message.content,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun String.reasoningPreview(): String {
    val normalized = lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
        .replace(Regex("\\s+"), " ")

    if (normalized.isEmpty()) return "No reasoning recorded"
    return if (normalized.length <= REASONING_PREVIEW_MAX_LENGTH) {
        normalized
    } else {
        normalized.take(REASONING_PREVIEW_MAX_LENGTH).trimEnd() + "…"
    }
}

@Composable
internal fun MessageToolCalls(
    toolCalls: kotlinx.collections.immutable.ImmutableList<UiToolCall>,
    modifier: Modifier = Modifier,
    messageId: String? = null,
    animateEntrance: Boolean = false,
    approvalRequest: UiApprovalRequest? = null,
) {
    val pendingApprovalToolCallIds = remember(approvalRequest) {
        approvalRequest?.toolCalls
            ?.mapTo(mutableSetOf()) { it.toolCallId }
            .orEmpty()
    }
    if (shouldUseCompactToolCallGroup(toolCalls)) {
        val entranceKey = remember(messageId, toolCalls.firstOrNull()?.toolCallMotionKey()) {
            "tool-group|${messageId.orEmpty()}|${toolCalls.firstOrNull()?.toolCallMotionKey().orEmpty()}"
        }
        val shouldAnimateEntrance = remember(animateEntrance, entranceKey) {
            shouldRunToolCallEntranceAnimation(animateEntrance, entranceKey)
        }
        if (shouldAnimateEntrance) {
            ToolCallEntrance {
                CompactToolCallGroupCard(
                    toolCalls = toolCalls,
                    pendingApprovalToolCallIds = pendingApprovalToolCallIds,
                    modifier = modifier,
                    animateRows = animateEntrance,
                    rowAnimationKeyPrefix = "message|${messageId.orEmpty()}",
                )
            }
        } else {
            CompactToolCallGroupCard(
                toolCalls = toolCalls,
                pendingApprovalToolCallIds = pendingApprovalToolCallIds,
                modifier = modifier,
                animateRows = animateEntrance,
                rowAnimationKeyPrefix = "message|${messageId.orEmpty()}",
            )
        }
        return
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        toolCalls.forEachIndexed { index, toolCall ->
            val motionKey = toolCall.toolCallMotionKey()
            key(index, motionKey) {
                val entranceKey = remember(messageId, motionKey) {
                    "tool|${messageId.orEmpty()}|$motionKey"
                }
                val shouldAnimateEntrance = remember(animateEntrance, entranceKey) {
                    shouldRunToolCallEntranceAnimation(animateEntrance, entranceKey)
                }
                if (shouldAnimateEntrance) {
                    ToolCallEntrance {
                        ToolCallCard(
                            toolCall = toolCall,
                            approvalStateOverride = toolCall.approvalState(pendingApprovalToolCallIds),
                        )
                    }
                } else {
                    ToolCallCard(
                        toolCall = toolCall,
                        approvalStateOverride = toolCall.approvalState(pendingApprovalToolCallIds),
                    )
                }
            }
        }
    }
}

internal fun shouldUseCompactToolCallGroup(toolCalls: List<UiToolCall>): Boolean =
    toolCalls.size > 1

internal fun shouldRunToolCallEntranceAnimation(
    animateEntrance: Boolean,
    key: String,
): Boolean =
    animateEntrance && toolCallEntranceAnimationHistory.addIfAbsent(key)

internal fun recordToolCallEntranceAnimationRun(key: String) {
    toolCallEntranceAnimationHistory.addIfAbsent(key)
}

internal fun clearToolCallEntranceAnimationHistoryForTest() {
    toolCallEntranceAnimationHistory.clear()
}

private class RecentStringSet(
    private val maxEntries: Int,
) {
    private val lock = Any()
    private val values = object : LinkedHashMap<String, Unit>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > maxEntries
    }

    fun contains(value: String): Boolean = synchronized(lock) {
        values.containsKey(value)
    }

    fun addIfAbsent(value: String): Boolean = synchronized(lock) {
        if (values.containsKey(value)) {
            false
        } else {
            values[value] = Unit
            true
        }
    }

    fun clear() {
        synchronized(lock) {
            values.clear()
        }
    }
}

private fun UiToolCall.toolCallMotionKey(): String = buildString {
    append(toolCallId ?: name)
    append('|')
    append(arguments.hashCode())
}

@Composable
private fun ToolCallEntrance(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = ChatMotion.verticalEnter(slideDivisor = 10),
        exit = ChatMotion.expandExit(),
    ) {
        content()
    }
}

@Composable
internal fun CompactToolCallGroupCard(
    toolCalls: List<UiToolCall>,
    pendingApprovalToolCallIds: Set<String>,
    modifier: Modifier = Modifier,
    approvalRequests: List<UiApprovalRequest> = emptyList(),
    activeApprovalRequestId: String? = null,
    onApprovalDecision: ((String, List<String>, Boolean, String?) -> Unit)? = null,
    animateRows: Boolean = false,
    rowAnimationKeyPrefix: String = "",
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.82f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${toolCalls.size} tool calls",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                val completedCount = toolCalls.count { it.result != null }
                if (completedCount > 0) {
                    ToolMetaChip(text = "$completedCount/${toolCalls.size} done")
                }
            }
            toolCalls.forEachIndexed { index, toolCall ->
                val motionKey = toolCall.toolCallMotionKey()
                key(index, motionKey) {
                    val entranceKey = remember(rowAnimationKeyPrefix, motionKey) {
                        "compact-tool-row|$rowAnimationKeyPrefix|$motionKey"
                    }
                    val shouldAnimateEntrance = remember(animateRows, entranceKey) {
                        shouldRunToolCallEntranceAnimation(animateRows, entranceKey)
                    }
                    if (shouldAnimateEntrance) {
                        ToolCallEntrance {
                            CompactToolCallRow(
                                toolCall = toolCall,
                                approvalState = toolCall.approvalState(pendingApprovalToolCallIds),
                            )
                        }
                    } else {
                        CompactToolCallRow(
                            toolCall = toolCall,
                            approvalState = toolCall.approvalState(pendingApprovalToolCallIds),
                        )
                    }
                }
            }
            approvalRequests.forEach { approval ->
                ApprovalRequestControls(
                    approval = approval,
                    isSubmitting = activeApprovalRequestId == approval.requestId,
                    onDecision = onApprovalDecision,
                )
            }
        }
    }
}

@Composable
private fun CompactToolCallRow(
    toolCall: UiToolCall,
    approvalState: ToolApprovalState?,
) {
    val fontScale = LocalChatFontScale.current
    var expanded by remember(toolCall.toolCallMotionKey()) { mutableStateOf(false) }
    val display = remember(toolCall.name, toolCall.arguments) {
        ToolDisplayRegistry.resolve(toolCall.name, toolCall.arguments)
    }
    val argumentSummary = remember(toolCall.arguments) { summarizeToolArguments(toolCall.arguments) }
    val executionTimeText = remember(toolCall.executionTimeMs) { toolCall.executionTimeMs?.let(::formatToolExecutionTime) }
    val displayResult = remember(toolCall.result) { toolCall.result?.displayToolResult() }
    val resultPreview = remember(displayResult) { displayResult?.takeIf { it.isNotBlank() } }
    val summary = remember(toolCall.name, toolCall.arguments, displayResult, toolCall.status, display.detailLine) {
        compactToolCallSummary(toolCall, display.detailLine, displayResult)
    }
    val compactTitle = remember(toolCall.name, summary) {
        "${toolCall.name} - $summary"
    }
    val isError = ToolReturnStatus.isError(toolCall.status)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = ChatMotion.chipCrossfadeSpec,
        label = "CompactToolCallChevronRotation",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { }
                .clickable(
                    onClickLabel = if (expanded) "Collapse tool details" else "Expand tool details",
                ) { expanded = !expanded }
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(display.emoji, style = MaterialTheme.chatTypography.codeBlock)
            Text(
                text = compactTitle,
                style = MaterialTheme.typography.chatBubbleSender
                    .copy(fontFamily = MaterialTheme.chatTypography.codeBlock.fontFamily)
                    .scaledBy(fontScale),
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (approvalState != null) {
                AnimatedToolApprovalChip(state = approvalState)
            }
            executionTimeText?.let { time ->
                ToolMetaChip(text = time)
            }
            when {
                isError -> Icon(
                    imageVector = LettaIcons.Error,
                    contentDescription = "Error",
                    modifier = Modifier.size(LettaIconSizing.Inline),
                    tint = MaterialTheme.colorScheme.error,
                )
                toolCall.result != null -> Icon(
                    imageVector = LettaIcons.CheckCircle,
                    contentDescription = "Success",
                    modifier = Modifier.size(LettaIconSizing.Inline),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                imageVector = LettaIcons.ExpandMore,
                contentDescription = if (expanded) "Collapse tool details" else "Expand tool details",
                modifier = Modifier
                    .size(14.dp)
                    .rotate(chevronRotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp),
            ) {
                ToolCallExpandedSummary(
                    toolCall = toolCall,
                    argumentSummary = argumentSummary,
                    resultPreview = resultPreview,
                    isError = isError,
                    fontScale = fontScale,
                )
                ToolCallExpandedBody(
                    toolCall = toolCall,
                    display = display,
                    executionTimeText = executionTimeText,
                    displayResult = displayResult,
                    isError = isError,
                    fontScale = fontScale,
                )
            }
        }
    }
}

private fun compactToolCallSummary(
    toolCall: UiToolCall,
    displayDetailLine: String?,
    displayResult: String? = toolCall.result?.displayToolResult(),
): String {
    val result = displayResult?.takeIf { it.isNotBlank() }
    if (result != null) return "${if (ToolReturnStatus.isError(toolCall.status)) "Error" else "Result"}: $result"
    val argumentSummary = summarizeToolArguments(toolCall.arguments)
    if (argumentSummary != null) return "${argumentSummary.label}: ${argumentSummary.value}"
    return displayDetailLine ?: if (toolCall.result == null) "Running" else "Completed"
}

private fun String.displayToolResult(): String = ToolOutputParser.sanitizeResultFieldText(this)

private enum class ToolApprovalState {
    RequestingInput,
    Approved,
    Rejected,
}

private fun UiToolApprovalDecision.toToolApprovalState(): ToolApprovalState = when (this) {
    UiToolApprovalDecision.Approved -> ToolApprovalState.Approved
    UiToolApprovalDecision.Rejected -> ToolApprovalState.Rejected
}

private fun UiToolCall.approvalState(pendingApprovalToolCallIds: Set<String>): ToolApprovalState? {
    val id = toolCallId?.takeIf { it.isNotBlank() }
    if (id != null && id in pendingApprovalToolCallIds) {
        return ToolApprovalState.RequestingInput
    }
    return approvalDecision?.toToolApprovalState()
}

/**
 * Compact, inline approval chip shown in the `ToolCallCard` header. Pending
 * requests and folded decisions share the same slot so the chip can crossfade
 * from "requesting input" to "approved" instead of popping in as new chrome.
 */
@Composable
private fun AnimatedToolApprovalChip(state: ToolApprovalState?) {
    AnimatedVisibility(
        visible = state != null,
        enter = ChatMotion.horizontalEnter(),
        exit = ChatMotion.horizontalExit(),
    ) {
        Crossfade(
            targetState = state,
            animationSpec = ChatMotion.chipCrossfadeSpec,
            label = "Tool approval chip",
        ) { targetState ->
            targetState?.let { ToolApprovalChip(it) }
        }
    }
}

@Composable
private fun ToolApprovalChip(state: ToolApprovalState) {
    val container = when (state) {
        ToolApprovalState.RequestingInput -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        ToolApprovalState.Approved -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        ToolApprovalState.Rejected -> MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
    }
    val text = when (state) {
        ToolApprovalState.RequestingInput -> stringResource(R.string.screen_chat_tool_approval_chip_requesting_input)
        ToolApprovalState.Approved -> stringResource(R.string.screen_chat_tool_approval_chip_approved)
        ToolApprovalState.Rejected -> stringResource(R.string.screen_chat_tool_approval_chip_rejected)
    }
    val tint = when (state) {
        ToolApprovalState.RequestingInput -> MaterialTheme.colorScheme.onSecondaryContainer
        ToolApprovalState.Approved -> MaterialTheme.colorScheme.primary
        ToolApprovalState.Rejected -> MaterialTheme.colorScheme.error
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ToolMetaChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.88f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ToolSummaryLine(
    label: String,
    value: String,
    fontScale: Float,
    isError: Boolean = false,
    maxLines: Int = 1,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.sectionTitle.scaledBy(fontScale),
            color = if (isError) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
            },
        )
        Text(
            text = value,
            style = MaterialTheme.typography.listItemSupporting.scaledBy(fontScale),
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ToolCallExpandedSummary(
    toolCall: UiToolCall,
    argumentSummary: ToolArgumentSummary?,
    resultPreview: String?,
    isError: Boolean,
    fontScale: Float,
) {
    argumentSummary?.let { summary ->
        Spacer(modifier = Modifier.height(6.dp))
        ToolSummaryLine(
            label = summary.label,
            value = summary.value,
            fontScale = fontScale,
            maxLines = 2,
        )
    }
    if (resultPreview != null) {
        Spacer(modifier = Modifier.height(4.dp))
        ToolSummaryLine(
            label = if (isError) "Error" else "Result",
            value = resultPreview,
            fontScale = fontScale,
            isError = isError,
            maxLines = 2,
        )
    } else if (toolCall.result == null) {
        Spacer(modifier = Modifier.height(4.dp))
        ToolSummaryLine(
            label = "Status",
            value = "Running",
            fontScale = fontScale,
            maxLines = 1,
        )
    }
}

@Composable
private fun ToolCallExpandedBody(
    toolCall: UiToolCall,
    display: ToolDisplayInfo,
    executionTimeText: String?,
    displayResult: String?,
    isError: Boolean,
    fontScale: Float,
) {
    val codeStyle = MaterialTheme.chatTypography.codeBlock
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Text(
            text = "Tool: ${toolCall.name}",
            style = MaterialTheme.typography.chatBubbleSender.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
        )
        executionTimeText?.let { time ->
            Text(
                text = "Execution time: $time",
                style = MaterialTheme.typography.listItemSupporting.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
            )
        }
        display.detailLine?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.listItemSupporting.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (toolCall.arguments.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Arguments",
                style = MaterialTheme.typography.sectionTitle.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
            Text(
                text = toolCall.arguments,
                style = MaterialTheme.typography.listItemSupporting.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        displayResult?.takeIf { it.isNotBlank() }?.let { result ->
            var resultExpanded by remember(toolCall.result) { mutableStateOf(false) }
            val resultChevronRotation by animateFloatAsState(
                targetValue = if (resultExpanded) 180f else 0f,
                animationSpec = ChatMotion.chipCrossfadeSpec,
                label = "ToolOutputChevronRotation",
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { }
                    .clickable { resultExpanded = !resultExpanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isError) "Error" else "Output",
                    style = MaterialTheme.typography.sectionTitle.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                    color = if (isError) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    },
                    modifier = Modifier.weight(1f),
                )
                val lineCount = result.count { it == '\n' } + 1
                if (lineCount > 1 || result.length > 80) {
                    Text(
                        text = if (resultExpanded) "collapse" else "${lineCount} line${if (lineCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall.scaledBy(fontScale),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Icon(
                    imageVector = LettaIcons.ExpandMore,
                    contentDescription = if (resultExpanded) "Collapse output" else "Expand output",
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(resultChevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
            ToolOutputRenderer(
                raw = result,
                expanded = resultExpanded,
                isError = isError,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ToolCallCard(
    toolCall: UiToolCall,
    approvalStateOverride: ToolApprovalState? = null,
    keepExpanded: Boolean = false,
) {
    val fontScale = LocalChatFontScale.current
    var expanded by remember { mutableStateOf(false) }
    val display = remember(toolCall.name, toolCall.arguments) {
        ToolDisplayRegistry.resolve(toolCall.name, toolCall.arguments)
    }
    val argumentSummary = remember(toolCall.arguments) { summarizeToolArguments(toolCall.arguments) }
    val executionTimeText = remember(toolCall.executionTimeMs) { toolCall.executionTimeMs?.let(::formatToolExecutionTime) }
    val displayResult = remember(toolCall.result) { toolCall.result?.displayToolResult() }
    val resultPreview = remember(displayResult) { displayResult?.takeIf { it.isNotBlank() } }
    // Explicit-error-whitelist: only paint the Error icon / red color when
    // the server actually said "error". Treating `null`, "completed", or any
    // unrecognized value as error caused the long-running mis-labeling bug
    // tracked in `letta-mobile-o9ce`. See ToolReturnStatus for the full
    // empirical justification.
    val isError = ToolReturnStatus.isError(toolCall.status)
    val codeStyle = MaterialTheme.chatTypography.codeBlock
    val showDetails = keepExpanded || expanded
    val approvalState = approvalStateOverride ?: toolCall.approvalDecision?.toToolApprovalState()
    val compactDetail = remember(
        display.label,
        display.detailLine,
        toolCall.name,
        argumentSummary,
        resultPreview,
        displayResult,
        isError,
    ) {
        when {
            resultPreview != null -> "${if (isError) "Error" else "Result"}: $resultPreview"
            argumentSummary != null -> "${argumentSummary.label}: ${argumentSummary.value}"
            toolCall.result == null -> "Running"
            display.label != toolCall.name -> display.label
            else -> display.detailLine
        }
    }
    val compactTitle = remember(toolCall.name, compactDetail) {
        compactDetail?.let { "${toolCall.name} - $it" } ?: toolCall.name
    }

    // letta-mobile-23h5 (polish 2026-04-19): give the tool card a touch
    // more presence — slightly stronger surface tint and a 1.dp outline
    // so it reads as a distinct artifact in a stack of bubbles, without
    // shouting like a colored pill would.
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.86f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            // Single-line header — tap to expand/collapse
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !keepExpanded) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(display.emoji, style = codeStyle)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = compactTitle,
                    style = MaterialTheme.typography.chatBubbleSender.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // letta-mobile-23h5: folded-in approval decision. Rendered as
                // a compact chip so the user can see "approved" / "rejected"
                // without the old stack of redundant standalone pill bubbles.
                // Pending approval requests use the same slot so the tool card
                // animates from "requesting input" to "approved".
                if (approvalState != null) {
                    AnimatedToolApprovalChip(state = approvalState)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                executionTimeText?.let { time ->
                    ToolMetaChip(text = time)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (isError) {
                    Icon(
                        imageVector = LettaIcons.Error,
                        contentDescription = "Error",
                        modifier = Modifier.size(LettaIconSizing.Inline),
                        tint = MaterialTheme.colorScheme.error,
                    )
                } else if (toolCall.result != null) {
                    Icon(
                        imageVector = LettaIcons.CheckCircle,
                        contentDescription = "Success",
                        modifier = Modifier.size(LettaIconSizing.Inline),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (showDetails) {
                ToolCallExpandedSummary(
                    toolCall = toolCall,
                    argumentSummary = argumentSummary,
                    resultPreview = resultPreview,
                    isError = isError,
                    fontScale = fontScale,
                )
            }

            // Expanded content. Keep manual detail expansion static so LazyColumn
            // does not animate the whole timeline around the user's scroll anchor.
            if (showDetails) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    // Tool name and timing
                    Text(
                        text = "Tool: ${toolCall.name}",
                        style = MaterialTheme.typography.chatBubbleSender.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                    )
                    executionTimeText?.let { time ->
                        Text(
                            text = "Execution time: $time",
                            style = MaterialTheme.typography.listItemSupporting.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
                        )
                    }
                    // Detail line (extracted from arguments)
                    display.detailLine?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.listItemSupporting.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Arguments
                    if (toolCall.arguments.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Arguments",
                            style = MaterialTheme.typography.sectionTitle.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        )
                        Text(
                            text = toolCall.arguments,
                            style = MaterialTheme.typography.listItemSupporting.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Result — inner collapsible (letta-mobile-mge5.19).
                    // Default collapsed: show the result-label row with a
                    // chevron + first-line preview. Tap expands to full.
                    displayResult?.takeIf { it.isNotBlank() }?.let { result ->
                        var resultExpanded by remember(toolCall.result) { mutableStateOf(false) }
                        val resultChevronRotation by animateFloatAsState(
                            targetValue = if (resultExpanded) 180f else 0f,
                            animationSpec = ChatMotion.chipCrossfadeSpec,
                            label = "ToolOutputChevronRotation",
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { resultExpanded = !resultExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (isError) "Error" else "Output",
                                style = MaterialTheme.typography.sectionTitle.copy(fontFamily = codeStyle.fontFamily).scaledBy(fontScale),
                                color = if (isError) {
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                                },
                                modifier = Modifier.weight(1f),
                            )
                            val lineCount = result.count { it == '\n' } + 1
                            if (lineCount > 1 || result.length > 80) {
                                Text(
                                    text = if (resultExpanded) "collapse" else "${lineCount} line${if (lineCount == 1) "" else "s"}",
                                    style = MaterialTheme.typography.labelSmall.scaledBy(fontScale),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Icon(
                                imageVector = LettaIcons.ExpandMore,
                                contentDescription = if (resultExpanded) "Collapse output" else "Expand output",
                                modifier = Modifier
                                    .size(14.dp)
                                    .rotate(resultChevronRotation),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            )
                        }
                        ToolOutputRenderer(
                            raw = result,
                            expanded = resultExpanded,
                            isError = isError,
                            modifier = Modifier.fillMaxWidth(),
                        )
                }
            }
        }
        }
    }
}

private data class ToolArgumentSummary(val label: String, val value: String)

private fun summarizeToolArguments(arguments: String): ToolArgumentSummary? {
    if (arguments.isBlank()) return null
    val fields = listOf(
        "query" to "Query",
        "search" to "Query",
        "command" to "Args",
        "file_path" to "Args",
        "pattern" to "Args",
        "content" to "Args",
        "value" to "Args",
    )
    fields.forEach { (field, label) ->
        extractJsonStringField(arguments, field)?.let { value ->
            return ToolArgumentSummary(label = label, value = value)
        }
    }
    return ToolArgumentSummary(label = "Args", value = arguments.trim())
}

private fun extractJsonStringField(json: String, field: String): String? {
    val key = "\"$field\""
    val keyIdx = json.indexOf(key)
    if (keyIdx < 0) return null
    val colonIdx = json.indexOf(':', keyIdx + key.length)
    if (colonIdx < 0) return null
    val quoteStart = json.indexOf('"', colonIdx + 1)
    if (quoteStart < 0) return null
    val sb = StringBuilder()
    var i = quoteStart + 1
    while (i < json.length) {
        val c = json[i]
        if (c == '\\' && i + 1 < json.length) {
            val next = json[i + 1]
            when (next) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                'n' -> sb.append(' ')
                't' -> sb.append(' ')
                else -> {
                    sb.append('\\')
                    sb.append(next)
                }
            }
            i += 2
        } else if (c == '"') {
            break
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString().ifBlank { null }
}

private fun formatToolExecutionTime(durationMs: Long): String {
    return when {
        durationMs < 1_000L -> "${durationMs}ms"
        durationMs < 60_000L -> {
            val seconds = durationMs / 1_000.0
            "${String.format(java.util.Locale.US, "%.1f", seconds)}s"
        }
        else -> {
            val minutes = durationMs / 60_000L
            val seconds = (durationMs % 60_000L) / 1_000L
            "${minutes}m ${seconds}s"
        }
    }
}

internal fun buildMessageCopyText(message: UiMessage): String {
    return buildString {
        if (message.content.isNotBlank()) {
            append(message.content)
        }
        message.toolCalls.orEmpty().forEach { toolCall ->
            if (isNotEmpty()) append("\n\n")
            append("Tool: ")
            append(toolCall.name)
            toolCall.executionTimeMs?.let { durationMs ->
                append("\nExecution time: ")
                append(formatToolExecutionTime(durationMs))
            }
            if (toolCall.arguments.isNotBlank()) {
                append("\nArguments:\n")
                append(toolCall.arguments)
            }
            toolCall.result?.takeIf { it.isNotBlank() }?.let { result ->
                append("\nResult:\n")
                append(result)
            }
        }
    }
}

private fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(R.string.action_copied), Toast.LENGTH_SHORT).show()
    }
}
