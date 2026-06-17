package com.letta.mobile.runtime.notifications

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Captures posted notifications into the shared [NotificationStore] so the
 * `notification.poll` tool can return recent items.
 *
 * OPT-IN: this service is inert until the user grants "Notification access" in
 * Android Settings. The tool reports SettingsRequired until then.
 */
@AndroidEntryPoint
class LettaNotificationListenerService : NotificationListenerService() {

    @Inject
    lateinit var store: NotificationStore

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val extras = notification.notification?.extras
        val title = extras?.getCharSequence("android.title")?.toString()
        val text = extras?.getCharSequence("android.text")?.toString()
        store.record(
            CapturedNotification(
                packageName = notification.packageName ?: "",
                title = title,
                text = text,
                postTimeMillis = notification.postTime,
            ),
        )
    }

    companion object {
        /**
         * True when this app currently holds notification-listener access.
         * Read from the system's enabled-listeners setting.
         */
        fun isNotificationAccessGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            val expected = ComponentName(context, LettaNotificationListenerService::class.java)
            return flat.split(":").any { entry ->
                val cn = ComponentName.unflattenFromString(entry)
                cn != null && cn == expected
            }
        }

        /** Settings action the user must visit to grant access. */
        const val NOTIFICATION_ACCESS_SETTINGS_ACTION =
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
    }
}
