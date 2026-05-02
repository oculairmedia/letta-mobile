package com.letta.mobile.channel

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.letta.mobile.util.Telemetry

/**
 * Recovery-only alarm for the chat push foreground service.
 *
 * This is intentionally not an instant-delivery mechanism. Android/OEMs can
 * still defer inexact alarms in Doze, but keeping one scheduled gives the app a
 * conservative chance to revive [ChatPushService] if START_STICKY is not enough
 * after memory pressure, OEM cleanup, or a killed process.
 */
object ChatPushAlarmScheduler {
    private const val TAG = "ChatPushAlarmScheduler"
    private const val REQUEST_CODE = 7532
    private const val INTERVAL_MS = 15 * 60 * 1000L

    const val ACTION_RECOVER_CHAT_PUSH = "com.letta.mobile.channel.RECOVER_CHAT_PUSH"

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Telemetry.event("ChatPushAlarm", "scheduleSkipped", "reason" to "missingAlarmManager")
            return
        }

        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
        val pendingIntent = recoveryPendingIntent(appContext, PendingIntent.FLAG_UPDATE_CURRENT)
            ?: run {
                Telemetry.event("ChatPushAlarm", "scheduleSkipped", "reason" to "missingPendingIntent")
                return
            }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            }
            Telemetry.event(
                "ChatPushAlarm", "scheduled",
                "intervalMinutes" to (INTERVAL_MS / 60_000L),
                "triggerElapsedMs" to triggerAt,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to schedule chat push recovery alarm", t)
            Telemetry.error("ChatPushAlarm", "scheduleFailed", t)
        }
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val pendingIntent = recoveryPendingIntent(appContext, PendingIntent.FLAG_NO_CREATE)

        if (pendingIntent != null) {
            try {
                alarmManager?.cancel(pendingIntent)
                pendingIntent.cancel()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to cancel chat push recovery alarm", t)
                Telemetry.error("ChatPushAlarm", "cancelFailed", t)
                return
            }
        }

        Telemetry.event("ChatPushAlarm", "cancelled")
    }

    private fun recoveryPendingIntent(
        context: Context,
        mutabilityFlag: Int,
    ): PendingIntent? {
        val flags = mutabilityFlag or PendingIntent.FLAG_IMMUTABLE
        val intent = Intent(context, ChatPushAlarmReceiver::class.java).apply {
            action = ACTION_RECOVER_CHAT_PUSH
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
