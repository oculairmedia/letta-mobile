package com.letta.mobile.data.channel

/**
 * Contract for submitting notification delivery candidates. The implementation
 * in :app owns Android notification policy and infrastructure; this interface
 * lets :bot's [NotificationReplyHandler] submit candidates without depending on
 * :app directly.
 */
interface NotificationDelivery {
    fun submit(candidate: NotificationDeliveryCandidate): NotificationDeliveryDecision
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
    PublishRejected,
}
