package com.letta.mobile.data.controller.node.iroh.feed

/**
 * What the journal keeps. A conversation is bounded by entry count and bytes (finished turns are dropped oldest
 * first, then the oldest entries of the running one), the whole directory by [diskCeilingBytes] (least recently used
 * conversations are deleted first, never one with a running turn), and memory by [maxLoadedLogs].
 */
data class FeedRetention(
    val maxEntriesPerConversation: Int = DEFAULT_MAX_ENTRIES,
    val maxBytesPerConversation: Long = DEFAULT_MAX_BYTES,
    val diskCeilingBytes: Long = DEFAULT_DISK_CEILING_BYTES,
    val maxLoadedLogs: Int = DEFAULT_MAX_LOADED_LOGS,
    /** Consecutive snapshots of one text message that replace each other in memory before one is written. */
    val textFlushEvery: Int = DEFAULT_TEXT_FLUSH_EVERY,
) {
    init {
        require(maxEntriesPerConversation >= MIN_ENTRIES) { "maxEntriesPerConversation must be at least $MIN_ENTRIES" }
        require(maxBytesPerConversation > 0 && diskCeilingBytes > 0 && maxLoadedLogs > 0 && textFlushEvery > 0) {
            "retention limits must be positive"
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 1_000
        const val DEFAULT_MAX_BYTES: Long = 4L * 1024 * 1024
        const val DEFAULT_DISK_CEILING_BYTES: Long = 64L * 1024 * 1024
        const val DEFAULT_MAX_LOADED_LOGS: Int = 32
        const val DEFAULT_TEXT_FLUSH_EVERY: Int = 16
        private const val MIN_ENTRIES = 4
    }
}
