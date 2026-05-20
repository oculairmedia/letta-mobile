package com.letta.mobile.testutil

import com.letta.mobile.channel.IChannelSyncStateStore

class FakeChannelSyncStateStore(
    initialProcessed: Map<String, String> = emptyMap(),
    initialNotified: Map<String, String> = emptyMap(),
) : IChannelSyncStateStore {
    val processed: MutableMap<String, String> = initialProcessed.toMutableMap()
    val notified: MutableMap<String, String> = initialNotified.toMutableMap()

    override fun getProcessedLastActivityAt(conversationId: String): String? = processed[conversationId]

    override fun setProcessedLastActivityAt(conversationId: String, value: String) {
        processed[conversationId] = value
    }

    override fun getLastNotifiedMessageId(conversationId: String): String? = notified[conversationId]

    override fun setLastNotifiedMessageId(conversationId: String, messageId: String) {
        notified[conversationId] = messageId
    }
}
