package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.data.chat.approval.ApprovalOutboxCoordinator
import com.letta.mobile.data.chat.approval.OutboxApproval
import com.letta.mobile.data.chat.approval.OutboxNotice
import com.letta.mobile.data.chat.approval.OutboxSubmitResult
import com.letta.mobile.data.runtime.ApprovalRelayClient
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.data.runtime.RelayedPermissionModes
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.chat.session.toUiState
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * letta-mobile-bzvro.40: what the Android chat needs from its Iroh host's approval relay: the parked
 * requests (joined onto the timeline so they draw as cards with their rules and diffs) and the
 * conversation's permission-mode chip. Thin binding over the sharedLogic classes.
 */
class ChatApprovalRelay @Inject constructor(
    private val client: ApprovalRelayClient,
    private val modes: RelayedPermissionModes,
    private val transport: IChannelTransport,
    /** letta-mobile-bzvro.14: answers made while the link is down, replayed after the reconnect. */
    val outbox: ApprovalOutboxCoordinator,
) {
    val pendingDetails: StateFlow<Map<String, PendingApprovalDetails>> get() = client.pending

    /**
     * The chip for [runtime], or null (no chip) while the connected host cannot read or change modes.
     * Re-evaluated on every connection change, since the capability comes with the host's handshake.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun permissionMode(runtime: AppServerRuntimeScope?): Flow<ChatPermissionModeUiState?> {
        if (runtime == null) return flowOf(null)
        return transport.state
            .map { modes.supported }
            .distinctUntilChanged()
            .flatMapLatest { supported -> if (supported) chip(runtime) else flowOf(null) }
    }

    /** This client's view at once; the host's answer then confirms or replaces it. */
    private fun chip(runtime: AppServerRuntimeScope): Flow<ChatPermissionModeUiState> = channelFlow {
        launch { modes.sync(runtime) }
        modes.observe(runtime).collect { send(it.toUiState()) }
    }

    suspend fun setPermissionMode(runtime: AppServerRuntimeScope, mode: AppServerPermissionMode): Boolean =
        modes.supported && modes.set(runtime, mode)

    /** The answer for [toolCallId] was accepted: its card goes now. */
    fun resolve(toolCallId: String) = client.resolve(com.letta.mobile.runtime.ToolCallId(toolCallId))
}

/**
 * letta-mobile-bzvro.40: one chat screen's view of a [ChatApprovalRelay] for [agentId]'s
 * conversations; inert (no parked requests, no chip) when the screen has no relay.
 */
internal class ChatApprovalBinding(private val relay: ChatApprovalRelay?, private val agentId: String) {
    val pendingDetails: StateFlow<Map<String, PendingApprovalDetails>> = relay?.pendingDetails ?: NONE

    /** letta-mobile-bzvro.14: the requests answered offline and waiting. */
    val queuedRequestIds: StateFlow<Set<String>> = relay?.outbox?.queuedRequestIds ?: NONE_QUEUED

    /** letta-mobile-bzvro.14: what the person is told once queued answers are sent or dropped. */
    val outboxNotices: Flow<OutboxNotice> = relay?.outbox?.notices ?: emptyFlow()

    /**
     * letta-mobile-bzvro.14: [entry] through the outbox (sent now, or queued while the link is down);
     * null when this screen has none, or its backend is not a channel transport (HTTP sends directly).
     */
    suspend fun deliver(entry: OutboxApproval, overChannel: Boolean): OutboxSubmitResult? =
        relay?.outbox?.takeIf { overChannel }?.submit(entry)

    /** The chip for whichever conversation [conversationIds] names now. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun permissionMode(conversationIds: Flow<String?>): Flow<ChatPermissionModeUiState?> {
        val owner = relay ?: return flowOf(null)
        return conversationIds.map(::runtimeOf).distinctUntilChanged().flatMapLatest(owner::permissionMode)
    }

    fun setPermissionMode(scope: CoroutineScope, conversationId: String?, mode: AppServerPermissionMode) {
        val owner = relay ?: return
        val runtime = runtimeOf(conversationId) ?: return
        scope.launch { owner.setPermissionMode(runtime, mode) }
    }

    private fun runtimeOf(conversationId: String?): AppServerRuntimeScope? {
        val conversation = conversationId?.takeIf { it.isNotBlank() } ?: return null
        return agentId.takeIf { it.isNotBlank() }?.let { AppServerRuntimeScope(it, conversation) }
    }

    private companion object {
        val NONE: StateFlow<Map<String, PendingApprovalDetails>> = MutableStateFlow(emptyMap())
        val NONE_QUEUED: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    }
}
