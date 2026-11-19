package com.letta.mobile.data.media

/**
 * Size limits every media renderer and platform player applies, so a part plays or is refused the
 * same way everywhere. Images keep their own ingress policy (the attachment package); these bound
 * what is buffered for playback.
 */
object MediaLimits {
    /** Most decoded bytes an inline (base64) part may have before a player refuses to buffer it. */
    const val MAX_INLINE_PLAYBACK_BYTES: Long = 25L * 1024 * 1024

    /** Most bytes a player that must buffer a whole remote file (no streaming decoder) downloads. */
    const val MAX_BUFFERED_REMOTE_BYTES: Long = 25L * 1024 * 1024

    /** How far one keyboard or accessibility seek step moves, forwards or back. */
    const val SEEK_STEP_MS: Long = 5_000L

    /** The decoded size of a base64 payload of [base64Length] characters, without decoding it. */
    fun decodedBytes(base64Length: Int): Long = base64Length.toLong() * 3 / 4

    /** Whether an inline payload of [base64Length] characters is small enough to play. */
    fun inlinePlayable(base64Length: Int): Boolean = decodedBytes(base64Length) <= MAX_INLINE_PLAYBACK_BYTES
}
