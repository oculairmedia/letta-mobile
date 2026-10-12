package com.letta.mobile.feature.chat.screen.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.subagents.projectSubagentRoster
import com.letta.mobile.feature.chat.screen.ChatScreenNavigationCallbacks
import com.letta.mobile.feature.chat.screen.ChatScreenSubagentBarState
import com.letta.mobile.feature.chat.subagent.ActiveSubagent
import com.letta.mobile.feature.chat.subagent.ActiveSubagentRings
import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource
import com.letta.mobile.feature.chat.subagent.LocalSubagentTodoSheetOpener
import com.letta.mobile.feature.chat.subagent.SelfTodoSource
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheet
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheetState
import com.letta.mobile.feature.chat.subagent.SubagentTodoSheetTarget
import com.letta.mobile.feature.chat.subagent.subagentTodoSheetStateFrom
import com.letta.mobile.ui.chat.subagents.SubagentRosterList
import com.letta.mobile.ui.components.FloatingBanner
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** letta-mobile-bglj6.1: where the shared page's subagent affordances read from. */
internal data class SharedChatSubagentInputs(
    val source: ActiveSubagentSource,
    val selfTodoSource: SelfTodoSource,
    /** The active subagents the rings draw (ticks while any is live). */
    val barState: ChatScreenSubagentBarState,
)

/**
 * letta-mobile-bglj6.1: the subagent todo sheet the shared page opens: a tapped dispatch opens its
 * todo sheet at once, and the sheet offers the subagent's conversation once [ActiveSubagentSource]
 * resolves it.
 */
@Stable
internal class SharedChatSubagentSheetState(
    private val source: ActiveSubagentSource,
    private val scope: CoroutineScope,
) {
    var target: SubagentTodoSheetTarget? by mutableStateOf(null)

    /**
     * The floating banner text ("conversation is not available yet"), blank when none shows;
     * [SharedChatSubagentBanner] draws it and clears it (letta-mobile-bglj6.1.22).
     */
    var bannerMessage: String by mutableStateOf("")

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
 * Opens [target]'s todo sheet at once, then (when the dispatch has no conversation yet) resolves
 * the subagent's agent and conversation in [scope] and re-targets the sheet so it can offer
 * "view conversation".
 */
internal fun openSubagentTodoSheet(
    target: SubagentTodoSheetTarget,
    source: ActiveSubagentSource,
    scope: CoroutineScope,
    onTarget: (SubagentTodoSheetTarget) -> Unit,
) {
    onTarget(target)
    if (target.subagentConversationId != null) return
    scope.launch {
        val subagent = source.resolveSubagent(target.toolCallId).getOrNull()
        val agentId = target.subagentAgentId ?: subagent?.subagentAgentId
        val conversationId = subagent?.let { source.resolveConversationId(it).getOrNull() }
        if (agentId != null && conversationId != null) {
            onTarget(target.copy(subagentAgentId = agentId, subagentConversationId = conversationId))
        }
    }
}

/**
 * The active-subagent rings over the full-screen timeline (the shared page's
 * `ChatSurfacePlatform.timelineOverlay`): a ring opens the subagent's todo sheet; its "view
 * conversation" navigates once the conversation resolves.
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
        CompositionLocalProvider(LocalSubagentTodoSheetOpener provides state::open) {
            ActiveSubagentRings(
                subagents = inputs.barState.activeSubagents,
                now = inputs.barState.lingerTick,
                onRingClick = { subagent -> state.target = subagent.sheetTarget() },
                onViewConversation = { subagent ->
                    scope.launch { viewSubagentConversation(subagent, inputs.source, navigation, haptic, state) }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(LettaDimens.Space.sm),
            )
        }
    }
}

private fun ActiveSubagent.sheetTarget() = SubagentTodoSheetTarget(
    toolCallId = id,
    description = description,
    subagentAgentId = subagentAgentId,
    subagentConversationId = subagentConversationId,
)

/** Opens the subagent's conversation, or its todo sheet with a banner while it has none yet. */
private suspend fun viewSubagentConversation(
    subagent: ActiveSubagent,
    source: ActiveSubagentSource,
    navigation: ChatScreenNavigationCallbacks,
    haptic: HapticFeedback,
    state: SharedChatSubagentSheetState,
) {
    val open = navigation.onViewSubagentConversation
    val agentId = subagent.subagentAgentId?.takeIf { it.isNotBlank() }
    val conversationId = agentId?.let { source.resolveConversationId(subagent).getOrNull()?.takeIf { it.isNotBlank() } }
    if (open != null && agentId != null && conversationId != null) {
        HapticEffects.longPress(haptic)
        open(agentId, conversationId)
        return
    }
    state.target = subagent.sheetTarget()
    state.bannerMessage = "Subagent conversation is not available yet"
}

/**
 * The floating banner over the top of the page (canvas or full screen), shown for a few seconds
 * when a ring's subagent conversation cannot be opened yet.
 */
@Composable
internal fun SharedChatSubagentBanner(state: SharedChatSubagentSheetState) {
    val message = state.bannerMessage
    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            delay(SUBAGENT_BANNER_MILLIS.milliseconds)
            state.bannerMessage = ""
        }
    }
    Box(Modifier.fillMaxSize()) {
        FloatingBanner(
            visible = message.isNotBlank(),
            text = message,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(LettaDimens.Space.lg),
        )
    }
}

