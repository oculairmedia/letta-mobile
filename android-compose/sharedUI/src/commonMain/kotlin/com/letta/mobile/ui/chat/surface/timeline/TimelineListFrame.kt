package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Arrangement
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
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
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
                contentPadding = PaddingValues(
                    start = LettaDimens.Space.lg,
                    end = LettaDimens.Space.lg,
                    top = LettaDimens.Space.xl,
                    bottom = LettaDimens.Space.xl + overlays.bottomReserve,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
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
                TimelineItemRow(prompt, bindings)
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

/** One render item, at the chat column's width, through the shared row seam. */
@Composable
internal fun TimelineItemRow(item: ChatRenderItem, bindings: TimelineRowBindings, modifier: Modifier = Modifier) {
    Box(modifier = modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth()) {
        ChatRenderItemRow(item = item, context = bindings.contexts.forItem(item), callbacks = bindings.callbacks)
    }
}
