package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.CanonicalTimelinePresentation
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.desktop.chat.DesktopCanonicalOpenRequest
import com.letta.mobile.desktop.chat.DesktopChatController
import java.nio.file.Path
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Host-side holder for the canonical route. A coordinator is bound to exactly one transport, and
 * Desktop resolves a different transport for default-shim conversations, so this keeps one runtime
 * per distinct transport rather than assuming a single one for the whole app.
 *
 * [isEnabled] is false unless the route is explicitly switched on, because the canonical route is
 * still read-only: sends and the live stream are not bound to the canonical writer yet
 * (bead letta-mobile-x13xi.12.1).
 */
class DesktopCanonicalTimelineHost(
    private val backendId: String = DESKTOP_CANONICAL_BACKEND_ID,
    private val ledgerDirectory: Path = defaultDesktopTimelineLedgerDirectory(),
    val isEnabled: Boolean = desktopCanonicalTimelineEnabled(),
) {
    private val mutex = Mutex()
    private val runtimes = mutableMapOf<TimelineTransport, DesktopCanonicalTimelineRuntime>()
    private var closed = false

    suspend fun open(request: DesktopCanonicalOpenRequest): CanonicalTimelinePresentation {
        val runtime = mutex.withLock {
            check(!closed) { "Desktop canonical timeline host is closed" }
            runtimes.getOrPut(request.transport) {
                checkNotNull(
                    createDesktopCanonicalTimelineRuntime(
                        transport = request.transport,
                        backendId = backendId,
                        ledgerDirectory = ledgerDirectory,
                        enabled = isEnabled,
                    ),
                ) { "Canonical timeline route is disabled" }
            }
        }
        return runtime.open(request.agentId, request.conversationId, request.scope)
    }

    /**
     * Routes the controller's conversation selection through this host. Only call it when the route
     * is enabled: installing an opener makes the controller skip legacy whole-history hydration, so
     * a host that cannot actually serve pages would leave the transcript empty.
     */
    fun installOn(controller: DesktopChatController) {
        check(isEnabled) { "Cannot install a disabled canonical timeline route" }
        controller.canonicalEligible = ::servesConversation
        controller.canonicalOpen = { request -> open(request) }
    }

    /**
     * A default-shim conversation is addressed by a rewritten loop id, so a canonical scope built
     * from its real id would not name the same history. Keep those on the legacy route.
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

/**
 * Distinct from the legacy snapshot backend id on purpose. The two stores are separate generations
 * of the same history, and sharing an id would let a scope built for one address the other.
 */
const val DESKTOP_CANONICAL_BACKEND_ID: String = "desktop-canonical"

private const val DEFAULT_SHIM_CONVERSATION_PREFIX = "conv-default-"
