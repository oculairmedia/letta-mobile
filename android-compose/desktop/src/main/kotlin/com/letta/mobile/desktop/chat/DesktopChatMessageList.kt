package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.Image
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.onClick
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.sp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.StepDotIcon
import com.letta.mobile.data.chat.runtime.ChatScreenStatus
import com.letta.mobile.data.chat.runtime.ChatViewportFollowPolicy
import com.letta.mobile.data.chat.runtime.ChatViewportSnapshot
import com.letta.mobile.data.chat.runtime.isConnectionRetryable
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalResponse
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.composer.AutocompleteTrigger
import com.letta.mobile.data.composer.ComposerAutocomplete
import com.letta.mobile.data.composer.ComposerEffort
import com.letta.mobile.data.diff.DiffLineKind
import com.letta.mobile.data.diff.UnifiedDiff
import com.letta.mobile.data.composer.MentionCatalog
import com.letta.mobile.data.composer.MentionKind
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.onboarding.AgentOnboarding
import com.letta.mobile.data.onboarding.OnboardingTask
import com.letta.mobile.data.onboarding.OnboardingTaskKind
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.render.rememberSmoothedStreamingText
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopControlText
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopTextArea
import com.letta.mobile.desktop.DesktopTooltip
import com.letta.mobile.ui.theme.customColors
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.jewel.ui.component.PopupMenu as JewelPopupMenu
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState

internal data class MessageListParams(
    val conversationId: String?,
    val renderItems: List<ChatRenderItem>,
    val isSending: Boolean,
    val isStreamingReply: Boolean = false,
)

@Composable
internal fun MessageList(
    params: MessageListParams,
    modifier: Modifier = Modifier,
) {
    val renderItems = params.renderItems
    val isSending = params.isSending
    val listState = rememberLazyListState()
    val isUserScrolling by listState.interactionSource.collectIsDraggedAsState()
    val scope = rememberCoroutineScope()
    var followLatest by remember(params.conversationId) { mutableStateOf(true) }
    val latestItemKey = renderItems.lastOrNull()?.key

    // The single assistant message currently being revealed: the newest
    // assistant narration of the tail (bottom-most) render item, but only while
    // the reply is actively streaming ([isStreamingReply], which spans the whole
    // stream — unlike [isSending]/"thinking", which clears at the first token).
    // Everything else is settled history.
    val streamingMessageId = if (params.isStreamingReply) {
        renderItems.lastOrNull()?.streamingCandidateMessageId()
    } else {
        null
    }

    // The LazyColumn is laid out as [ "__today__" header, ...renderItems,
    // ("__thinking__" while sending)? ]. The leading header offsets every render
    // row by one, so the last row is at index renderItems.size (not size - 1),
    // and the thinking row at size + 1. The scroll targets below must use this
    // header-aware index — latestIndex(renderItems.size) landed one row short,
    // which is why a fresh prompt/reply needed a manual nudge to the bottom.
    val chatBottomIndex = (renderItems.size + if (isSending) 1 else 0).coerceAtLeast(0)
    val tailContentLength = remember(renderItems) { renderItems.tailContentLength() }

    MessageListFollowEffects(
        MessageListFollowParams(
            conversationId = params.conversationId,
            renderItems = renderItems,
            isSending = isSending,
            latestItemKey = latestItemKey,
            tailContentLength = tailContentLength,
            chatBottomIndex = chatBottomIndex,
            listState = listState,
            isUserScrolling = { isUserScrolling },
            followLatest = { followLatest },
            onFollowLatestChange = { followLatest = it },
        ),
    )

    val fadeAlphas = rememberChatListFadeAlphas(listState)

    Box(modifier = modifier.fillMaxWidth()) {
        // The fade wraps ONLY the list (not the scroll-to-latest button, which is
        // a sibling below) so the button is never dimmed by the bottom fade.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .chatFadingEdges(
                    topFadeAlpha = fadeAlphas.top,
                    bottomFadeAlpha = fadeAlphas.bottom,
                    fadeLength = 44.dp,
                ),
        ) {
            MessageListColumn(
                MessageListColumnParams(
                    listState = listState,
                    renderItems = renderItems,
                    streamingMessageId = streamingMessageId,
                    isSending = isSending,
                ),
            )
        }

        if (ChatViewportFollowPolicy.shouldShowScrollToLatest(listState.toChatViewportSnapshot(isUserScrolling))) {
            ScrollToLatestButton(
                onClick = {
                    followLatest = true
                    scope.launch { listState.animateScrollToItem(chatBottomIndex) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp),
            )
        }
    }
}

