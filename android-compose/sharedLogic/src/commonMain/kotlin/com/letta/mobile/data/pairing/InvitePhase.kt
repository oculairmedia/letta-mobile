package com.letta.mobile.data.pairing

/**
 * Where the inviter's "Pair a device" card is:
 * Configuring (scope + TTL) -> Showing (QR + countdown) -> Expiring (last
 * [InvitePhases.EXPIRING_WINDOW_MS]) -> Expired, or -> Redeemed when a new
 * device shows up, or -> Cancelled when the inviter withdraws the code.
 *
 * There is no auto-refresh: an invite is one-time, so a fresh one is minted
 * only when the inviter asks ("New code").
 */
sealed interface InvitePhase {
    data object Configuring : InvitePhase

    data class Showing(val invite: MintedPairInvite, val remainingMs: Long) : InvitePhase

    data class Expiring(val invite: MintedPairInvite, val remainingMs: Long) : InvitePhase

    data class Expired(val invite: MintedPairInvite) : InvitePhase

    data class Redeemed(val device: PairedDevice) : InvitePhase

    data object Cancelled : InvitePhase
}

/** Derives the [InvitePhase] from session state and a clock, so it is a pure, testable function. */
object InvitePhases {
    /** The last stretch before expiry, shown as "Expiring" (status text, never colour alone). */
    const val EXPIRING_WINDOW_MS = 30_000L

    fun of(state: PairInviteUiState, nowMs: Long): InvitePhase {
        state.accepted?.let { return InvitePhase.Redeemed(it) }
        val invite = state.invite ?: return if (state.cancelled) InvitePhase.Cancelled else InvitePhase.Configuring
        val remaining = invite.expiresAtMs - nowMs
        return when {
            remaining <= 0 -> InvitePhase.Expired(invite)
            remaining <= EXPIRING_WINDOW_MS -> InvitePhase.Expiring(invite, remaining)
            else -> InvitePhase.Showing(invite, remaining)
        }
    }
}

/**
 * The invite countdown: "m:ss" on screen, and screen-reader
 * announcements throttled to two moments (60 s and 10 s left) instead of every tick.
 */
object InviteCountdown {
    private const val MS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L

    /** Announcement thresholds, largest first. */
    val ANNOUNCE_AT_SECONDS: List<Long> = listOf(60L, 10L)

    /** Whole seconds left, rounded up so "0:00" only shows once the code has actually expired. */
    fun secondsLeft(remainingMs: Long): Long =
        if (remainingMs <= 0) 0 else (remainingMs + MS_PER_SECOND - 1) / MS_PER_SECOND

    /** "4:32" for 272 s left; "0:00" once expired. */
    fun format(remainingMs: Long): String {
        val seconds = secondsLeft(remainingMs)
        val minutes = seconds / SECONDS_PER_MINUTE
        val rest = seconds % SECONDS_PER_MINUTE
        return "$minutes:${rest.toString().padStart(2, '0')}"
    }

    /**
     * The threshold (in seconds) crossed between two ticks, or null. A tick that
     * jumps over several thresholds reports only the smallest one crossed.
     */
    fun crossedThreshold(previousRemainingMs: Long, remainingMs: Long): Long? {
        val before = secondsLeft(previousRemainingMs)
        val now = secondsLeft(remainingMs)
        return ANNOUNCE_AT_SECONDS.lastOrNull { threshold -> before > threshold && now <= threshold && now > 0 }
    }
}
