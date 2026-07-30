package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.AppMessage

/**
 * letta-mobile-f0ixs: one page of backfilled history plus an explicit "is there more" answer.
 *
 * Exists because page SIZE is not a reliable end-of-history signal. MessageListPageGuard trims
 * an oversized window to fit its byte budget, so a full-history page can arrive shorter than the
 * requested limit; a caller reading that as "reached the beginning" truncates scroll-back
 * silently, with no error and nothing in the logs.
 *
 * @property hasMore true/false when the transport stated it, null when it did not. Null means
 *   "unknown, decide for yourself" — callers keep their page-size heuristic — rather than "no
 *   more history", so transports without a signal (the HTTP path) behave exactly as before.
 */
data class OlderMessagesPage(
    val messages: List<AppMessage>,
    val hasMore: Boolean?,
)
