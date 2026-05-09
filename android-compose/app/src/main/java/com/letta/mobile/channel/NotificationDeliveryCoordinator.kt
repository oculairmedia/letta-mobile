package com.letta.mobile.channel

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped owner for deciding whether candidate chat events should become
 * Android notifications.
 *
 * Producers submit candidates from their transport-specific layer instead of
 * publishing directly. The coordinator owns shared delivery policy: foreground
 * suppression, notification-reply stream suppression, duplicate suppression,
 * streaming preview gating, publisher invocation, and heartbeat dedupe state.
 *
 * Source semantics:
 * - [NotificationCandidateSource.TimelineIngestion] is the live timeline/SSE
 *   path. Submit settled server events only.
 * - [NotificationCandidateSource.WebsocketClientMode] is a client-mode stream
 *   path. Submit partial chunks as [NotificationCandidatePhase.Partial] and the
 *   final user-visible preview as [NotificationCandidatePhase.Final]. Partials
 *   are recorded as candidates but never published prematurely.
 * - [NotificationCandidateSource.NotificationReplyStream] is a response caused
 *   by a notification RemoteInput reply. These candidates are normally
 *   suppressed while [NotificationReplyHandler.activeReplyStreams] contains the
 *   conversation, preventing the reply surface from notifying itself.
 * - [NotificationCandidateSource.HeartbeatFallback] is the periodic polling
 *   fallback. It shares the same duplicate state as realtime delivery so it can
 *   fill gaps without republishing messages already delivered by another path.
 */
@Singleton
class NotificationDeliveryCoordinator @Inject constructor(
    private val currentConversationTracker: CurrentConversationTracker,
    private val notificationReplyHandler: NotificationReplyHandler,
    private val syncStateStore: ChannelSyncStateStore,
    private val publisher: ChannelNotificationPublisher,
) {
    fun submit(candidate: NotificationDeliveryCandidate): NotificationDeliveryDecision {
        val notificationId = candidate.notificationIdentity
            ?: return NotificationDeliveryDecision.Suppressed(NotificationSuppressionReason.MissingIdentity)

        if (candidate.previewText.isBlank()) {
            return NotificationDeliveryDecision.Suppressed(NotificationSuppressionReason.BlankPreview)
        }

        if (currentConversationTracker.current == candidate.conversationId) {
            return NotificationDeliveryDecision.Suppressed(NotificationSuppressionReason.ForegroundConversation)
        }

        if (
            candidate.source != NotificationCandidateSource.NotificationReplyStream &&
            candidate.conversationId in notificationReplyHandler.activeReplyStreams.value
        ) {
            return NotificationDeliveryDecision.Suppressed(NotificationSuppressionReason.ActiveNotificationReplyStream)
        }

        if (candidate.phase == NotificationCandidatePhase.Partial || !candidate.isFinal) {
            return NotificationDeliveryDecision.Deferred(NotificationDeferralReason.AwaitingFinalPreview)
        }

        if (syncStateStore.getLastNotifiedMessageId(candidate.conversationId) == notificationId) {
            return NotificationDeliveryDecision.Suppressed(NotificationSuppressionReason.DuplicateNotification)
        }

        publisher.publish(candidate.toChannelNotification(notificationId))
        syncStateStore.setLastNotifiedMessageId(candidate.conversationId, notificationId)
        return NotificationDeliveryDecision.Published(notificationId)
    }

    private fun NotificationDeliveryCandidate.toChannelNotification(notificationId: String): ChannelNotification =
        ChannelNotification(
            agentId = agentId,
            agentName = agentName,
            conversationId = conversationId,
            conversationSummary = conversationSummary,
            messageId = notificationId,
            messagePreview = previewText,
        )
}

data class NotificationDeliveryCandidate(
    val conversationId: String,
    val agentId: String,
    val agentName: String,
    val conversationSummary: String?,
    val messageId: String?,
    val runId: String?,
    val source: NotificationCandidateSource,
    val phase: NotificationCandidatePhase,
    val previewText: String,
    val isFinal: Boolean,
) {
    val notificationIdentity: String?
        get() = messageId?.takeIf { it.isNotBlank() }
            ?: runId?.takeIf { it.isNotBlank() }
}

enum class NotificationCandidateSource {
    TimelineIngestion,
    WebsocketClientMode,
    NotificationReplyStream,
    HeartbeatFallback,
}

enum class NotificationCandidatePhase {
    Partial,
    Final,
    Settled,
}

sealed interface NotificationDeliveryDecision {
    data class Published(val notificationId: String) : NotificationDeliveryDecision
    data class Deferred(val reason: NotificationDeferralReason) : NotificationDeliveryDecision
    data class Suppressed(val reason: NotificationSuppressionReason) : NotificationDeliveryDecision
}

enum class NotificationDeferralReason {
    AwaitingFinalPreview,
}

enum class NotificationSuppressionReason {
    MissingIdentity,
    BlankPreview,
    ForegroundConversation,
    ActiveNotificationReplyStream,
    DuplicateNotification,
}
