package com.letta.mobile.channel

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ca.oculair.meridian.R
import com.letta.mobile.NotificationNavigationTarget
import com.letta.mobile.data.notify.AttentionCopy
import com.letta.mobile.data.notify.AttentionEvent
import com.letta.mobile.util.Telemetry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * letta-mobile-bzvro.27 (F27): posts "an agent needs your go-ahead" on its own high-importance
 * channel, so the person can let approvals through while keeping message updates quiet. The body
 * and the Open action both deep-link to the conversation; the decision is made in the app.
 */
@Singleton
class ApprovalNotificationPublisher @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun ensureChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.approval_notifications_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.approval_notifications_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    fun publish(event: AttentionEvent.ApprovalNeeded, agentName: String?): Boolean {
        ensureChannel()
        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return blocked(event, "POST_NOTIFICATIONS_DENIED")
        }
        if (!manager.areNotificationsEnabled()) return blocked(event, "NOTIFICATIONS_DISABLED")
        val open = PendingIntent.getActivity(
            context,
            event.conversationId.hashCode(),
            NotificationNavigationTarget(event.agentId, event.conversationId, agentName).createIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = AttentionCopy.approvalBody(event)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(AttentionCopy.approvalTitle(agentName))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .addAction(0, AttentionCopy.OPEN_ACTION, open)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        return runCatching { manager.notify(TAG_PREFIX + event.conversationId, NOTIFICATION_ID, notification) }
            .onFailure { Telemetry.error("ApprovalNotificationPublisher", "publishFailed", it, "conversationId" to event.conversationId) }
            .isSuccess
    }

    private fun blocked(event: AttentionEvent.ApprovalNeeded, reason: String): Boolean {
        Telemetry.event(
            "ApprovalNotificationPublisher", "publishBlocked",
            "reason" to reason,
            "conversationId" to event.conversationId,
        )
        return false
    }

    private companion object {
        const val CHANNEL_ID = "letta-approvals"

        /** One approval notification per conversation: a newer request replaces the older one. */
        const val TAG_PREFIX = "approval:"
        const val NOTIFICATION_ID = 7_642
    }
}
