package com.letta.mobile.channel

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.letta.mobile.NotificationNavigationTarget
import com.letta.mobile.R
import com.letta.mobile.util.Telemetry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class ChannelNotification(
    val agentId: String,
    val agentName: String,
    val conversationId: String,
    val conversationSummary: String?,
    val messageId: String,
    val messagePreview: String,
)

internal data class ChannelNotificationContent(
    val title: String,
    val messageText: String,
    val titleText: SanitizedNotificationText,
    val preview: SanitizedNotificationText,
)

@Singleton
class ChannelNotificationPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
) : IChannelNotificationPublisher {
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_notifications_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_notifications_description)
        }
        manager.createNotificationChannel(channel)
    }

    override fun publish(notification: ChannelNotification): Boolean {
        ensureChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; skipping notification for ${notification.conversationId}")
            Telemetry.event(
                "ChannelNotificationPublisher", "publishBlocked",
                "reason" to "POST_NOTIFICATIONS_DENIED",
                "conversationId" to notification.conversationId,
            )
            return false
        }

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled for app; skipping notification for ${notification.conversationId}")
            Telemetry.event(
                "ChannelNotificationPublisher", "publishBlocked",
                "reason" to "NOTIFICATIONS_DISABLED",
                "conversationId" to notification.conversationId,
            )
            return false
        }

        val target = NotificationNavigationTarget(
            agentId = notification.agentId,
            conversationId = notification.conversationId,
            agentName = notification.agentName.takeIf { it.isNotBlank() },
        )
        val pendingIntent = PendingIntent.getActivity(
            context,
            notification.conversationId.hashCode(),
            target.createIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val content = resolveContent(notification)
        val notificationId = notificationIdForConversation(notification.conversationId)
        val notificationTag = notificationTagForConversation(notification.conversationId)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(content.title)
            .setContentText(content.messageText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.messageText))
            .setSubText(notification.conversationSummary)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        try {
            manager.notify(notificationTag, notificationId, builder.build())
        } catch (t: Throwable) {
            Telemetry.error(
                "ChannelNotificationPublisher", "publishFailed", t,
                "conversationId" to notification.conversationId,
                "messageId" to notification.messageId,
                "notificationTag" to notificationTag,
                "notificationId" to notificationId,
                "rawPreviewLength" to content.preview.rawLength,
                "normalizedPreviewLength" to content.preview.normalizedLength,
                "finalPreviewLength" to content.messageText.length,
                "previewFallbackReason" to content.preview.fallbackReason.name,
            )
            return false
        }
        Telemetry.event(
            "ChannelNotificationPublisher", "published",
            "conversationId" to notification.conversationId,
            "messageId" to notification.messageId,
            "notificationTag" to notificationTag,
            "notificationId" to notificationId,
            "rawPreviewLength" to content.preview.rawLength,
            "normalizedPreviewLength" to content.preview.normalizedLength,
            "finalPreviewLength" to content.messageText.length,
            "previewFallbackReason" to content.preview.fallbackReason.name,
            "titleFallbackReason" to content.titleText.fallbackReason.name,
        )
        return true
    }

    internal fun resolveContent(notification: ChannelNotification): ChannelNotificationContent {
        val titleText = NotificationContentSanitizer.sanitizeTitle(
            raw = notification.agentName,
            fallback = context.getString(R.string.channel_notifications_fallback_title),
        )
        val previewText = NotificationContentSanitizer.sanitizePreview(
            raw = notification.messagePreview,
            fallback = context.getString(R.string.channel_notifications_fallback_text),
        )
        return ChannelNotificationContent(
            title = titleText.text,
            messageText = previewText.text,
            titleText = titleText,
            preview = previewText,
        )
    }

    companion object {
        private const val TAG = "ChannelNotificationPublisher"
        private const val CHANNEL_ID = "letta-agent-updates"
        private const val NOTIFICATION_TAG_PREFIX = "conversation:"

        fun notificationIdForConversation(conversationId: String): Int = conversationId.hashCode()

        fun notificationTagForConversation(conversationId: String): String =
            "$NOTIFICATION_TAG_PREFIX$conversationId"
    }
}
