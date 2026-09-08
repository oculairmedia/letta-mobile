package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Required indexed maintenance; implementations must never acquire a legacy sync loop. */
interface CanonicalTimelineMaintenance {
    suspend fun turnStarted(owner: CanonicalTimelineCoordinator.Owner, runId: String?, turnId: String?)
    suspend fun turnEnded(owner: CanonicalTimelineCoordinator.Owner, clean: Boolean)
    suspend fun cleanup(owner: CanonicalTimelineCoordinator.Owner, runId: String?, turnId: String?, reason: String, candidateRunIds: Set<String>): Int
    suspend fun repairCursor(owner: CanonicalTimelineCoordinator.Owner, fallbackSeq: Long?)
}

/** Captured once when a transport is created, never resolved from the active screen on callbacks. */
class CanonicalTransportScopeResolver(
    private val backendId: String,
    private val isBackendCurrent: (String) -> Boolean,
) {
    init { require(backendId.isNotBlank()) }

    suspend fun resolve(agentId: String?, conversationId: String): TimelineScope {
        check(isBackendCurrent(backendId)) { "Retired transport backend" }
        return TimelineScope(backendId, conversationId, agentId)
    }
}

/**
 * Conversation-owned external transport adapter. No method acquires TimelineRepository.
 * Maintenance is deliberately mandatory: a host cannot accidentally enable a partial writer.
 * The scope resolver must capture the transport's backend, not a screen selection.
 */
class CanonicalExternalTransportWriter(
    private val coordinator: CanonicalTimelineCoordinator,
    private val resolveScope: suspend (String?, String) -> TimelineScope,
    private val maintenance: CanonicalTimelineMaintenance,
    private val now: () -> String = { timelineNow().toString() },
) : TimelineExternalTransportWriter {
    private suspend fun owner(agentId: String?, conversationId: String) =
        coordinator.acquire(resolveScope(agentId, conversationId))

    override suspend fun appendExternalTransportLocal(conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>) =
        appendExternalTransportLocal(null, conversationId, content, otid, attachments)

    override suspend fun appendExternalTransportLocal(agentId: String?, conversationId: String, content: String, otid: String, attachments: List<MessageContentPart.Image>): String {
        coordinator.appendPending(owner(agentId, conversationId), CanonicalPendingLocalStore.Record(otid, content, attachments, now()))
        return otid
    }

    override suspend fun markExternalTransportLocalSent(conversationId: String, otid: String) =
        markExternalTransportLocalSent(null, conversationId, otid)

    override suspend fun markExternalTransportLocalSent(agentId: String?, conversationId: String, otid: String) =
        coordinator.markPending(owner(agentId, conversationId), otid, CanonicalPendingLocalStore.Delivery.Sent)

    override suspend fun markExternalTransportLocalFailed(conversationId: String, otid: String) =
        markExternalTransportLocalFailed(null, conversationId, otid)

    override suspend fun markExternalTransportLocalFailed(agentId: String?, conversationId: String, otid: String) =
        coordinator.markPending(owner(agentId, conversationId), otid, CanonicalPendingLocalStore.Delivery.Failed)

    override suspend fun ingestExternalTransportMessage(conversationId: String, message: LettaMessage, source: String) =
        ingestExternalTransportMessage(null, conversationId, message, source)

    override suspend fun ingestExternalTransportMessage(agentId: String?, conversationId: String, message: LettaMessage, source: String) {
        check(coordinator.ingestExternal(owner(agentId, conversationId), message)) { "External frame rejected by canonical ownership fence" }
    }

    override suspend fun clearExternalTransportActive(conversationId: String) = clearExternalTransportActive(null, conversationId)

    override suspend fun clearExternalTransportActive(agentId: String?, conversationId: String) {
        coordinator.current(resolveScope(agentId, conversationId))?.let { coordinator.completeExternal(it) }
    }

    override suspend fun turnStarted(agentId: String?, conversationId: String, runId: String?, turnId: String?) {
        val owner = owner(agentId, conversationId)
        maintenance.turnStarted(owner, runId, turnId)
        coordinator.beginLive(owner)
    }

    override suspend fun turnEnded(agentId: String?, conversationId: String, clean: Boolean) {
        val owner = owner(agentId, conversationId)
        coordinator.completeExternal(owner)
        maintenance.turnEnded(owner, clean)
    }

    override suspend fun cleanupAbandonedAssistantFragments(agentId: String?, conversationId: String, runId: String?, turnId: String?, reason: String, candidateRunIds: Set<String>): Int =
        maintenance.cleanup(owner(agentId, conversationId), runId, turnId, reason, candidateRunIds)

    override suspend fun repairExpiredConversationCursor(conversationId: String, fallbackSeq: Long?) =
        repairExpiredConversationCursorScoped(null, conversationId, fallbackSeq)

    override suspend fun repairExpiredConversationCursorScoped(agentId: String?, conversationId: String, fallbackSeq: Long?) =
        maintenance.repairCursor(owner(agentId, conversationId), fallbackSeq)

    override suspend fun reconcileExternalTransportSend(conversationId: String, agentId: String, externalConversationId: String, otid: String) =
        reconcileExternalTransportSendScoped(agentId, conversationId, externalConversationId, otid)

    override suspend fun reconcileExternalTransportSendScoped(agentId: String?, conversationId: String, externalConversationId: String, otid: String) {
        require(conversationId == externalConversationId) { "Canonical scope must name the remote conversation" }
        val owner = owner(agentId, conversationId)
        coordinator.completeExternal(owner)
        for (attempt in 0 until 4) {
            if (attempt > 0) delay(250L * attempt)
            coordinator.reconcileRecent(owner)
            if (owner.session.pending.value.none { it.otid == otid }) return
        }
        // An absent echo is not confirmation. Durable pending survives retries/restart.
    }

    override suspend fun reconcileRecentMessages(agentId: String?, conversationId: String, reason: String, forceRefresh: Boolean, connectionGeneration: Long): RecentMessagesReconcileOutcome =
        try {
            val result = coordinator.reconcileRecentDetailed(owner(agentId, conversationId))
            when (result.outcome) {
                TimelineEnginePageOutcome.Applied -> RecentMessagesReconcileOutcome.Applied(result.appended)
                TimelineEnginePageOutcome.Stale -> RecentMessagesReconcileOutcome.Skipped("stale")
                TimelineEnginePageOutcome.NoProgress -> RecentMessagesReconcileOutcome.Skipped("no_progress")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            RecentMessagesReconcileOutcome.Failed(failure)
        }
}
