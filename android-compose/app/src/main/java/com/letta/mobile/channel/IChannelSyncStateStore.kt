package com.letta.mobile.channel

interface IChannelSyncStateStore {
    fun getProcessedLastActivityAt(conversationId: String): String?
    fun setProcessedLastActivityAt(conversationId: String, value: String)
    fun getLastNotifiedMessageId(conversationId: String): String?
    fun setLastNotifiedMessageId(conversationId: String, messageId: String)
}
