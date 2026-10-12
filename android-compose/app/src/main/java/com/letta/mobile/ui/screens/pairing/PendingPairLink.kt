package com.letta.mobile.ui.screens.pairing

import com.letta.mobile.data.pairing.PairLink
import java.util.concurrent.atomic.AtomicReference

/**
 * A pairing link opened from outside the app (camera scan / deep link), handed
 * to the scan screen exactly once (letta-mobile-dpv7m). Kept only in process
 * memory: it carries a one-time secret, so it never goes into navigation
 * arguments (which are persisted in saved state) or logs.
 */
object PendingPairLink {
    private val pending = AtomicReference<String?>(null)

    /** Accepts [raw] only when it is a pairing link; returns whether it was taken. */
    fun offer(raw: String?): Boolean {
        val value = raw?.trim()?.takeIf { PairLink.isPairLink(it) } ?: return false
        pending.set(value)
        return true
    }

    fun take(): String? = pending.getAndSet(null)
}
