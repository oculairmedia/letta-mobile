package com.letta.mobile.data.chat.approval

import com.letta.mobile.data.runtime.ApprovalRelay
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 *  (F14): the offline outbox over an Iroh channel [transport], shared by the
 * Android chat and the desktop Iroh gateway.
 *
 * - Connected is the transport's own state.
 * - "Still pending" is the host's `pending_approvals` relay ([pending]) once the host advertises it
 *   ([ApprovalRelay.CAPABILITY]); an older host cannot say, so the answer is sent and the host decides.
 * - The host refusing an answer as no longer pending (`approval_rejected`) is a stale answer: it is
 *   dropped, never retried, and [onStale] takes its card down at once.
 */
class ApprovalOutboxChannel(
    private val transport: IChannelTransport,
    private val pending: StateFlow<Map<String, PendingApprovalDetails>>?,
    private val send: suspend (OutboxApproval) -> Unit,
    private val onStale: (OutboxApproval) -> Unit = {},
) {
    fun coordinator(scope: CoroutineScope, outbox: ApprovalOutbox): ApprovalOutboxCoordinator = ApprovalOutboxCoordinator(
        scope = scope,
        outbox = outbox,
        isConnected = { isConnected(transport.state.value) },
        connectionChanges = transport.state.map(::isConnected).distinctUntilChanged(),
        stillPending = ::stillPending,
        send = ::sendOnce,
    )

    internal fun stillPending(entry: OutboxApproval): Boolean? {
        val parked = pending ?: return null
        if (ApprovalRelay.CAPABILITY !in transport.hostCapabilities) return null
        return entry.key in parked.value
    }

    internal suspend fun sendOnce(entry: OutboxApproval): OutboxSendOutcome = try {
        send(entry)
        OutboxSendOutcome.Accepted
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        if (!isNoLongerPending(error.message)) throw error
        onStale(entry)
        OutboxSendOutcome.NoLongerPending
    }

    companion object {
        /** The host's refusal of an answer whose request it no longer holds. */
        fun isNoLongerPending(message: String?): Boolean {
            val text = message?.lowercase() ?: return false
            return STALE_MARKERS.any { it in text }
        }

        private val STALE_MARKERS = listOf("approval_rejected", "no longer pending", "no longer offered")

        private fun isConnected(state: ChannelTransportState): Boolean = state is ChannelTransportState.Connected
    }
}
