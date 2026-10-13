package com.letta.mobile.ui.chat.subagents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** The rings to draw and the clock they were filtered against (the stuck / linger checks read it). */
@Immutable
data class SubagentRingsState(
    val subagents: ImmutableList<ActiveSubagent>,
    val now: Long,
)

/**
 * letta-mobile-ckmub.3.19: the rings' clock, the same on every client. While anything visible is
 * live (running, or a finished ring still lingering) the clock ticks once a second so lingering
 * rings expire and stuck rings turn; it stops once nothing would change (letta-mobile-l3j5l: a
 * retained hidden reflection never keeps it ticking). [self] (the agent's own plan) leads the rings.
 */
@Composable
fun rememberSubagentRingsState(snapshot: ImmutableList<ActiveSubagent>, self: ActiveSubagent? = null): SubagentRingsState {
    var tick by remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(snapshot) {
        while (snapshot.any { !it.isHiddenReflection() && (it.isTerminal || it.isActive) }) {
            tick = nowMillis()
            delay(1.seconds)
        }
        tick = nowMillis()
    }
    val subagents = remember(snapshot, tick, self) {
        val lingering = snapshot.withLingeringTerminals(tick)
        if (self == null) lingering else (listOf(self) + lingering).toImmutableList()
    }
    return SubagentRingsState(subagents, tick)
}

private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * letta-mobile-ckmub.3.19: the active-subagent rings over a chat page's timeline, for a host that
 * lists the conversation's registry [entries] (desktop): a ring opens its todo sheet (todos through
 * [loader]) with the conversation's whole roster under it. [onViewConversation] null hides "view
 * conversation" (a host that cannot open a subagent's conversation).
 */
@Composable
fun SubagentRingsOverlay(
    entries: List<SubagentEntry>,
    loader: SubagentTodoLoader,
    modifier: Modifier = Modifier,
    onViewConversation: ((agentId: String, conversationId: String) -> Unit)? = null,
) {
    val snapshot = remember(entries) { entries.map { it.toActiveSubagent() }.toImmutableList() }
    val rings = rememberSubagentRingsState(snapshot)
    var target by remember { mutableStateOf<SubagentTodoSheetTarget?>(null) }
    Box(modifier.fillMaxSize()) {
        ActiveSubagentRings(
            subagents = rings.subagents,
            now = rings.now,
            onRingClick = { target = it.sheetTarget() },
            onViewConversation = { subagent -> subagent.conversation()?.let { (agent, conversation) -> onViewConversation?.invoke(agent, conversation) } },
            modifier = Modifier.align(Alignment.TopEnd).padding(LettaDimens.Space.sm),
        )
    }
    val open = target ?: return
    val conversation = open.conversation()
    SubagentTodoSheetHost(
        target = open,
        loader = loader,
        onDismiss = { target = null },
        onViewConversation = if (onViewConversation != null && conversation != null) {
            {
                target = null
                onViewConversation(conversation.first, conversation.second)
            }
        } else {
            null
        },
        roster = {
            SubagentRoster(
                entries = entries,
                onOpenConversation = onViewConversation?.let { view -> { item -> item.openWith { agent, id -> target = null; view(agent, id) } } },
            )
        },
    )
}

/** A ring's (agent id, conversation id), or null while either is unknown: navigation never guesses. */
private fun ActiveSubagent.conversation(): Pair<String, String>? = sheetTarget().conversation()

/** The sheet's (agent id, conversation id) to offer, or null while either is unknown. */
fun SubagentTodoSheetTarget.conversation(): Pair<String, String>? {
    val agentId = subagentAgentId?.takeIf { it.isNotBlank() } ?: return null
    val conversationId = subagentNavigationConversationId ?: return null
    return agentId to conversationId
}
