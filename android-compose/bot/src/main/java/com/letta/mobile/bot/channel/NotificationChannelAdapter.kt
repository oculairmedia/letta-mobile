package com.letta.mobile.bot.channel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification channel adapter — delivers bot responses as Android notifications.
 *
 * This adapter publishes agent responses as system notifications, allowing
 * background bot activity to surface to the user. It also supports
 * inline reply actions for quick responses without opening the app.
 */
@Singleton
class NotificationChannelAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
) : ChannelAdapter {

    override val channelId: String = CHANNEL_ID
    override val displayName: String = "Notifications"
    private var _active: Boolean = false
    override val isActive: Boolean get() = _active

    private val _incoming = MutableSharedFlow<ChannelMessage>(extraBufferCapacity = 64)
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun incomingMessages(): Flow<ChannelMessage> = _incoming.asSharedFlow()

    /**
     * Called when the user replies via notification inline reply.
     */
    suspend fun handleNotificationReply(message: ChannelMessage) {
        _incoming.emit(message)
    }

    override suspend fun deliver(response: ChannelDelivery): DeliveryResult {
        return try {
            ensureNotificationChannel()
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Letta Bot")
                .setContentText(response.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(response.text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            val notificationId = response.chatId.hashCode()
            notificationManager.notify(notificationId, notification)
            DeliveryResult.Success(notificationId.toString())
        } catch (e: Exception) {
            DeliveryResult.Failed("Failed to publish notification", e)
        }
    }

    override suspend fun start() {
        ensureNotificationChannel()
        _active = true
    }

    override suspend fun stop() {
        _active = false
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Bot Messages",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Messages from Letta bot agents"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "notification"
        private const val NOTIFICATION_CHANNEL_ID = "letta_bot_messages"
    }
}
