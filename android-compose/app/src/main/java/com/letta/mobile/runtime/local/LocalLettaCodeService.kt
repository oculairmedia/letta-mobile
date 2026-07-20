package com.letta.mobile.runtime.local

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.letta.mobile.MainActivity
import com.letta.mobile.R

class LocalLettaCodeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        return START_STICKY
    }

    private fun ensureForeground(): Boolean {
        createChannelIfNeeded()
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.lettacode_runtime_service_notification_title))
            .setContentText(getString(R.string.lettacode_runtime_service_notification_text))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Local LettaCode foreground promotion failed", t)
            false
        }
    }

    private fun createChannelIfNeeded() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.lettacode_runtime_service_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = getString(R.string.lettacode_runtime_service_channel_description)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val TAG = "LocalLettaCodeService"
        private const val CHANNEL_ID = "letta-code-runtime"
        private const val NOTIFICATION_ID = 9142

        /**
         * Whether the foreground-service notification will actually be visible in the
         * notification drawer.
         *
         * On Android 13+ (TIRAMISU) posting notifications requires the runtime
         * POST_NOTIFICATIONS permission. When it is denied the foreground service is
         * still allowed to run — the notification simply moves to the Task Manager
         * (active apps) surface instead of the drawer. This decision is intentionally
         * pure so it can be unit-tested without the Android framework: it controls
         * notification VISIBILITY/UX only and must never gate the runtime launch.
         */
        fun notificationsWillBeVisible(
            sdkInt: Int,
            postNotificationsGranted: Boolean,
        ): Boolean = sdkInt < Build.VERSION_CODES.TIRAMISU || postNotificationsGranted

        fun start(context: Context): Boolean {
            // Record notification visibility for the UX layer, but never block the
            // launch on it: Android allows foreground services to start without
            // POST_NOTIFICATIONS (the notification appears in Task Manager instead).
            val notificationsGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!notificationsWillBeVisible(Build.VERSION.SDK_INT, notificationsGranted)) {
                Log.i(
                    TAG,
                    "POST_NOTIFICATIONS not granted; starting LocalLettaCodeService anyway " +
                        "(its foreground notification will surface in Task Manager, not the drawer)",
                )
            }
            return try {
                ContextCompat.startForegroundService(context, Intent(context, LocalLettaCodeService::class.java))
                true
            } catch (t: Throwable) {
                Log.w(TAG, "Local LettaCode service start failed", t)
                false
            }
        }
    }
}
