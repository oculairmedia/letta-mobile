package com.letta.mobile.channel

import com.letta.mobile.bot.channel.NotificationReplyHandler
import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.channel.NotificationCandidatePhase
import com.letta.mobile.data.channel.NotificationCandidateSource
import com.letta.mobile.data.channel.NotificationDeferralReason
import com.letta.mobile.data.channel.NotificationDelivery
import com.letta.mobile.data.channel.NotificationDeliveryCandidate
import com.letta.mobile.data.channel.NotificationDeliveryDecision
import com.letta.mobile.data.channel.NotificationSuppressionReason
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
) : NotificationDelivery {
    override fun submit(candidate: NotificationDeliveryCandidate): NotificationDeliveryDecision {
        val notificationId = candidate.notificationIdentity
            ?: return candidate.suppressed(NotificationSuppressionReason.MissingIdentity)

        val previewText = NotificationContentSanitizer.sanitizePreview(candidate.previewText)

        if (previewText.fallbackApplied && (candidate.phase == NotificationCandidatePhase.Partial || !candidate.isFinal)) {
            return candidate.deferred(NotificationDeferralReason.AwaitingFinalPreview, notificationId)
        }

        if (currentConversationTracker.current == candidate.conversationId) {
            return candidate.suppressed(NotificationSuppressionReason.ForegroundConversation, notificationId)
        }

        if (
            candidate.source != NotificationCandidateSource.NotificationReplyStream &&
            candidate.conversationId in notificationReplyHandler.activeReplyStreams.value
        ) {
            return candidate.suppressed(NotificationSuppressionReason.ActiveNotificationReplyStream, notificationId)
        }

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
    ): NotificationDeliveryDecision.Deferred {
        Telemetry.event(
            "NotificationDelivery", "deferred",
            *telemetryAttrs(notificationId),
            "reason" to reason.name,
        )
        return NotificationDeliveryDecision.Deferred(reason)
    }

    private fun NotificationDeliveryCandidate.suppressed(
        reason: NotificationSuppressionReason,
        notificationId: String? = null,
    ): NotificationDeliveryDecision.Suppressed {
        Telemetry.event(
            "NotificationDelivery", "suppressed",
            *telemetryAttrs(notificationId),
            "reason" to reason.name,
        )
        return NotificationDeliveryDecision.Suppressed(reason)
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

