package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.presence.ConversationRunPhasePublisher
import com.letta.mobile.data.presence.ConversationRunRegistry
import com.letta.mobile.data.presence.RunScope
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The chat controller's half of the run-phase pipeline (letta-mobile-8a3bz): folds the gateway's
 * runtime events, the controller's own first-hand events and the composer's typing flag through
 * the shared [ConversationRunPhasePublisher] into the window's [ConversationRunRegistry]. It
 * resolves agent ids from the surface [state], so a caller only ever names the conversation.
 */
internal class DesktopChatRunPhases(
    private val scope: CoroutineScope,
    private val state: StateFlow<DesktopChatSurfaceState>,
    private val registry: ConversationRunRegistry,
    private val closed: () -> Boolean,
) {
    private val publisher = ConversationRunPhasePublisher(registry)
    private var runtimeEventsJob: Job? = null
    private var typingConversationId: String? = null

    private val typingJob: Job = scope.launch {
        state.map { it.selectedConversationId to it.composerText.isNotBlank() }
            .distinctUntilChanged()
            .collect { (conversationId, typing) -> onTypingChanged(conversationId, typing) }
    }

    private fun agentIdFor(conversationId: String): String? =
        state.value.conversations.firstOrNull { it.id == conversationId }?.agentId

    /** Fold one event the controller knows first-hand into [conversationId]'s phase. */
    fun publish(conversationId: String?, payload: RuntimeEventPayload) {
        if (closed()) return
        val id = conversationId ?: return
        val agent = agentIdFor(id) ?: return
        publisher.onEvent(RunScope(id, agent), payload)
    }

    /**
     * Reply tokens are arriving for [conversationId]: the one phase the controller observes
     * first-hand (through its presenter), so it is fed as the assistant frame it stands for.
     */
    fun publishTokens(conversationId: String?) {
        publish(
            conversationId,
            RuntimeEventPayload.RemoteStreamFrame(frameId = "desktop-tokens", messageType = "assistant_message", body = ""),
        )
    }

    /** The user asked to stop [conversationId]'s run; it reads INTERRUPTING until the terminal lands. */
    fun markInterrupting(conversationId: String) {
        if (closed()) return
        // The run being stopped is in the registry under its own agent, even when the surface
        // state does not list the conversation (a run the gateway reported for another window).
        val agent = registry.runs.value[conversationId]?.agentId ?: agentIdFor(conversationId) ?: return
        publisher.markInterrupting(RunScope(conversationId, agent))
    }

    /** The window's subagent registry list, through the shared path Android uses (DELEGATING, dreaming). */
    fun publishSubagents(entries: List<SubagentEntry>) {
        if (closed()) return
        publisher.setSubagents(entries)
    }

    /** Subscribe to [gateway]'s runtime events when it can report them; a gateway that cannot reports nothing. */
    fun bind(gateway: DesktopChatGateway?) {
        runtimeEventsJob?.cancel()
        runtimeEventsJob = null
        val source = gateway as? DesktopRuntimeEventSource ?: return
        runtimeEventsJob = scope.launch {
            source.runtimeEvents.collect { event ->
                if (closed()) return@collect
                val agent = event.agentId.takeIf { it.isNotBlank() } ?: agentIdFor(event.conversationId) ?: return@collect
                publisher.onEvent(RunScope(event.conversationId, agent), event.payload)
            }
        }
    }

    // The composer moved to another conversation, or its text went blank / non-blank: the previous
    // conversation stops "typing" and the current one takes the flag.
    private fun onTypingChanged(conversationId: String?, typing: Boolean) {
        val previous = typingConversationId
        if (previous != null && previous != conversationId) {
            agentIdFor(previous)?.let { publisher.setUserTyping(RunScope(previous, it), false) }
        }
        typingConversationId = conversationId
        val id = conversationId ?: return
        val agent = agentIdFor(id) ?: return
        publisher.setUserTyping(RunScope(id, agent), typing)
    }

    fun close() {
        typingJob.cancel()
        runtimeEventsJob?.cancel()
    }
}
