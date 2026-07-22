package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.feature.chat.subagent.ActiveSubagent
import com.letta.mobile.feature.chat.subagent.ActiveSubagentSource
import com.letta.mobile.feature.chat.subagent.SelfTodoSource
import com.letta.mobile.feature.chat.subagent.withLingeringTerminals
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.time.Duration.Companion.milliseconds

internal data class ChatScreenSubagentBarState(
    val activeSubagents: ImmutableList<ActiveSubagent>,
    val lingerTick: Long,
)

@Composable
internal fun rememberChatScreenSubagentBarState(
    resolvedSubagentSource: ActiveSubagentSource,
    resolvedSelfTodoSource: SelfTodoSource,
    currentConversationId: String?,
): ChatScreenSubagentBarState {
    val subagentSnapshot by resolvedSubagentSource.activeSubagents
        .collectAsStateWithLifecycle(initialValue = persistentListOf())
    var lingerTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(subagentSnapshot) {
        while (subagentSnapshot.any { it.isTerminal || it.isActive }) {
            lingerTick = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000.milliseconds)
        }
        lingerTick = System.currentTimeMillis()
    }
    val selfEntry by remember(resolvedSelfTodoSource, currentConversationId) {
        if (currentConversationId.isNullOrBlank()) {
            kotlinx.coroutines.flow.flowOf<ActiveSubagent?>(null)
        } else {
            resolvedSelfTodoSource.selfEntry(currentConversationId)
        }
    }.collectAsStateWithLifecycle(initialValue = null)
    val activeSubagents = remember(subagentSnapshot, lingerTick, selfEntry) {
        val subagents = subagentSnapshot.withLingeringTerminals(lingerTick)
        val self = selfEntry
        if (self == null) subagents
        else (listOf(self) + subagents).toImmutableList()
    }
    return ChatScreenSubagentBarState(
        activeSubagents = activeSubagents,
        lingerTick = lingerTick,
    )
}
