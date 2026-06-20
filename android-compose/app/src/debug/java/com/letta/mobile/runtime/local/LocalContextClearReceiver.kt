package com.letta.mobile.runtime.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.letta.mobile.data.local.LettaDatabase

/**
 * Debug-only local context cleaner.
 *
 * ADB:
 *   adb shell am broadcast -n com.letta.mobile.dev/com.letta.mobile.runtime.local.LocalContextClearReceiver \
 *     --es agent local-agent-... \
 *     --es conversation local-conv-local-agent-...
 */
class LocalContextClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                clear(context.applicationContext, intent)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun clear(context: Context, intent: Intent) {
        val agentId = intent.getStringExtra(EXTRA_AGENT)?.trim()?.takeIf { it.isNotBlank() }
            ?: DEFAULT_NOSWITCH_AGENT
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION)?.trim()?.takeIf { it.isNotBlank() }
            ?: "local-conv-$agentId"
        val database = LettaDatabase.getDatabase(context)
        val db = database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            val deletedEvents = db.delete(
                "runtime_events",
                "agentId = ? OR conversationId = ?",
                arrayOf(agentId, conversationId),
            )
            val deletedPending = db.delete(
                "pending_local_messages",
                "conversationId = ?",
                arrayOf(conversationId),
            )
            val deletedCursors = db.delete(
                "conversation_cursors",
                "conv_id = ?",
                arrayOf(conversationId),
            )
            val deletedRefresh = db.delete(
                "conversation_refresh_state",
                "agentId = ?",
                arrayOf(agentId),
            )
            val deletedConversations = db.delete(
                "conversations",
                "id = ? OR agentId = ?",
                arrayOf(conversationId, agentId),
            )
            db.setTransactionSuccessful()
            Log.i(
                TAG,
                "CLEARED agent=$agentId conversation=$conversationId events=$deletedEvents pending=$deletedPending cursors=$deletedCursors refresh=$deletedRefresh conversations=$deletedConversations",
            )
        } catch (error: Throwable) {
            Log.e(TAG, "FAILED agent=$agentId conversation=$conversationId", error)
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        private const val TAG = "LOCAL_CONTEXT_CLEAR"
        private const val EXTRA_AGENT = "agent"
        private const val EXTRA_CONVERSATION = "conversation"
        private const val DEFAULT_NOSWITCH_AGENT = "local-agent-352028c9-aaf3-42cd-b92e-aad853f57fdb"
    }
}
