package com.letta.mobile.ui.chat.surface.timeline


import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.surface.touchStyle
import com.letta.mobile.ui.chat.surface.sendflight.LocalSendFlight
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRenderItemRow
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowCallbacks
import com.letta.mobile.ui.mascot.MascotGazeSurface
import com.letta.mobile.ui.mascot.mascotGazeTarget
import com.letta.mobile.ui.theme.ChatTimelineDimens
import com.letta.mobile.ui.theme.LettaDimens

/** What every list draws its rows with. */
@Immutable
internal class TimelineRowBindings(
    val contexts: TimelineRowContexts,
    val callbacks: ChatRowCallbacks,
)

/** The list frame's overlays and their actions. */
@Immutable
internal class TimelineFrameOverlays(
    val pinnedPrompt: ChatRenderItem?,
    val showScrollToLatest: Boolean,
    val onScrollToLatest: () -> Unit,
    /** The list's scroll-to-latest glide: the button stands down while it runs, its springback lifts the rows. */
    val glide: NewestEdgeGlide,
    /** Reserve at the bottom so the newest row clears the stacked A2UI surfaces. */
    val bottomReserve: Dp,
    /**
     * Reserve at the top for host chrome floating over the list (ChatSurfacePlatform.topChromeInset):
     * the oldest row rests below it, and the list still scrolls under it.
     */
    val topReserve: Dp = 0.dp,
)

/**
 * letta-mobile-bglj6.1: the reversed LazyColumn both timelines draw into, with the edge fades,
 * the pinned prompt and the scroll-to-latest button. The fade wraps ONLY the list, so the button
 * and the pinned card are never dimmed (desktop MessageList). The button is desktop's quiet
 * squircle centred over the reading area on Pointer, and Android's round FAB at the bottom end,
 * just above the composer, on Touch (feature-chat ChatMessageList).
 */
@Composable
internal fun TimelineListFrame(
    listState: LazyListState,
    bindings: TimelineRowBindings,
    overlays: TimelineFrameOverlays,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    val fades = rememberTimelineFadeAlphas(
        canScrollTowardOlder = listState.canScrollForward,
        canScrollTowardNewer = listState.canScrollBackward,
        promptPinned = overlays.pinnedPrompt != null,
    )
    val selectionColors = TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.primary,
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = ChatTimelineDimens.Alpha.selection),
    )
    Box(modifier = modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxSize()
                    // The glide's springback lifts the rows inside the list's bounds and fades.
                    .clipToBounds()
                    // Under floating chrome the dissolve runs from the top edge through it (Android's
                    // chat list fades over its top padding, ChatMessageListBody).
                    .timelineFadingEdges(fades, topLength = ChatTimelineDimens.topFadeLength + overlays.topReserve)
                    .graphicsLayer { translationY = overlays.glide.overshootPx }
                    // The conversation: the agent's mascot glances at it (letta-mobile-bglj6.1).
                    .mascotGazeTarget(MascotGazeSurface.TIMELINE)
                    .testTag(ChatTimelineTags.LIST),
                // The Android timeline's frame (ChatMessageListLazyColumn): a 12dp side gutter, a
                // card gap at each end, and no gap between items: each row brings its own leading
                // space (timelineLeadingSpace), tight within a turn, a section break between speakers.
                contentPadding = PaddingValues(
                    start = ChatRowSpacing.contentPaddingHorizontal,
                    end = ChatRowSpacing.contentPaddingHorizontal,
                    top = ChatRowSpacing.listEdge + overlays.topReserve,
                    bottom = ChatRowSpacing.listEdge + overlays.bottomReserve,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
        overlays.pinnedPrompt?.let { prompt ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = overlays.topReserve)
                    .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md)
                    .testTag(ChatTimelineTags.PINNED_PROMPT),
                contentAlignment = Alignment.TopCenter,
            ) {
                // A copy, never a send flight's landing spot: only the prompt's own row can be.
                CompositionLocalProvider(LocalSendFlight provides null) {
                    TimelineItemRow(prompt, bindings, leadingSpace = false)
                }
            }
        }
        val showScrollToLatest = overlays.showScrollToLatest && !overlays.glide.isGliding
        if (touchStyle()) {
            TouchScrollToLatestButton(
                visible = showScrollToLatest,
                onClick = overlays.onScrollToLatest,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = ChatTimelineDimens.scrollToLatestTouchInset,
                        bottom = ChatTimelineDimens.scrollToLatestTouchInset + overlays.bottomReserve,
                    ),
            )
        } else if (showScrollToLatest) {
            ScrollToLatestButton(
                onClick = overlays.onScrollToLatest,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = LettaDimens.Space.lg + overlays.bottomReserve),
            )
        }
    }
}

/**
 * One render item, at the chat column's width, through the shared row seam. In the list it
 * carries its own leading space ([timelineLeadingSpace]); the pinned copy over the list does not.
 */
@Composable
internal fun TimelineItemRow(
    item: ChatRenderItem,
    bindings: TimelineRowBindings,
    modifier: Modifier = Modifier,
    leadingSpace: Boolean = true,
) {
    Box(
        modifier = modifier
            .widthIn(max = ChatColumnMaxWidth)
            .fillMaxWidth()
            .padding(top = if (leadingSpace) timelineLeadingSpace(item) else 0.dp),
    ) {
        ChatRenderItemRow(item = item, context = bindings.contexts.forItem(item), callbacks = bindings.callbacks)
    }
}

/**
 * The space above an item (feature-chat RenderChatMessage / ChatMessageListRenderRunItem): a
 * run of tool calls, a reasoning or tool row, or the continuation of one speaker's group takes
 * the tight beat; anything that starts a new speaker or a new run takes the section break.
 */
internal fun timelineLeadingSpace(item: ChatRenderItem): Dp = when (item) {
    is ChatRenderItem.RunBlock ->
        if (item.messages.all { !it.first.toolCalls.isNullOrEmpty() }) ChatRowSpacing.grouped else ChatRowSpacing.ungrouped
    is ChatRenderItem.Single -> when {
        item.stableRunKey != null -> ChatRowSpacing.ungrouped
        item.message.isReasoning || !item.message.toolCalls.isNullOrEmpty() -> ChatRowSpacing.grouped
        item.groupPosition == GroupPosition.Middle || item.groupPosition == GroupPosition.Last -> ChatRowSpacing.grouped
        else -> ChatRowSpacing.ungrouped
    }
}
