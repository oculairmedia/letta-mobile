package com.letta.mobile.ui.context

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.letta.mobile.data.context.FocusedContextWindows
import com.letta.mobile.data.context.limit.ContextLimitController
import com.letta.mobile.data.context.limit.ContextLimitOutcome
import com.letta.mobile.data.context.limit.ContextLimitRequest
import com.letta.mobile.data.context.limit.ContextLimitScope
import com.letta.mobile.data.context.limit.ContextLimitStops
import com.letta.mobile.data.context.limit.key
import com.letta.mobile.data.compaction.ConversationCompactRpc
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.launch

/** letta-mobile-joigh: what one conversation's limit slider is about. */
internal data class LimitTarget(
    val agentId: AgentId,
    val conversationId: ConversationId?,
    val modelValue: String?,
    /** The limit in force now (an applied one, the host record, else the client's window). */
    val current: Int?,
    val modelMax: Int?,
    val usedTokens: Int?,
) {
    /** letta-code's own rule: `/context-limit` on the bare `default` conversation updates the agent. */
    val scope: ContextLimitScope
        get() = ContextLimitScope.forConversation(request(0).wireConversationId == ConversationCompactRpc.DEFAULT_CONVERSATION)

    fun request(tokens: Int) = ContextLimitRequest(agentId, conversationId, tokens)

    companion object {
        fun of(focus: AgentContextFocus, windows: FocusedContextWindows, current: Int?, usedTokens: Int?) = LimitTarget(
            agentId = AgentId(focus.agentId),
            conversationId = focus.conversationId?.let(::ConversationId),
            modelValue = focus.modelValue,
            current = current,
            modelMax = windows.modelMax,
            usedTokens = usedTokens,
        )
    }
}

/**
 * letta-mobile-joigh: the sheet's context-limit control for [target]: nothing without a
 * controller or with nothing to choose between, the reason once the backend said it cannot change
 * the limit, else the slider. A change applies through the controller (letta-code's
 * `/context-limit`), then [onApplied] re-reads the host record; letta-code's refusal stays under
 * the slider until the next change.
 */
@Composable
internal fun rememberLimitControl(
    controller: ContextLimitController?,
    target: LimitTarget,
    onApplied: suspend () -> Unit,
): ContextLimitControl? {
    controller ?: return null
    val supported by controller.supported.collectAsState()
    val applying by controller.applying.collectAsState()
    val key = target.request(0).key
    var failure by remember(key) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (supported == false) return ContextLimitControl.Unsupported
    val stops = ContextLimitStops.of(target.modelMax, target.current) ?: return null
    val setting = ContextLimitSetting(stops, target.current, target.usedTokens, target.scope, applying = key in applying, failure = failure)
    return ContextLimitControl.Adjustable(setting) { tokens ->
        scope.launch {
            val outcome = controller.apply(target.request(tokens), target.modelValue) ?: return@launch
            failure = (outcome as? ContextLimitOutcome.Failed)?.message
            if (outcome is ContextLimitOutcome.Applied) onApplied()
        }
    }
}
