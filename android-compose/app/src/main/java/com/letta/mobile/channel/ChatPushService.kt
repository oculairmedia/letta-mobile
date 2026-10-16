package com.letta.mobile.channel

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
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
import ca.oculair.meridian.R
import com.letta.mobile.util.Telemetry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the app process alive while no Activity is foregrounded, so the session graph's channel
 * and the approval watcher keep running when the screen turns off.
 *
 * letta-mobile-by4vm.15: the service no longer warms legacy TimelineRepository loops or listens to
 * their ingest. The canonical ledger owns every Android timeline and pages on open; Iroh never fed
 * those loops a live stream (its HTTP stream route is gated), so their ingest listener was idle.
 * Notifications reach [NotificationDeliveryCoordinator] from its other sources (heartbeat, reply stream).
 *
 * See letta-mobile-mge5 for the architectural epic.
 */
@AndroidEntryPoint
class ChatPushService : LifecycleService() {

    /**
     * Single field inject required by `@AndroidEntryPoint`. Collaborators live
     * on [ChatPushServiceDependencies] via constructor injection.
     */
    @Inject lateinit var deps: ChatPushServiceDependencies

    // LifecycleService owns this scope and cancels it at onDestroy, so the service no longer
    // holds a root scope whose cancellation depends on remembering to do it by hand.
    private val scope get() = lifecycleScope
    private val channelNotificationPublisher get() = deps.channelNotificationPublisher

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        // letta-mobile-50p2: foreground promotion can fail (e.g. FGS quota
        // exhausted on Android 14+ for older service types, or transient
        // BG-restriction states). Swallow the failure — the service still
        // runs in the background until Android kills it, which is far
        // better than crashing the entire app process at launch.
        channelNotificationPublisher.ensureChannel()
        val foregrounded = ensureForegroundNotification()
        if (!foregrounded) {
            Log.w(TAG, "Foreground promotion failed; running as background service")
        }
        scope.launch { deps.approvalAttention.watch() }
        Telemetry.event(
            "ChatPushService", "created",
            "foregrounded" to foregrounded.toString(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // LifecycleService dispatches its lifecycle events from these super calls.
        super.onStartCommand(intent, flags, startId)
        // letta-mobile-jmzq.5: track service restarts (Android may restart after
        // being killed, onStartCommand fires without onCreate in that case).
        Telemetry.event(
            "ChatPushService", "startCommand",
            "startId" to startId,
            "intentAction" to (intent?.action ?: "<null>"),
        )
        channelNotificationPublisher.ensureChannel()
        ensureForegroundNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        Telemetry.event("ChatPushService", "destroyed")
        super.onDestroy()
    }

    /**
     * Promote the service to the foreground.
     *
     * Returns `true` on success, `false` if Android refused (e.g.
     * ForegroundServiceStartNotAllowedException — quota exhausted, BG
     * restricted, missing permission). Caller decides how to degrade.
     *
     * letta-mobile-50p2: previously used FOREGROUND_SERVICE_TYPE_DATA_SYNC,
     * which has a 6-hour rolling daily quota on Android 14+. Once the
     * quota was hit, every subsequent startForeground() threw and crashed
     * the process at launch in an unrecoverable loop. Switched to
     * REMOTE_MESSAGING (the correct semantic for a chat push channel)
     * which is quota-exempt, and wrapped the call in try/catch as a
     * defense-in-depth so future quota / restriction surprises degrade
     * gracefully instead of taking the app down.
     */
    private fun ensureForegroundNotification(): Boolean {
        createServiceChannelIfNeeded()

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.push_service_notification_title))
            .setContentText(getString(R.string.push_service_notification_text))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val foregroundServiceType =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                    } else {
                        0
                    }
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    notification,
                    foregroundServiceType,
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException (Android 12+) is the
            // common case, but we catch broadly because IllegalStateException
            // and SecurityException can also surface here on niche OEM ROMs.
            Log.e(TAG, "startForeground failed", t)
            Telemetry.error("ChatPushService", "startForeground.failed", t)
            false
        }
    }

    private fun createServiceChannelIfNeeded() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(SERVICE_CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            getString(R.string.push_service_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.push_service_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ChatPushService"
        private const val SERVICE_CHANNEL_ID = "letta-chat-push-service"
        private const val FOREGROUND_NOTIFICATION_ID = 7531

        fun start(context: Context, scheduleRecoveryAlarm: Boolean = true): Boolean {
            // On Android 13+, POST_NOTIFICATIONS is runtime-granted. We still
            // start the service — the foreground service requires a
            // notification, but user can dismiss/disable the channel.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                // Defer — we'll be restarted after the user grants permission
                // (see MainActivity). Without POST_NOTIFICATIONS the service
                // can still run but the notification is suppressed, which
                // breaks the foreground contract on 13+.
                Log.w(TAG, "POST_NOTIFICATIONS not granted; deferring ChatPushService start")
                return false
            }

            if (scheduleRecoveryAlarm) {
                ChatPushAlarmScheduler.schedule(context)
            }

            return try {
                val intent = Intent(context, ChatPushService::class.java)
                ContextCompat.startForegroundService(context, intent)
                Telemetry.event("ChatPushService", "started")
                true
            } catch (t: Throwable) {
                // Alarm-based recovery may run while the app is backgrounded on
                // modern Android versions. Foreground-service background-start
                // restrictions can reject that launch; treat it as a telemetry
                // signal and let the next inexact alarm try again later.
                Log.w(TAG, "ChatPushService start rejected", t)
                Telemetry.error("ChatPushService", "startFailed", t)
                false
            }
        }
    }
}