private data class ChatListFadeAlphas(val top: Float, val bottom: Float)

@Composable
private fun rememberChatListFadeAlphas(listState: LazyListState): ChatListFadeAlphas {
    // Soft gradient fades at the top/bottom of the list so content dissolves
    // into the background instead of hard-clipping where it meets the title bar
    // and the composer (mirrors the mobile chat fading edges).
    val showTopFade by remember(listState) { derivedStateOf { listState.canScrollBackward } }
    val showBottomFade by remember(listState) { derivedStateOf { listState.canScrollForward } }
    val topFadeAlpha by animateFloatAsState(
        targetValue = if (showTopFade) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "topFadeAlpha",
    )
    val bottomFadeAlpha by animateFloatAsState(
        targetValue = if (showBottomFade) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "bottomFadeAlpha",
    )
    return ChatListFadeAlphas(top = topFadeAlpha, bottom = bottomFadeAlpha)
}

private data class MessageListFollowParams(
    val conversationId: String?,
    val renderItems: List<ChatRenderItem>,
    val isSending: Boolean,
    val latestItemKey: Any?,
    val tailContentLength: Int,
    val chatBottomIndex: Int,
    val listState: LazyListState,
    val isUserScrolling: () -> Boolean,
    val followLatest: () -> Boolean,
    val onFollowLatestChange: (Boolean) -> Unit,
)

@Composable
private fun MessageListFollowEffects(params: MessageListFollowParams) {
    val listState = params.listState
    val chatBottomIndex = params.chatBottomIndex
    val onFollowLatestChange = params.onFollowLatestChange

    LaunchedEffect(params.conversationId) {
        onFollowLatestChange(true)
        if (params.renderItems.isNotEmpty()) {
            listState.scrollToItem(chatBottomIndex)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.toChatViewportSnapshot(params.isUserScrolling()) }
            .distinctUntilChanged()
            .collect { snapshot ->
                onFollowLatestChange(
                    ChatViewportFollowPolicy.nextFollowModeAfterScroll(
                        currentFollowMode = params.followLatest(),
                        snapshot = snapshot,
                    ),
                )
            }
    }

    LaunchedEffect(
        params.latestItemKey,
        params.renderItems.size,
        params.tailContentLength,
        params.isSending,
    ) {
        if (ChatViewportFollowPolicy.shouldAutoFollow(params.followLatest(), params.renderItems.size)) {
            listState.scrollToItem(chatBottomIndex)
        }
    }

    // When a send starts, always jump to the bottom (the thinking row, then the
    // streaming reply) regardless of where the user was reading — followLatest is
    // forced so the auto-follow effect above keeps tracking the reply as it lands.
    LaunchedEffect(params.isSending) {
        if (params.isSending) {
            onFollowLatestChange(true)
            listState.animateScrollToItem(chatBottomIndex)
        }
    }
}

private data class MessageListColumnParams(
    val listState: LazyListState,
    val renderItems: List<ChatRenderItem>,
    val streamingMessageId: String?,
    val isSending: Boolean,
)

