package com.letta.mobile.channel

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which conversation the user is currently looking at, if any. The
 * [ChatPushService]'s [IngestedMessageListener] reads this to suppress system
 * notifications for messages that would arrive in the already-visible chat.
 *
 * Wired by [com.letta.mobile.ui.screens.chat.AdminChatViewModel] on
 * observe/dispose. Read-only elsewhere.
 *
 * See letta-mobile-mge5.
 */
@Singleton
class CurrentConversationTracker @Inject constructor() {
    @Volatile
    private var _current: String? = null

    val current: String? get() = _current

    fun setCurrent(conversationId: String?) {
        _current = conversationId
    }
}
