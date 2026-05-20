package com.letta.mobile.testutil

import com.letta.mobile.bot.channel.NotificationReplyStreamTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeNotificationReplyStreamTracker(
    initialActiveReplyStreams: Set<String> = emptySet(),
) : NotificationReplyStreamTracker {
    private val activeReplyStreamsState = MutableStateFlow(initialActiveReplyStreams)
    override val activeReplyStreams: StateFlow<Set<String>> = activeReplyStreamsState

    fun setActiveReplyStreams(conversationIds: Set<String>) {
        activeReplyStreamsState.value = conversationIds
    }
}
