package com.letta.mobile.feature.chat.screen.messagelist

import com.letta.mobile.data.chat.runtime.ChatViewportFollowPolicy
import com.letta.mobile.feature.chat.screen.ChatAutoScrollSignature
import com.letta.mobile.feature.chat.screen.ChatFadeEdgeLength
import com.letta.mobile.feature.chat.screen.ChatMessageRoles
import com.letta.mobile.feature.chat.screen.StreamingAutoScrollSnapThrottleMs
import com.letta.mobile.feature.chat.screen.newestMessageAutoScrollSignature
import com.letta.mobile.feature.chat.screen.shouldForceScrollOnUserSend
import com.letta.mobile.feature.chat.screen.toChatViewportSnapshot
import com.letta.mobile.feature.chat.coordination.ChatHydrationTrace
import com.letta.mobile.ui.chat.render.ConversationState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class ChatMessageListAutoScrollGate(
    val signature: ChatAutoScrollSignature,
    val previousSignature: ChatAutoScrollSignature?,
    val followLatest: Boolean,
    val renderItemCount: Int,
)

internal data class ChatMessageListPerformAutoScrollParams(
    val listState: LazyListState,
    val isStreaming: Boolean,
    val lastStreamingSnapMs: Long,
)

internal data class ViewportAnchor(
    val index: Int,
    val scrollOffset: Int,
    val followTail: Boolean,
)

internal val conversationViewportAnchors = mutableMapOf<String, ViewportAnchor>()

internal enum class ChatRestorationState {
    AwaitingSnapshot,
    AwaitingFirstLayout,
    Restored,
    UserControlled,
    FollowTail,
}

private class ChatViewportRestorationTracker(hasInitialItems: Boolean) {
    var restorationState by mutableStateOf(initialRestorationState(hasInitialItems))
        private set
    var followLatest by mutableStateOf(true)
        private set
    var lastStreamingSnapMs by mutableStateOf(0L)
    var lastAutoScrollSignature by mutableStateOf<ChatAutoScrollSignature?>(null)

    fun onSnapshotAvailable() {
        if (restorationState == ChatRestorationState.AwaitingSnapshot) {
            restorationState = ChatRestorationState.AwaitingFirstLayout
        }
    }

    fun onInitialLayoutRestored(followTail: Boolean) {
        restorationState = if (followTail) ChatRestorationState.Restored else ChatRestorationState.UserControlled
        followLatest = followTail
    }

    fun onViewportFollowModeChanged(nextFollowMode: Boolean) {
        followLatest = nextFollowMode
        restorationState = nextRestorationStateAfterViewport(restorationState, nextFollowMode)
    }

    fun onUserMessageSent() {
        followLatest = true
        restorationState = ChatRestorationState.FollowTail
    }

    fun onUserInteraction() {
        followLatest = false
        restorationState = restorationStateAfterUserInteraction()
    }

    fun canAutoFollow(): Boolean = restorationState in AUTO_FOLLOW_STATES
}

internal fun initialRestorationState(hasItems: Boolean): ChatRestorationState =
    if (hasItems) ChatRestorationState.AwaitingFirstLayout else ChatRestorationState.AwaitingSnapshot

internal fun restorationStateAfterUserInteraction(): ChatRestorationState = ChatRestorationState.UserControlled

internal fun nextRestorationStateAfterViewport(
    current: ChatRestorationState,
    followLatest: Boolean,
): ChatRestorationState = when {
    current == ChatRestorationState.AwaitingSnapshot -> current
    current == ChatRestorationState.AwaitingFirstLayout -> current
    !followLatest -> ChatRestorationState.UserControlled
    current == ChatRestorationState.UserControlled -> ChatRestorationState.FollowTail
    else -> current
}

private val AUTO_FOLLOW_STATES = setOf(ChatRestorationState.Restored, ChatRestorationState.FollowTail)
private val PENDING_RESTORATION_STATES =
    setOf(ChatRestorationState.AwaitingSnapshot, ChatRestorationState.AwaitingFirstLayout)

@Composable
internal fun ChatMessageListEffects(params: ChatMessageListEffectsParams) {
    ChatMessageListFocusClearEffect(params.listState)
    ChatMessageListViewportRestorationEffect(params)
    ChatMessageListLoadOlderEffect(params)
    ChatMessageListReleaseOlderEffect(params)
    ChatMessageListScrollToMessageEffect(params)
}

@Composable
private fun ChatMessageListFocusClearEffect(listState: LazyListState) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(listState.interactionSource) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                focusManager.clearFocus()
            }
        }
    }
}

