package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource
import com.letta.mobile.feature.chat.subagent.SelfTodoSource
import com.letta.mobile.ui.chat.subagents.ActiveSubagent
import com.letta.mobile.ui.chat.subagents.rememberSubagentRingsState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.flowOf

internal data class ChatScreenSubagentBarState(
    val activeSubagents: ImmutableList<ActiveSubagent>,
    val lingerTick: Long,
)

/**
 * The Android binding of the shared rings' state (letta-mobile-ckmub.3.19): the active subagents
 * from [resolvedSubagentSource] and the agent's own plan from [resolvedSelfTodoSource], on the
 * shared clock ([rememberSubagentRingsState]).
 */
@Composable
internal fun rememberChatScreenSubagentBarState(
    resolvedSubagentSource: ActiveSubagentSource,
    resolvedSelfTodoSource: SelfTodoSource,
    currentConversationId: String?,
): ChatScreenSubagentBarState {
    val subagentSnapshot by resolvedSubagentSource.activeSubagents
        .collectAsStateWithLifecycle(initialValue = persistentListOf())
    val selfEntry by remember(resolvedSelfTodoSource, currentConversationId) {
        if (currentConversationId.isNullOrBlank()) flowOf(null) else resolvedSelfTodoSource.selfEntry(currentConversationId)
    }.collectAsStateWithLifecycle(initialValue = null)
    val rings = rememberSubagentRingsState(subagentSnapshot, selfEntry)
    return ChatScreenSubagentBarState(activeSubagents = rings.subagents, lingerTick = rings.now)
}