@Composable
private fun MessageListColumn(params: MessageListColumnParams) {
    val listState = params.listState
    val renderItems = params.renderItems
    val streamingMessageId = params.streamingMessageId
    val isSending = params.isSending
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        // Vertical breathing room as CONTENT padding, not a viewport inset, so
        // the scroll area itself runs to the top/bottom edges. Content then
        // clips exactly where the fade reaches full transparency — no faint
        // line from content ending mid-gradient.
        contentPadding = PaddingValues(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "__today__") {
            Text(
                text = "Today",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        items(
            items = renderItems,
            key = { it.key },
        ) { item ->
            MessageListItem(item = item, streamingMessageId = streamingMessageId)
        }
        if (isSending) {
            item(key = "__thinking__") {
                Box(modifier = Modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth()) {
                    ThinkingMessageRow()
                }
            }
        }
    }
}

@Composable
private fun MessageListItem(
    item: ChatRenderItem,
    streamingMessageId: String?,
) {
    Column(modifier = Modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth()) {
        // Message text is selectable (SelectionContainer), which is the
        // copy path. The floating hover copy toolbar was removed — it
        // popped over the content on every hover and read as noise while
        // reading/scrolling.
        when (item) {
            is ChatRenderItem.Single -> DesktopMessageBubble(item.message, streamingMessageId)
            is ChatRenderItem.RunBlock -> DesktopRunBlock(item, streamingMessageId)
            is ChatRenderItem.SkillEnvelopeChip -> DesktopSkillEnvelopeChip(item)
        }
        // Per-message clock timestamp (Penpot "Grouping + timestamps"),
        // aligned to the sender side.
        messageClockLabel(item.boundaryTimestamp)?.let { clock ->
            val isUser = (item as? ChatRenderItem.Single)?.message?.role == "user"
            Text(
                text = clock,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = if (isUser) TextAlign.End else TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun ScrollToLatestButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = "Scroll to latest message",
            )
        }
    }
}

private fun List<ChatRenderItem>.tailContentLength(): Int =
    lastOrNull()?.let { item ->
        when (item) {
            is ChatRenderItem.Single -> item.message.content.length
            is ChatRenderItem.RunBlock -> item.messages.sumOf { it.first.content.length }
            is ChatRenderItem.SkillEnvelopeChip -> item.rawContent.length
        }
    } ?: 0

/**
 * Softly dissolves the top/bottom [fadeLength] of the wrapped content to
 * transparent, so the message list grades into the background instead of
 * hard-clipping at the title bar / composer — the desktop port of the mobile
 * chat fading edges. A [BlendMode.DstIn] vertical-gradient mask over an
 * offscreen layer (DstIn keeps the already-drawn content only where the mask is
 * opaque, so a transparent→opaque ramp makes each edge fade out). The mask
 * colour is irrelevant; only its alpha drives the fade. No-ops (and skips the
 * offscreen layer) when both alphas are 0, i.e. the list isn't scrollable.
 */
internal fun Modifier.chatFadingEdges(
    topFadeAlpha: Float,
    bottomFadeAlpha: Float,
    fadeLength: Dp,
): Modifier {
    if (topFadeAlpha <= 0f && bottomFadeAlpha <= 0f) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadePx = fadeLength.toPx()
            if (fadePx <= 0f) return@drawWithContent
            if (topFadeAlpha > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 1f - topFadeAlpha), Color.Black),
                        startY = 0f,
                        endY = fadePx.coerceAtMost(size.height),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (bottomFadeAlpha > 0f) {
                val len = fadePx.coerceAtMost(size.height / 2f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black, Color.Black.copy(alpha = 1f - bottomFadeAlpha)),
                        startY = size.height - len,
                        endY = size.height,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}

internal fun LazyListState.toChatViewportSnapshot(isUserScrolling: Boolean): ChatViewportSnapshot =
    ChatViewportSnapshot(
        totalItems = layoutInfo.totalItemsCount,
        lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index,
        isUserScrolling = isUserScrolling,
    )

/**
 * A run (reasoning + tool steps + narration) rendered to match the Penpot
 * "Conversation (detailed)" board: an optional "Thought" row, a compact
 * "Run · N steps" card (one row per tool call), then the agent's narration.
 */
