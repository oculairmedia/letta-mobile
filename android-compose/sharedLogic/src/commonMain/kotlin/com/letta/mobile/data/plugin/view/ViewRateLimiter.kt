package com.letta.mobile.data.plugin.view

/**
 * At most [limit] messages in any [windowMs] (a sliding window), for one view instance: the bridge
 * owns one per view, so a busy page never slows another. Not thread-safe; the bridge admits
 * messages one at a time.
 */
class ViewRateLimiter(
    private val limit: Int = LcpView.MAX_MESSAGES_PER_SECOND,
    private val windowMs: Long = WINDOW_MS,
) {
    private val admitted = ArrayDeque<Long>()

    init {
        require(limit > 0) { "limit must be positive" }
        require(windowMs > 0) { "windowMs must be positive" }
    }

    /** Whether a message arriving at [nowMs] (a monotonic clock) is within the limit; an admitted one counts. */
    fun tryAcquire(nowMs: Long): Boolean {
        while (admitted.isNotEmpty() && nowMs - admitted.first() >= windowMs) admitted.removeFirst()
        if (admitted.size >= limit) return false
        admitted.addLast(nowMs)
        return true
    }

    companion object {
        const val WINDOW_MS: Long = 1_000
    }
}
