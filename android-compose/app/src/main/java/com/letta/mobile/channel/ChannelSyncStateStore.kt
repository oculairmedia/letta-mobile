package com.letta.mobile.channel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.core.content.edit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelSyncStateStore @Inject constructor(
    @ApplicationContext context: Context,
) : IChannelSyncStateStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getProcessedLastActivityAt(conversationId: String): String? {
        return prefs.getString(processedKey(conversationId), null)
    }

    override fun setProcessedLastActivityAt(conversationId: String, value: String) {
        prefs.edit { putString(processedKey(conversationId), value) }
    }

    override fun getLastNotifiedMessageId(conversationId: String): String? {
        return prefs.getString(notifiedKey(conversationId), null)
    }

    override fun setLastNotifiedMessageId(conversationId: String, messageId: String) {
        prefs.edit { putString(notifiedKey(conversationId), messageId) }
    }

    private fun processedKey(conversationId: String) = "processed::$conversationId"

    private fun notifiedKey(conversationId: String) = "notified::$conversationId"

    companion object {
        private const val PREFS_NAME = "channel_sync_state"
    }
}
