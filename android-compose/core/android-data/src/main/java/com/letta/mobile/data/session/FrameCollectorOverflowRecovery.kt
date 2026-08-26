package com.letta.mobile.data.session

import com.letta.mobile.data.timeline.RecentMessagesReconcileOutcome
import com.letta.mobile.data.transport.api.FrameCollectorOverflowIncident
import kotlinx.coroutines.flow.SharedFlow

fun interface FrameCollectorOverflowReconciler {
    suspend fun reconcile(conversationId: String, connectionGeneration: Long): RecentMessagesReconcileOutcome
}

sealed interface FrameCollectorOverflowRecoveryOutcome {
    data object Started : FrameCollectorOverflowRecoveryOutcome
    data class Reconciled(val appended: Int) : FrameCollectorOverflowRecoveryOutcome
    data class NotApplied(val result: RecentMessagesReconcileOutcome) : FrameCollectorOverflowRecoveryOutcome
    data class InvalidIncident(val reason: String) : FrameCollectorOverflowRecoveryOutcome
    data object Failed : FrameCollectorOverflowRecoveryOutcome
    data object Superseded : FrameCollectorOverflowRecoveryOutcome
}

data class FrameCollectorOverflowRecoveryEvent(
    val graphId: Long,
    val subscriptionId: Long,
    val subscriptionIdentity: String,
    val conversationId: String,
    val connectionGeneration: Long,
    val attempt: Int,
    val outcome: FrameCollectorOverflowRecoveryOutcome,
)

interface FrameCollectorOverflowRecoveryMonitor {
    val recoveryEvents: SharedFlow<FrameCollectorOverflowRecoveryEvent>
}

internal fun FrameCollectorOverflowIncident.toRecoveryEvent(
    graphId: Long,
    attempt: Int,
    outcome: FrameCollectorOverflowRecoveryOutcome,
): FrameCollectorOverflowRecoveryEvent = FrameCollectorOverflowRecoveryEvent(
    graphId = graphId,
    subscriptionId = subscriptionId,
    subscriptionIdentity = subscriptionIdentity,
    conversationId = conversationId,
    connectionGeneration = connectionGeneration,
    attempt = attempt,
    outcome = outcome,
)