private const val SUBAGENT_BANNER_MILLIS = 2_600

/** The open todo sheet, if any, with the shared subagent roster under it. */
@Composable
internal fun SharedChatSubagentSheet(
    state: SharedChatSubagentSheetState,
    inputs: SharedChatSubagentInputs,
    currentConversationId: String?,
    navigation: ChatScreenNavigationCallbacks,
) {
    val sheetTarget = state.target ?: return
    var todoState by remember(sheetTarget.toolCallId) {
        mutableStateOf<SubagentTodoSheetState>(SubagentTodoSheetState.Loading)
    }
    LaunchedEffect(inputs.source, inputs.selfTodoSource, sheetTarget.toolCallId, currentConversationId) {
        val todos = if (sheetTarget.toolCallId == ActiveSubagent.SELF_ID) {
            Result.success(inputs.selfTodoSource.todos(currentConversationId.orEmpty()))
        } else {
            inputs.source.todos(sheetTarget.toolCallId)
        }
        todoState = subagentTodoSheetStateFrom(todos)
    }
    val open = navigation.onViewSubagentConversation
    val agentId = sheetTarget.subagentAgentId?.takeIf { it.isNotBlank() }
    val conversationId = sheetTarget.subagentNavigationConversationId
    SubagentTodoSheet(
        description = sheetTarget.description,
        state = todoState,
        onDismiss = state::dismiss,
        onViewConversation = if (open != null && agentId != null && conversationId != null) {
            {
                state.target = null
                open(agentId, conversationId)
            }
        } else {
            null
        },
        roster = { SharedChatSubagentRoster(inputs.source, navigation) { state.target = null } },
    )
}

/**
 * letta-mobile-ulcg6 (PR4): the shared subagent roster inside the sheet, from the same projection
 * the desktop Background tasks pane renders.
 */
@Composable
private fun SharedChatSubagentRoster(
    source: ActiveSubagentSource,
    navigation: ChatScreenNavigationCallbacks,
    onLeaveSheet: () -> Unit,
) {
    val entries by source.rosterEntries.collectAsStateWithLifecycle()
    var clearedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    val roster = remember(entries, clearedKeys) {
        projectSubagentRoster(entries, System.currentTimeMillis(), clearedKeys)
    }
    val openConversation = navigation.onViewSubagentConversation
    SubagentRosterList(
        roster = roster,
        onOpenConversation = openConversation?.let { open ->
            { item ->
                val agentId = item.childAgentId
                val conversationId = item.childConversationId
                if (agentId != null && conversationId != null) {
                    onLeaveSheet()
                    open(agentId, conversationId)
                }
            }
        },
        onClearFinished = { clearedKeys = clearedKeys + roster.finished.map { it.key } },
    )
}
