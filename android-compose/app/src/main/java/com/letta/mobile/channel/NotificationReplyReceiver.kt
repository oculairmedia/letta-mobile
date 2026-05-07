package com.letta.mobile.channel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class NotificationReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.w(TAG, "onReceive action=${intent.action}")
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        val agentId = intent.getStringExtra(EXTRA_AGENT_ID)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        if (conversationId == null || agentId == null) {
            Log.w(TAG, "missing required extras, aborting")
            return
        }

        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim()
        if (replyText.isNullOrEmpty()) return

        val pendingResult = goAsync()
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                ReplyEntryPoint::class.java,
            )
            entryPoint.replyHandler()
                .sendReply(agentId, conversationId, replyText)
                .invokeOnCompletion { pendingResult.finish() }
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (e: Exception) {
            Log.w(TAG, "failed to start reply work", e)
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_REPLY = "com.letta.mobile.ACTION_NOTIFICATION_REPLY"
        const val EXTRA_CONVERSATION_ID = "notification_conversation_id"
        const val EXTRA_AGENT_ID = "notification_agent_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val KEY_TEXT_REPLY = "key_text_reply"
        private const val TAG = "NotificationReply"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReplyEntryPoint {
    fun replyHandler(): NotificationReplyHandler
}
