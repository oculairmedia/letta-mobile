package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.runtime.PendingApprovalDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * The approval state a chat host owns, read by a session port instead of
 * tracking its own: answers in flight, answers waiting in the offline outbox, and what each parked
 * control request offered (by tool call id).
 */
interface ChatApprovalStateSource {
    val submittingApprovals: StateFlow<Set<String>>
    val queuedApprovals: StateFlow<Set<String>>
    val pendingApprovalDetails: StateFlow<Map<String, PendingApprovalDetails>>
}

/**
 * [this] source as the overlay [withHostApprovals] lays over a timeline; a
 * host without one (null) reports nothing. Every session port reads host approvals through here.
 */
fun ChatApprovalStateSource?.hostApprovals(): Flow<TimelineHostApprovals> {
    val source = this ?: return flowOf(TimelineHostApprovals())
    return combine(source.submittingApprovals, source.queuedApprovals, source.pendingApprovalDetails) { submitting, queued, details ->
        TimelineHostApprovals(submitting, queued, details)
    }
}

/** [hostApprovals] at this instant, for a port's synchronous initial state. */
fun ChatApprovalStateSource.currentHostApprovals(): TimelineHostApprovals =
    TimelineHostApprovals(submittingApprovals.value, queuedApprovals.value, pendingApprovalDetails.value)
