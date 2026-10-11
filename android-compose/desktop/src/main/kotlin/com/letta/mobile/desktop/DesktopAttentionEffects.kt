package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import com.letta.mobile.data.notify.AttentionDecision
import com.letta.mobile.data.notify.AttentionEvent
import com.letta.mobile.data.notify.AttentionGate
import com.letta.mobile.data.notify.AttentionPrefsRepository
import com.letta.mobile.data.notify.ApprovalAttentionMonitor
import com.letta.mobile.data.presence.ConversationRunState
import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.launcher.windows.WindowsBadgeManager
import dev.nucleusframework.taskbarprogress.TaskbarProgress
import java.awt.Window
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.StateFlow

/**
 * letta-mobile-bzvro.27 (F27): what the desktop's attention notifications read. [runs] is the
 * window's run registry, [prefs] the person's mutes, and the lookups name agents and conversations.
 */
internal data class DesktopAttentionBindings(
    val runs: StateFlow<Map<String, ConversationRunState>>,
    val prefs: AttentionPrefsRepository,
    val agentNameFor: (agentId: String) -> String?,
    val agentIdForConversation: (conversationId: String) -> String?,
)

/** An approval to announce, with the agent's display name when the window knows it. */
internal data class DesktopApprovalNotice(val event: AttentionEvent.ApprovalNeeded, val agentName: String?)

/**
 * One window's attention policy: the shared [AttentionGate] with "the window has focus" as the
 * person looking. Approval toasts and the finished-reply toast both ask it, so a muted agent is
 * silent on both.
 */
internal class DesktopAttention(
    val bindings: DesktopAttentionBindings,
    val window: Window,
    val controller: DesktopNucleusController,
) {
    val gate = AttentionGate(prefs = { bindings.prefs.prefs.value }, focus = { window.isFocused })
    private val finishedTurns = AtomicLong()

    /** Whether the finished-reply toast for [conversationId] may post. */
    fun allowsTurnDone(conversationId: String?): Boolean {
        val agentId = conversationId?.let(bindings.agentIdForConversation) ?: return true
        val event = AttentionEvent.TurnDone(agentId, conversationId, finishedTurns.incrementAndGet().toString())
        return gate.decide(event) is AttentionDecision.Post
    }
}

@Composable
internal fun rememberDesktopAttention(
    bindings: DesktopAttentionBindings,
    window: Window,
    controller: DesktopNucleusController,
): DesktopAttention = remember(bindings, window, controller) { DesktopAttention(bindings, window, controller) }

/**
 * Posts a toast for each approval the gate lets through; clicking it (or Open) raises the window
 * on that conversation. The taskbar attention and badge clear on focus like the finished toast's.
 */
@Composable
internal fun ApprovalAttentionEffect(attention: DesktopAttention, onOpen: (conversationId: String) -> Unit) {
    val open by rememberUpdatedState(onOpen)
    LaunchedEffect(attention) {
        ApprovalAttentionMonitor(attention.gate).watch(attention.bindings.runs) { event ->
            attention.controller.notifyApprovalNeeded(DesktopApprovalNotice(event, attention.bindings.agentNameFor(event.agentId))) {
                open(event.conversationId)
            }
            TaskbarProgress.requestAttention(attention.window)
            if (Platform.Current == Platform.Windows && WindowsBadgeManager.isAvailable) WindowsBadgeManager.setCount(1)
        }
    }
}
