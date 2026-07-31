package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.runtime.ChatViewportFollowPolicy
import com.letta.mobile.data.chat.runtime.ChatViewportSnapshot
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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
        renderItems.lastOrNull()?.streamingCandidateMessageId()?.let(::StreamingMessageId)
    } else {
        null
    }

    // Consecutive tool-only messages fold into one collapsed "N tool calls"
    // row — six near-identical Bash cards with six timestamps drowned the
    // agent's prose. Folded HERE (not in the column) because the scroll
    // arithmetic below must count the rows the LazyColumn actually holds.
    val rows = remember(renderItems) { groupDesktopChatRows(renderItems) }

    // The LazyColumn is laid out as [ "__today__" header, ...rows,
    // ("__thinking__" while sending)? ]. The leading header offsets every render
    // row by one, so the last row is at index rows.size (not size - 1),
    // and the thinking row at size + 1. The scroll targets below must use this
    // header-aware index — latestIndex(rows.size) landed one row short,
    // which is why a fresh prompt/reply needed a manual nudge to the bottom.
    val chatBottomIndex = (rows.size + if (isSending) 1 else 0).coerceAtLeast(0)
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
        // No top fade: the user prompt pins to the top as a sticky header, and a
        // fade there dissolved the pinned card itself instead of loose text.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .chatFadingEdges(
                    topFadeAlpha = 0f,
                    bottomFadeAlpha = fadeAlphas.bottom,
                    fadeLength = 44.dp,
                ),
        ) {
            MessageListColumn(
                MessageListColumnParams(
                    listState = listState,
                    rows = rows,
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

private data class ChatListFadeAlphas(val bottom: Float)

@Composable
private fun rememberChatListFadeAlphas(listState: LazyListState): ChatListFadeAlphas {
    // Soft gradient fade at the bottom of the list so content dissolves into
    // the composer instead of hard-clipping (mirrors the mobile chat fading
    // edges). Top has no fade — the pinned prompt card owns that edge.
    val showBottomFade by remember(listState) { derivedStateOf { listState.canScrollForward } }
    val bottomFadeAlpha by animateFloatAsState(
        targetValue = if (showBottomFade) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "bottomFadeAlpha",
    )
    return ChatListFadeAlphas(bottom = bottomFadeAlpha)
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
    val rows: List<DesktopChatRow>,
    val streamingMessageId: StreamingMessageId?,
    val isSending: Boolean,
)

private fun ChatRenderItem.isUserPrompt(): Boolean =
    this is ChatRenderItem.Single && MessageRoleToken(message.role).isUser()

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageListColumn(params: MessageListColumnParams) {
    val listState = params.listState
    val rows = params.rows
    val streamingMessageId = params.streamingMessageId
    val isSending = params.isSending
    val selectionColors = TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.primary,
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
    )
    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
            // User prompts are sticky headers: the question stays pinned to the
            // top of the viewport while its (usually much taller) answer scrolls
            // underneath, so you never lose track of what was asked. Everything
            // else is an ordinary row. Rows arrive pre-folded (see MessageList):
            // consecutive tool-only messages render as one collapsed "N tool
            // calls" card instead of a stack of near-identical Bash cards.
            rows.forEach { row ->
                if (row is DesktopChatRow.ToolGroup) {
                    item(key = row.key) {
                        Column(modifier = Modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth()) {
                            DesktopToolGroupCard(row)
                            // One clock for the whole group, matching the
                            // per-item label style below.
                            messageClockLabel(row.boundaryTimestamp)?.let { clock ->
                                Text(
                                    text = clock,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                                    textAlign = TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 2.dp),
                                )
                            }
                        }
                    }
                    return@forEach
                }
                val item = (row as DesktopChatRow.Item).item
                if (item.isUserPrompt()) {
                    stickyHeader(key = item.key) {
                        // No backdrop fill: an opaque strip here would paint over
                        // the ambient glow as a black band. The prompt card is
                        // itself opaque, so only the narrow gutters beside it let
                        // scrolled content show through.
                        // A pinned sticky header sticks at the viewport's actual
                        // top edge, ignoring the LazyColumn's top contentPadding —
                        // so once pinned the card touched the pane's edge with no
                        // breathing room. Top padding here applies whether pinned
                        // or scrolled inline, keeping the gap consistent either way.
                        // End padding clears the floating background-tasks
                        // toggle at the pane's top-right, so the pinned card
                        // floats beside it instead of running underneath.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, end = 44.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            MessageListItem(item = item, streamingMessageId = streamingMessageId)
                        }
                    }
                } else {
                    item(key = item.key) {
                        MessageListItem(item = item, streamingMessageId = streamingMessageId)
                    }
                }
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
}

@Composable
private fun MessageListItem(
    item: ChatRenderItem,
    streamingMessageId: StreamingMessageId?,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
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
    // Compact squircle: a 44dp circle read as a floating action button and
    // dominated the reading area; this is a quiet utility control.
    Surface(
        onClick = onClick,
        modifier = modifier.size(30.dp),
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = "Scroll to latest message",
                modifier = Modifier.size(18.dp),
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
