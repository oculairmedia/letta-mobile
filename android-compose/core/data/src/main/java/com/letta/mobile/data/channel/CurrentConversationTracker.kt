package com.letta.mobile.data.channel

import kotlinx.atomicfu.atomic
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which conversation the user is currently looking at, if any. The
 * [ChatPushService]'s [IngestedMessageListener] reads this to suppress system
 * notifications for messages that would arrive in the already-visible chat.
 *
 * Wired by AdminChatViewModel on observe/dispose. Read-only elsewhere.
 *
 * letta-mobile-x1xnl: also tracks an OWNER TOKEN. During a navigation overlap
 * two AdminChatViewModel instances (and thus two ChatSendCoordinators) can be
 * alive for the same conversation at once — the superseded one is a GHOST whose
 * stale UI writes render as a stranded duplicate assistant row. Each active
 * owner claims a unique token via [claimOwner]; the tracker keeps only the
 * latest. A coordinator/VM checks [isCurrentOwner] before writing UI, so a
 * superseded owner goes inert immediately (no wait for onCleared).
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

    private val ownerSeq = atomic(0L)

    @Volatile
    private var _currentOwnerToken: Long = 0L

    /** The latest owner token that has claimed the active chat surface. */
    val currentOwnerToken: Long get() = _currentOwnerToken

    /**
     * Mint a unique owner token for a newly-active chat owner and record it as
     * the current one. The caller keeps the returned token and passes it to
     * [isCurrentOwner] to decide whether it is still the active surface.
     */
    fun claimOwner(): Long {
        val token = ownerSeq.incrementAndGet()
        _currentOwnerToken = token
        return token
    }

    /** True when [token] is still the latest claimed owner (not superseded). */
    fun isCurrentOwner(token: Long): Boolean = _currentOwnerToken == token
}
