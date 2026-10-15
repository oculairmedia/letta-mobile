package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.runtime.PendingApprovalDetails
import kotlinx.coroutines.flow.StateFlow

/**
 * letta-mobile-by4vm.79: the approval state a chat host owns, read by a session port instead of
 * tracking its own: answers in flight, answers waiting in the offline outbox, and what each parked
 * control request offered (by tool call id).
 */
interface ChatApprovalStateSource {
    val submittingApprovals: StateFlow<Set<String>>
    val queuedApprovals: StateFlow<Set<String>>
    val pendingApprovalDetails: StateFlow<Map<String, PendingApprovalDetails>>
}
