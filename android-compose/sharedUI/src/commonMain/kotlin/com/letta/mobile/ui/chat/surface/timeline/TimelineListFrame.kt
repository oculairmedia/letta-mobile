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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
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
    /** Reserve at the bottom so the newest row clears the stacked A2UI surfaces. */
    val bottomReserve: Dp,
)

/**
 * letta-mobile-bglj6.1: the reversed LazyColumn both timelines draw into, with the edge fades,
 * the pinned prompt and the scroll-to-latest button. The fade wraps ONLY the list, so the button
 * and the pinned card are never dimmed (desktop MessageList).
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
                    .timelineFadingEdges(fades)
                    // The conversation: the agent's mascot glances at it (letta-mobile-bglj6.1).
                    .mascotGazeTarget(MascotGazeSurface.TIMELINE)
                    .testTag(ChatTimelineTags.LIST),
                // The Android timeline's frame (ChatMessageListLazyColumn): a 12dp side gutter, a
                // card gap at each end, and no gap between items: each row brings its own leading
                // space (timelineLeadingSpace), tight within a turn, a section break between speakers.
                contentPadding = PaddingValues(
                    start = ChatRowSpacing.contentPaddingHorizontal,
                    end = ChatRowSpacing.contentPaddingHorizontal,
                    top = ChatRowSpacing.listEdge,
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
        if (overlays.showScrollToLatest) {
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
