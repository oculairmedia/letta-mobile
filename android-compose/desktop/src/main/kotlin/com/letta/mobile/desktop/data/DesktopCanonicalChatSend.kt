package com.letta.mobile.desktop.data

import com.letta.mobile.data.chat.send.ChatSendCoordinator
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.desktop.chat.DesktopChatSendSurface
import com.letta.mobile.desktop.chat.DesktopChatSendUiSink
import java.util.UUID
import kotlinx.coroutines.CoroutineScope

/** What a coordinator needs from the app that is not the agent it is being built for. */
internal class DesktopCanonicalSendBindings(
    val runtimeFor: (TimelineTransport) -> DesktopCanonicalTimelineRuntime,
    val bridge: WsChatBridge,
    val conversationRepository: IConversationRepository,
    val scope: CoroutineScope,
    val activeConfig: () -> LettaConfig?,
    val surface: DesktopChatSendSurface,
    val clearComposerAfterSend: () -> Unit,
    val activeConversationId: () -> String?,
    val setActiveConversationId: (String) -> Unit,
    val startTimelineObserver: (String) -> Unit,
    val clientVersion: () -> String,
)

/**
 * Builds one shared [ChatSendCoordinator] per agent and keeps it.
 *
 * The coordinator holds per-conversation turn state and a pending-send queue that outlive any one
 * selection, so rebuilding it when the user switches conversations would discard the identity of a
 * turn still in flight. It is per agent rather than global because the coordinator is constructed
 * with an agent id and a writer captured for that agent, and a captured writer must never answer
 * for a conversation owned by another.
 */
internal class DesktopCanonicalChatSend(
    private val bindings: DesktopCanonicalSendBindings,
) {
    private val coordinators = mutableMapOf<Key, ChatSendCoordinator>()

    /**
     * [timelineTransport] is the ledger's page source and is resolved per conversation, which is a
     * different object from the frame source the bridge reads. Keying on both keeps a writer bound
     * to the transport whose history it indexes.
     */
    fun forAgent(agentId: String, timelineTransport: TimelineTransport): ChatSendCoordinator =
        coordinators.getOrPut(Key(agentId, timelineTransport)) {
        ChatSendCoordinator(
            scope = bindings.scope,
            agentId = agentId,
            activeConfig = bindings.activeConfig,
            wsChatBridge = bindings.bridge,
            // The send lands in the same ledger the paginated list pages from. This single argument
            // is what makes the canonical route one subsystem rather than two agreeing ones.
            timelineRepository = bindings.runtimeFor(timelineTransport).writer(agentId, bindings.scope),
            conversationRepository = bindings.conversationRepository,
            ui = DesktopChatSendUiSink(bindings.surface),
            clearComposerAfterSend = bindings.clearComposerAfterSend,
            activeConversationId = bindings.activeConversationId,
            setActiveConversationId = bindings.setActiveConversationId,
            startTimelineObserver = bindings.startTimelineObserver,
            clientVersion = bindings.clientVersion,
            otidGenerator = { UUID.randomUUID().toString() },
        )
    }

    private data class Key(val agentId: String, val timelineTransport: TimelineTransport)
}
