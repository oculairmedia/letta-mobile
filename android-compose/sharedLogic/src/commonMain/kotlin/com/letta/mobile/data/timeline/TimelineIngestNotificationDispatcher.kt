package com.letta.mobile.data.timeline

import com.letta.mobile.util.Telemetry

data class PendingIngestNotification(
    val serverId: String,
    val messageType: String,
    val contentPreview: String?,
)

/**
 * IO collaborator for post-ingest notification callbacks.
 *
 * Timeline mutation computes the lightweight [PendingIngestNotification] under
 * the write lock, then this dispatcher resolves and invokes app-layer listeners
 * after the lock has been released so notification lookup/network work cannot
 * block timeline rendering.
 */
class TimelineIngestNotificationDispatcher(
    private val conversationId: String,
    private val listener: IngestedMessageListener?,
    private val listenerProvider: (() -> IngestedMessageListener?)?,
) {
    suspend fun dispatch(notification: PendingIngestNotification?) {
        notification ?: return
        try {
            val resolvedListener = listenerProvider?.invoke() ?: listener
            Telemetry.event(
                "TimelineSync", "streamSubscriber.listenerDispatch",
                "conversationId" to conversationId,
                "serverId" to notification.serverId,
                "messageType" to notification.messageType,
                "hasListener" to (resolvedListener != null),
                "previewLength" to (notification.contentPreview?.length ?: 0),
            )
            resolvedListener?.onMessageIngested(
                conversationId = conversationId,
                serverId = notification.serverId,
                messageType = notification.messageType,
                contentPreview = notification.contentPreview,
            )
        } catch (t: Throwable) {
            Telemetry.error(
                "TimelineSync", "streamSubscriber.listenerThrew", t,
                "conversationId" to conversationId,
            )
        }
    }
}
