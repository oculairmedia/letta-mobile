package com.letta.mobile.channel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.letta.mobile.util.Telemetry

/**
 * One-shot recovery alarm receiver for persistent chat streams.
 *
 * The alarm is a safety net only: when it fires, reschedule the next check and
 * make a best-effort attempt to start [ChatPushService]. If Android refuses the
 * background foreground-service start, the failure is logged and the next
 * inexact alarm remains scheduled.
 */
class ChatPushAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ChatPushAlarmScheduler.ACTION_RECOVER_CHAT_PUSH) return

        Telemetry.event("ChatPushAlarm", "fired")

        // Keep the recovery chain alive even if this start attempt is rejected.
        ChatPushAlarmScheduler.schedule(context)

        val started = try {
            ChatPushService.start(context, scheduleRecoveryAlarm = false)
        } catch (t: Throwable) {
            Log.w(TAG, "Chat push recovery start threw", t)
            Telemetry.error("ChatPushAlarm", "startFailed", t)
            false
        }

        if (!started) {
            Telemetry.event("ChatPushAlarm", "startFailed", "reason" to "startReturnedFalse")
        }
    }

    companion object {
        private const val TAG = "ChatPushAlarmReceiver"
    }
}
