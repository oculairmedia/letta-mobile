package com.letta.mobile.data.timeline

/**
 * Listener invoked when the resume-stream subscriber ingests a server event
 * that represents an inbound assistant/tool message (i.e. something the user
 * probably wants to see a notification for). Implemented in the :app module
 * so :core stays free of Android notification dependencies.
 *
 * @see com.letta.mobile.data.timeline.TimelineRepository for wiring.
 */
interface IngestedMessageListener {
    /**
     * Called on the TimelineSyncLoop's coroutine context (Dispatchers.IO)
     * after the event has been appended to state and the write lock has been
     * released. Implementations may do slow lookup/network work; exceptions
     * are swallowed and logged.
     */
    suspend fun onMessageIngested(
        conversationId: String,
        serverId: String,
        messageType: String?,
        contentPreview: String?,
    )
}
