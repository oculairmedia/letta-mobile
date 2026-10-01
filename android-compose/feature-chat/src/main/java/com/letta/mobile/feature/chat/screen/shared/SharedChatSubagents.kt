package com.letta.mobile.feature.chat.screen.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import com.letta.mobile.feature.chat.screen.ChatScreenSubagentBarState
import com.letta.mobile.feature.chat.screen.ChatScreenSubagentRingsOverlay
import com.letta.mobile.feature.chat.screen.ChatScreenSubagentRingsOverlayParams
import com.letta.mobile.feature.chat.screen.ChatScreenNavigationCallbacks
import com.letta.mobile.feature.chat.screen.ChatScreenSubagentTodoSheet
import com.letta.mobile.feature.chat.screen.ChatScreenSubagentTodoSheetParams
import com.letta.mobile.feature.chat.screen.openSubagentTodoSheet
import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource
import com.letta.mobile.feature.chat.subagent.SelfTodoSource
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheetTarget
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.CoroutineScope

/** letta-mobile-bglj6.1: where the shared page's subagent affordances read from. */
internal data class SharedChatSubagentInputs(
    val source: ActiveSubagentSource,
    val selfTodoSource: SelfTodoSource,
    /** The active subagents the rings draw (ticks while any is live). */
    val barState: ChatScreenSubagentBarState,
)

/**
 * letta-mobile-bglj6.1: the subagent todo sheet the shared page opens, exactly as the legacy
 * layout does: a tapped dispatch opens its todo sheet at once, and the sheet offers the
 * subagent's conversation once [ActiveSubagentSource] resolves it.
 */
@Stable
internal class SharedChatSubagentSheetState(
    private val source: ActiveSubagentSource,
    private val scope: CoroutineScope,
) {
    var target: SubagentTodoSheetTarget? by mutableStateOf(null)

    /** Opens [target]'s sheet (from a ring or a resolved row). */
    fun open(target: SubagentTodoSheetTarget) = openSubagentTodoSheet(target, source, scope) { this.target = it }

    /** The shared page's `ChatSurfaceHost.openSubagent`. */
    fun openDispatch(toolCallId: String, subagentAgentId: String?, description: String) =
        open(SubagentTodoSheetTarget(toolCallId, description, subagentAgentId))

    fun dismiss() {
        target = null
    }
}

@Composable
internal fun rememberSharedChatSubagentSheetState(source: ActiveSubagentSource): SharedChatSubagentSheetState {
    val scope = rememberCoroutineScope()
    return remember(source, scope) { SharedChatSubagentSheetState(source, scope) }
}

/**
 * The active-subagent rings over the full-screen timeline (the shared page's
 * `ChatSurfacePlatform.timelineOverlay`), as the legacy layout draws them: a ring opens the
 * subagent's todo sheet; its "view conversation" navigates once the conversation resolves.
 */
@Composable
internal fun SharedChatSubagentRings(
    state: SharedChatSubagentSheetState,
    inputs: SharedChatSubagentInputs,
    navigation: ChatScreenNavigationCallbacks,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        ChatScreenSubagentRingsOverlay(
            ChatScreenSubagentRingsOverlayParams(
                barState = inputs.barState,
                resolvedSubagentSource = inputs.source,
                navigation = navigation,
                // The shared page has no floating banner; the fallback still opens the sheet.
                onFloatingBannerMessageChange = {},
                openSubagentTarget = state::open,
                onTargetChange = { state.target = it },
                subagentNavigationScope = scope,
                haptic = haptic,
                modifier = Modifier.align(Alignment.TopEnd).padding(LettaDimens.Space.sm),
            ),
        )
    }
}

/** The open sheet, if any; reuses the legacy layout's [ChatScreenSubagentTodoSheet]. */
@Composable
internal fun SharedChatSubagentSheet(
    state: SharedChatSubagentSheetState,
    inputs: SharedChatSubagentInputs,
    currentConversationId: String?,
    navigation: ChatScreenNavigationCallbacks,
) {
    ChatScreenSubagentTodoSheet(
        params = ChatScreenSubagentTodoSheetParams(
            target = state.target,
            resolvedSubagentSource = inputs.source,
            resolvedSelfTodoSource = inputs.selfTodoSource,
            currentConversationId = currentConversationId,
            navigation = navigation,
            onDismiss = state::dismiss,
            onTargetUpdate = { state.target = it },
        ),
    )
}
