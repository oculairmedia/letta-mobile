package com.letta.mobile.channel

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.channel.NotificationCandidatePhase
import com.letta.mobile.data.channel.NotificationCandidateSource
import com.letta.mobile.data.channel.NotificationDeferralReason
import com.letta.mobile.data.channel.NotificationDelivery
import com.letta.mobile.data.channel.NotificationDeliveryCandidate
import com.letta.mobile.data.channel.NotificationDeliveryDecision
import com.letta.mobile.data.channel.NotificationSuppressionReason
import com.letta.mobile.data.notify.AttentionPrefsRepository
import com.letta.mobile.util.Telemetry
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
 * - [NotificationCandidateSource.NotificationReplyStream] is a response caused
 *   by a notification RemoteInput reply.
 * - [NotificationCandidateSource.HeartbeatFallback] is the periodic polling
 *   fallback. It shares the same duplicate state as realtime delivery so it can
 *   fill gaps without republishing messages already delivered by another path.
 */
@Singleton
class NotificationDeliveryCoordinator @Inject constructor(
    private val currentConversationTracker: CurrentConversationTracker,
    private val syncStateStore: IChannelSyncStateStore,
    private val publisher: IChannelNotificationPublisher,
    private val attentionPrefs: AttentionPrefsRepository,
) : NotificationDelivery {
    override fun submit(candidate: NotificationDeliveryCandidate): NotificationDeliveryDecision {
        val notificationId = candidate.notificationIdentity
            ?: return candidate.suppressed(NotificationSuppressionReason.MissingIdentity)

        val previewText = NotificationContentSanitizer.sanitizePreview(candidate.previewText)

        if (previewText.fallbackApplied && (candidate.phase == NotificationCandidatePhase.Partial || !candidate.isFinal)) {
            return candidate.deferred(NotificationDeferralReason.AwaitingFinalPreview, notificationId)
        }

        audienceSuppression(candidate)?.let { return candidate.suppressed(it, notificationId) }

        if (candidate.phase == NotificationCandidatePhase.Partial || !candidate.isFinal) {
            return candidate.deferred(NotificationDeferralReason.AwaitingFinalPreview, notificationId)
        }

        if (syncStateStore.getLastNotifiedMessageId(candidate.conversationId) == notificationId) {
            return candidate.suppressed(NotificationSuppressionReason.DuplicateNotification, notificationId)
        }

        val published = publisher.publish(candidate.toChannelNotification(notificationId, previewText.text))
        if (!published) {
            return candidate.suppressed(NotificationSuppressionReason.PublishRejected, notificationId)
        }
        syncStateStore.setLastNotifiedMessageId(candidate.conversationId, notificationId)
        return candidate.published(notificationId)
    }

    /**
     * Who would see this: nobody wants it from an agent they muted (letta-mobile-bzvro.27), and the
     * person already sees a conversation that is on screen.
     */
    private fun audienceSuppression(candidate: NotificationDeliveryCandidate): NotificationSuppressionReason? = when {
        attentionPrefs.prefs.value.isMuted(candidate.agentId) -> NotificationSuppressionReason.MutedAgent
        currentConversationTracker.current == candidate.conversationId -> NotificationSuppressionReason.ForegroundConversation
        else -> null
    }

    private fun NotificationDeliveryCandidate.published(notificationId: String): NotificationDeliveryDecision.Published {
        Telemetry.event(
            "NotificationDelivery", "published",
            *telemetryAttrs(notificationId),
        )
        return NotificationDeliveryDecision.Published(notificationId)
    }

    private fun NotificationDeliveryCandidate.deferred(
        reason: NotificationDeferralReason,
        notificationId: String?,
    ): NotificationDeliveryDecision.Deferred =
        NotificationDeliveryDecision.Deferred(reason).also { reportWithheld(it, reason, notificationId) }

    private fun NotificationDeliveryCandidate.suppressed(
        reason: NotificationSuppressionReason,
        notificationId: String? = null,
    ): NotificationDeliveryDecision.Suppressed =
        NotificationDeliveryDecision.Suppressed(reason).also { reportWithheld(it, reason, notificationId) }

    /** Telemetry for a candidate that was not published: the outcome and its reason. */
    private fun NotificationDeliveryCandidate.reportWithheld(
        decision: NotificationDeliveryDecision,
        reason: Enum<*>,
        notificationId: String?,
    ) {
        val outcome = if (decision is NotificationDeliveryDecision.Deferred) "deferred" else "suppressed"
        Telemetry.event(
            "NotificationDelivery", outcome,
            *telemetryAttrs(notificationId),
            "reason" to reason.name,
        )
    }

    private fun NotificationDeliveryCandidate.telemetryAttrs(notificationId: String?): Array<Pair<String, Any?>> {
        val sanitizedPreview = NotificationContentSanitizer.sanitizePreview(previewText)
        return arrayOf(
            "conversationId" to conversationId,
            "notificationId" to (notificationId ?: "<none>"),
            "messageIdPresent" to (!messageId.isNullOrBlank()),
            "runIdPresent" to (!runId.isNullOrBlank()),
            "source" to source.name,
            "phase" to phase.name,
            "isFinal" to isFinal,
            "rawPreviewLength" to previewText.length,
            "normalizedPreviewLength" to sanitizedPreview.normalizedLength,
            "previewFallbackReason" to sanitizedPreview.fallbackReason.name,
        )
    }

    private fun NotificationDeliveryCandidate.toChannelNotification(
        notificationId: String,
        previewText: String,
    ): ChannelNotification =
        ChannelNotification(
            agentId = agentId,
            agentName = agentName,
            conversationId = conversationId,
            conversationSummary = conversationSummary,
            messageId = notificationId,
            messagePreview = previewText,
        )
}
