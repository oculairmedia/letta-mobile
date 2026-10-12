package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.letta.mobile.data.chat.runtime.nowActiveStatus
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopChatSurfaceState
import com.letta.mobile.ui.shell.nowactive.NowActiveBarActions
import com.letta.mobile.ui.shell.nowactive.NowActiveBarState

/**
 * Binding only (letta-mobile-ckmub.3.14): the shared now-active identity and run controls
 * (`ui/shell/nowactive/NowActiveBar.kt`) draw in the window header; this file maps the desktop
 * chat controller's activity state onto them.
 */
@Immutable
internal data class NowActiveBarHostState(
    val thinkingConversationId: String?,
    val isStreamingReplySelected: Boolean,
)

@Immutable
internal data class NowActiveBarHostActions(
    /** Select + reveal the given conversation (identity block and work chip). */
    val onOpenConversation: (String) -> Unit,
    /** Interrupt the given conversation's in-flight run. */
    val onStopRun: (String) -> Unit,
)

/** Result of pinning a conversation for the header identity block: which
 * conversation it is (so callers can scope actions to it) plus the state to
 * render. */
@Immutable
internal data class NowActiveBarPin(
    val conversationId: String,
    val state: NowActiveBarState,
)

/**
 * Derives the header identity block's pinned conversation and live status
 * from controller state: pinned to the conversation the user LAST PROMPTED
 * (sticky across browsing, like now-playing), falling back to the selection
 * until the first send. Returns null when there is no conversation at all —
 * callers should degrade to a generic title in that case.
 *
 * Pure by design (no Compose state reads) so the mapping is unit-testable
 * without a Compose UI test harness.
 */
internal fun deriveNowActiveBarPin(
    lastPromptedId: String?,
    streamingId: String?,
    cancellingId: String?,
    chatState: DesktopChatSurfaceState,
    host: NowActiveBarHostState,
): NowActiveBarPin? {
    val barConversation = lastPromptedId
        ?.let { id -> chatState.conversations.firstOrNull { it.id == id } }
        ?: chatState.selectedConversation
        ?: return null
    val barIsSelected = barConversation.id == chatState.selectedConversationId
    return NowActiveBarPin(
        conversationId = barConversation.id,
        state = NowActiveBarState(
            conversationTitle = barConversation.title,
            agentName = barConversation.agentName,
            agentId = barConversation.agentId,
            status = nowActiveStatus(
                isThinking = host.thinkingConversationId == barConversation.id,
                isStreaming = if (barIsSelected) {
                    host.isStreamingReplySelected
                } else {
                    streamingId == barConversation.id
                },
                hasError = barIsSelected && chatState.errorMessage != null,
                isStopping = cancellingId == barConversation.id,
            ),
            backgroundWorkAgentName = host.thinkingConversationId
                ?.takeIf { it != barConversation.id }
                ?.let { tid -> chatState.conversations.firstOrNull { it.id == tid }?.agentName },
        ),
    )
}

/** Identity block state + click handlers ready for the header chrome to render. */
@Immutable
internal data class DesktopHeaderIdentity(
    val state: NowActiveBarState?,
    val actions: NowActiveBarActions,
)

/**
 * State provider (no UI of its own): derives the pinned conversation and live status from
 * controller state; the header chrome (see [DesktopJewelWindow]) renders it with the shared
 * now-active composables, letta-mobile-3arhe.1.
 */
@Composable
internal fun DesktopNowActiveBarHost(
    chatController: DesktopChatController,
    chatState: DesktopChatSurfaceState,
    host: NowActiveBarHostState,
    actions: NowActiveBarHostActions,
): DesktopHeaderIdentity {
    val lastPromptedId by chatController.lastPromptedConversationId.collectAsState()
    val streamingId by chatController.streamingConversationId.collectAsState()
    // letta-mobile-lgns8.19: an abort was sent but the terminal frame hasn't
    // landed — the run is still live, so the header says "stopping…" and the
    // stop button stays available as the local force-clear escape hatch.
    val cancellingId by chatController.cancellingConversationId.collectAsState()
    val pin = deriveNowActiveBarPin(lastPromptedId, streamingId, cancellingId, chatState, host)
        ?: return DesktopHeaderIdentity(state = null, actions = NowActiveBarActions.None)
    return DesktopHeaderIdentity(
        state = pin.state,
        actions = NowActiveBarActions(
            onOpenConversation = { actions.onOpenConversation(pin.conversationId) },
            onJumpToBackgroundWork = {
                host.thinkingConversationId?.let(actions.onOpenConversation)
            },
            // Scoped to the pinned conversation so an unrelated in-flight
            // send can never be cancelled by mistake.
            onStopRun = { actions.onStopRun(pin.conversationId) },
        ),
    )
}
