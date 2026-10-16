package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.desktop.chat.DesktopCanonicalOpenRequest
import com.letta.mobile.desktop.chat.DesktopChatController
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Host-side holder for the canonical route. A coordinator is bound to exactly one transport, and
 * Desktop resolves a different transport for default-shim conversations, so this keeps one runtime
 * per distinct transport rather than assuming a single one for the whole app.
 */
class DesktopCanonicalTimelineHost(
    private val backendId: String = DESKTOP_CANONICAL_BACKEND_ID,
    private val ledgerDirectory: Path = defaultDesktopTimelineLedgerDirectory(),
) {
    private val mutex = Mutex()
    private val runtimes = mutableMapOf<TimelineTransport, DesktopCanonicalTimelineRuntime>()
    private var closed = false

    suspend fun open(request: DesktopCanonicalOpenRequest): CanonicalTimelinePresentation {
        val runtime = mutex.withLock {
            check(!closed) { "Desktop canonical timeline host is closed" }
            runtimeFor(request.transport)
        }
        return runtime.open(request.agentId, request.conversationId, request.scope)
    }

    private fun runtimeFor(transport: TimelineTransport): DesktopCanonicalTimelineRuntime =
        runtimes.getOrPut(transport) { createDesktopCanonicalTimelineRuntime(transport, backendId, ledgerDirectory) }

    /**
     * Routes the controller's conversation selection through this host. Only a backend whose frame
     * source carries live turns (Iroh) can serve this route: the shared send coordinator sends over
     * it, so on any other backend the controller keeps its in-memory timeline loop.
     */
    internal fun installOn(controller: DesktopChatController, send: DesktopCanonicalSendInstall) {
        controller.canonicalEligible = ::servesConversation
        controller.canonicalOpen = { request -> open(request) }
        // Reading history and writing to it are installed together on purpose. A route that can
        // page but not send is a transcript the user cannot reply in, and nothing downstream would
        // report that as broken.
        val coordinators = DesktopCanonicalChatSend(
            DesktopCanonicalSendBindings(
                runtimeFor = ::runtimeFor,
                bridge = WsChatBridge(send.frameSource),
                conversationRepository = send.conversationRepository,
                scope = send.scope,
                activeConfig = send.activeConfig,
                surface = controller.sendSurface,
                // The draft is already consumed and the composer cleared by the reducer before the
                // coordinator sees the send, so there is nothing left to clear here.
                clearComposerAfterSend = {},
                activeConversationId = { controller.state.value.selectedConversationId },
                setActiveConversationId = { conversationId ->
                    // Only a conversation the coordinator CREATED differs from the selection.
                    // Reselecting the current one would cancel and reopen the presentation on every
                    // send, which reads as the transcript flickering mid-prompt.
                    if (controller.state.value.selectedConversationId != conversationId) {
                        controller.selectConversation(conversationId)
                    }
                },
                // Selecting the conversation is what opens its presentation on this route, so the
                // observer the Android host starts here already exists by the time this is called.
                startTimelineObserver = {},
                clientVersion = send.clientVersion,
            ),
        )
        controller.canonicalSendFor = coordinators::forAgent
    }

    /**
     * A default-shim conversation is addressed by a rewritten loop id, so a canonical scope built
     * from its real id would not name the same history. Keep those on the in-memory loop.
     */
    internal fun servesConversation(conversationId: String): Boolean =
        !conversationId.startsWith(DEFAULT_SHIM_CONVERSATION_PREFIX)

    suspend fun close() {
        val retiring = mutex.withLock {
            if (closed) return
            closed = true
            runtimes.values.toList().also { runtimes.clear() }
        }
        retiring.forEach { it.close() }
    }
}

const val DESKTOP_CANONICAL_BACKEND_ID: String = "desktop-canonical"

private const val DEFAULT_SHIM_CONVERSATION_PREFIX = "conv-default-"

/**
 * The app-level facts the send coordinator needs.
 *
 * [frameSource] is the channel transport carrying live turn frames. It is NOT the ledger's page
 * source: that is a TimelineTransport resolved per conversation by the controller, because a
 * default-shim conversation is paged over a different transport than the gateway. Conflating the
 * two would bind a conversation's writer to a ledger indexing someone else's history.
 */
internal class DesktopCanonicalSendInstall(
    val frameSource: IChannelTransport,
    /** Read when an agent's coordinator is first built, so it sees the rebuilt session graph. */
    val conversationRepository: () -> IConversationRepository,
    val scope: CoroutineScope,
    val activeConfig: () -> LettaConfig?,
    val clientVersion: () -> String,
)
