package com.letta.mobile.bot.channel

import kotlinx.coroutines.flow.StateFlow

interface NotificationReplyStreamTracker {
    val activeReplyStreams: StateFlow<Set<String>>
}