@Composable
private fun ChatMessageListViewportRestorationEffect(params: ChatMessageListEffectsParams) {
    val conversationId = (params.state.conversationState as? ConversationState.Ready)?.conversationId
    val tracker = remember(conversationId) { ChatViewportRestorationTracker(params.renderItems.isNotEmpty()) }
    val sendScrollOffset = with(LocalDensity.current) { -ChatFadeEdgeLength.roundToPx() }

    ChatMessageListRestorationCancellationEffect(params.listState, conversationId, tracker)
    ChatMessageListSnapshotAvailabilityEffect(params, conversationId, tracker)
    ChatMessageListInitialLayoutEffect(params, conversationId, tracker)
    ChatMessageListViewportFollowEffect(params, conversationId, tracker)
    ChatMessageListAutoScrollEffect(params, sendScrollOffset, tracker)
}

@Composable
private fun ChatMessageListRestorationCancellationEffect(
    listState: LazyListState,
    conversationId: String?,
    tracker: ChatViewportRestorationTracker,
) {
    LaunchedEffect(listState.interactionSource, conversationId) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                val cancelledPendingRestore = tracker.restorationState in PENDING_RESTORATION_STATES
                tracker.onUserInteraction()
                if (cancelledPendingRestore) {
                    ChatHydrationTrace.current(conversationId)?.let { generation ->
                        ChatHydrationTrace.scrollInitialized(generation, correction = "user_controlled")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageListSnapshotAvailabilityEffect(
    params: ChatMessageListEffectsParams,
    conversationId: String?,
    tracker: ChatViewportRestorationTracker,
) {
    LaunchedEffect(params.renderItems.size, conversationId) {
        if (params.renderItems.isNotEmpty()) tracker.onSnapshotAvailable()
    }
}

@Composable
private fun ChatMessageListInitialLayoutEffect(
    params: ChatMessageListEffectsParams,
    conversationId: String?,
    tracker: ChatViewportRestorationTracker,
) {
    LaunchedEffect(params.listState, params.renderItems.size, conversationId) {
        snapshotFlow { params.listState.layoutInfo.totalItemsCount }
            .distinctUntilChanged()
            .collect { totalCount -> restoreInitialLayoutIfReady(params.listState, totalCount, conversationId, tracker) }
    }
}

private suspend fun restoreInitialLayoutIfReady(
    listState: LazyListState,
    totalCount: Int,
    conversationId: String?,
    tracker: ChatViewportRestorationTracker,
) {
    if (totalCount <= 0 || tracker.restorationState != ChatRestorationState.AwaitingFirstLayout) return
    val anchor = conversationId?.let(conversationViewportAnchors::get) ?: ViewportAnchor(0, 0, true)
    val targetIndex = anchor.index.coerceAtMost(totalCount - 1)
    if (listState.firstVisibleItemIndex != targetIndex || listState.firstVisibleItemScrollOffset != anchor.scrollOffset) {
        listState.scrollToItem(targetIndex, anchor.scrollOffset)
    }
    tracker.onInitialLayoutRestored(anchor.followTail)
    ChatHydrationTrace.current(conversationId)?.let { generation ->
        ChatHydrationTrace.scrollInitialized(generation, correction = "initial_restore")
    }
}

@Composable
private fun ChatMessageListViewportFollowEffect(
    params: ChatMessageListEffectsParams,
    conversationId: String?,
    tracker: ChatViewportRestorationTracker,
) {
    LaunchedEffect(params.listState, params.isUserScrolling, params.renderItems.size, conversationId) {
        snapshotFlow { params.listState.toChatViewportSnapshot(params.isUserScrolling, params.renderItems.size) }
            .distinctUntilChanged()
            .collect { snapshot ->
                tracker.onViewportFollowModeChanged(
                    ChatViewportFollowPolicy.nextFollowModeAfterScroll(
                        currentFollowMode = tracker.followLatest,
                        snapshot = snapshot,
                    ),
                )
                persistViewportAnchorIfRestored(params.listState, conversationId, tracker)
            }
    }
}

private fun persistViewportAnchorIfRestored(
    listState: LazyListState,
    conversationId: String?,
    tracker: ChatViewportRestorationTracker,
) {
    if (conversationId == null || tracker.restorationState in PENDING_RESTORATION_STATES) return
    conversationViewportAnchors[conversationId] = ViewportAnchor(
        index = listState.firstVisibleItemIndex,
        scrollOffset = listState.firstVisibleItemScrollOffset,
        followTail = tracker.followLatest,
    )
}

@Composable
private fun ChatMessageListAutoScrollEffect(
    params: ChatMessageListEffectsParams,
    sendScrollOffset: Int,
    tracker: ChatViewportRestorationTracker,
) {
    val signature = newestMessageAutoScrollSignature(params.state.messages)
    LaunchedEffect(signature, params.state.isStreaming, params.renderItems.size) {
        signature ?: return@LaunchedEffect
        val previousSignature = tracker.lastAutoScrollSignature
        when {
            !tracker.canAutoFollow() -> Unit
            shouldForceScrollOnUserSend(signature, previousSignature?.messageId) -> {
                tracker.onUserMessageSent()
                params.listState.animateScrollToItem(0, sendScrollOffset)
            }
            shouldAutoScrollToLatest(signature, previousSignature, params, tracker) -> {
                tracker.lastStreamingSnapMs = performAutoScrollToLatest(
                    ChatMessageListPerformAutoScrollParams(
                        listState = params.listState,
                        isStreaming = params.state.isStreaming,
                        lastStreamingSnapMs = tracker.lastStreamingSnapMs,
                    ),
                )
            }
        }
        tracker.lastAutoScrollSignature = signature
    }
}

private fun shouldAutoScrollToLatest(
    signature: ChatAutoScrollSignature,
    previousSignature: ChatAutoScrollSignature?,
    params: ChatMessageListEffectsParams,
    tracker: ChatViewportRestorationTracker,
): Boolean = shouldAutoScrollToLatest(
    ChatMessageListAutoScrollGate(
        signature = signature,
        previousSignature = previousSignature,
        followLatest = tracker.followLatest,
        renderItemCount = params.renderItems.size,
    ),
)

private fun shouldAutoScrollToLatest(gate: ChatMessageListAutoScrollGate): Boolean {
    if (!ChatViewportFollowPolicy.shouldAutoFollow(gate.followLatest, gate.renderItemCount)) return false
    return gate.signature.role != ChatMessageRoles.User ||
        gate.signature.messageId != gate.previousSignature?.messageId
}

private suspend fun performAutoScrollToLatest(params: ChatMessageListPerformAutoScrollParams): Long {
    val nowMs = System.currentTimeMillis()
    if (params.isStreaming) {
        if (nowMs - params.lastStreamingSnapMs < StreamingAutoScrollSnapThrottleMs) {
            return params.lastStreamingSnapMs
        }
        params.listState.scrollToItem(0)
        return nowMs
    }
    params.listState.scrollToItem(0)
    return params.lastStreamingSnapMs
}

@Composable
private fun ChatMessageListLoadOlderEffect(params: ChatMessageListEffectsParams) {
    LaunchedEffect(params.listState, params.state.hasMoreOlderMessages, params.state.isLoadingOlderMessages, params.state.messages.size) {
        snapshotFlow { params.listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { _ ->
                if (!shouldLoadOlderMessages(params)) return@collect
                // ⚡ Bolt Optimization: visibleItemsInfo is inherently sorted by index.
                // Using lastOrNull() avoids allocating an iterator on every frame, reducing GC jank.
                val lastVisible = params.listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val totalItems = params.listState.layoutInfo.totalItemsCount
                if (totalItems > 0 && lastVisible >= totalItems - 3) {
                    params.onLoadOlderMessages()
                }
            }
    }
}

private fun shouldLoadOlderMessages(params: ChatMessageListEffectsParams): Boolean {
    if (!params.state.hasMoreOlderMessages) return false
    if (params.state.isLoadingOlderMessages) return false
    if (params.state.messages.isEmpty()) return false
    return true
}

/**
 * Sliding-window release: pairs with [ChatMessageListLoadOlderEffect]. Once
 * the user scrolls back near the live tail (low index in this
 * reverseLayout list — index 0 is the newest message) after having pulled
 * in older pages, shrink the resident window back down so repeated
 * scroll-up/scroll-down cycles don't leave it permanently grown. Only fires
 * once the resident list has actually grown past what a normal viewport
 * needs, so ordinary scrolling near the tail never triggers a release.
 */
@Composable
private fun ChatMessageListReleaseOlderEffect(params: ChatMessageListEffectsParams) {
    LaunchedEffect(params.listState, params.state.messages.size, params.state.isLoadingOlderMessages) {
        snapshotFlow { params.listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstVisibleItemIndex ->
                if (shouldReleaseOlderMessages(params, firstVisibleItemIndex)) {
                    params.onReleaseOlderMessages()
                }
            }
    }
}

private fun shouldReleaseOlderMessages(params: ChatMessageListEffectsParams, firstVisibleItemIndex: Int): Boolean {
    if (params.state.isLoadingOlderMessages) return false
    if (params.state.messages.size <= RELEASE_OLDER_TRIGGER_MESSAGE_COUNT) return false
    return firstVisibleItemIndex <= RELEASE_OLDER_SCROLL_THRESHOLD
}

// Only worth releasing once the resident list has grown well past what a
// normal viewport/window needs to hold — mirrors ChatTimelineProjector's
// DEFAULT_MAX_RESIDENT_UI_MESSAGES (3000); trigger a little below it so a
// release actually has meaningful content to drop.
private const val RELEASE_OLDER_TRIGGER_MESSAGE_COUNT = 2500

// "Back near the live tail" — reverseLayout index 0 is the newest message,
// so a small firstVisibleItemIndex means the user scrolled back down away
// from the older content they'd pulled in.
private const val RELEASE_OLDER_SCROLL_THRESHOLD = 20
