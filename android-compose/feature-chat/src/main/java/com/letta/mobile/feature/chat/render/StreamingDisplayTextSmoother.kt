package com.letta.mobile.feature.chat.render

/**
 * Meters out bursty streaming-text arrivals with a reveal velocity that follows the upstream
 * token cadence instead of using a fixed speed or an accelerated end-of-stream drain.
 *
 * Pure Kotlin — no Compose dependency — so it is unit-testable.
 *
 * Usage:
 *  1. Call [updateTarget] every time the ViewModel pushes new accumulated text (or flips
 *     `isStreaming` off).
 *  2. Call [step] once per frame (driven by the composable wrapper's `LaunchedEffect`) to get the
 *     substring to render.
 *
 * Design rationale:
 *  - Letta/lettabot can emit uneven chunks: small token deltas, then larger coalesced bursts.
 *  - Rendering chunks immediately jumps; a fixed reveal rate eventually lags and then appears to
 *    dump buffered text.
 *  - This smoother estimates incoming characters/ms from target growth, clamps it to a readable
 *    range, and tweens the visible reveal velocity toward that estimate. The result tracks token
 *    output speed without abrupt speed changes.
 *  - When the stream ends, it keeps the current/tweened velocity instead of draining faster, so the
 *    remaining buffered tail does not race in and flicker.
 */
internal class StreamingDisplayTextSmoother(
    private val initialCharsPerMs: Float = DEFAULT_CHARS_PER_MS,
) {
    private var target: String = ""
    private var revealedCount: Int = 0
    private var revealedFloat: Float = 0f
    private var lastStepMs: Long = -1L
    private var lastTargetUpdateMs: Long = -1L
    private var currentCharsPerMs: Float = initialCharsPerMs
    private var desiredCharsPerMs: Float = initialCharsPerMs
    private var streaming: Boolean = false

    /**
     * Seed the reveal cursor so the smoother starts with [prefix] already
     * visible instead of rewinding to an empty string.
     *
     * letta-mobile-uoiu6: when an assistant message is first created with a
     * first token, that token is painted once via the plain (non-smoothed)
     * markdown path. The moment a subsequent delta makes the text grow, the
     * UI switches to the smoothed streaming renderer. Without seeding, the
     * smoother would start at `revealedCount = 0` and re-reveal from the
     * beginning, visibly wiping and re-growing the first word — the "first
     * word flash". Seeding with the already-painted prefix makes the reveal
     * continue from there, so the opening render is continuous.
     *
     * No-op once the smoother already has a non-empty target (i.e. it has
     * begun revealing), so this only takes effect on the initial engage.
     *
     * @param prefix the text already visible on screen when streaming engages.
     * @param isStreaming whether the stream is still open.
     * @param nowMs current monotonic time.
     */
    fun seed(prefix: String, isStreaming: Boolean, nowMs: Long) {
        if (target.isNotEmpty() || prefix.isEmpty()) {
            // Already started revealing (or nothing to seed). Fall through to
            // a normal target update so callers can use seed() unconditionally.
            updateTarget(prefix, isStreaming, nowMs)
            return
        }
        // letta-mobile-1kz40 (head-clip fix): the seed prefix is provisional —
        // it is the text already painted via the plain path at FIRST
        // composition. It is NOT guaranteed to be a prefix of the eventual
        // engaged target (coalescing / non-append first delta can rewrite the
        // head). We record the seed as the target and the reveal cursor, but
        // the FIRST updateTarget() re-verifies the cursor against the real
        // target via the longest-common-prefix clamp below. Even here we must
        // not advance the cursor past the seed's own length, and updateTarget
        // will clamp it to a verified prefix of whatever target actually
        // engages. The invariant target.substring(0, revealedCount) is always
        // a true prefix of target is preserved because revealedCount == prefix
        // .length == target.length here.
        target = prefix
        revealedCount = prefix.length
        revealedFloat = prefix.length.toFloat()
        lastStepMs = nowMs
        lastTargetUpdateMs = nowMs
        streaming = isStreaming
        logProfile("seed", nowMs)
    }

    /**
     * Push the latest accumulated text from the ViewModel.
     *
     * @param text full accumulated assistant text so far.
     * @param isStreaming true while the stream is still open.
     * @param nowMs current monotonic time (e.g. `System.nanoTime() / 1_000_000`).
     */
    fun updateTarget(text: String, isStreaming: Boolean, nowMs: Long) {
        if (text != target) {
            val oldTarget = target
            val appended = text.startsWith(oldTarget)
            if (appended) {
                // Pure append: keep the cursor, just measure incoming rate.
                val addedChars = text.length - oldTarget.length
                val elapsedSinceTargetUpdate = if (lastTargetUpdateMs >= 0L) {
                    (nowMs - lastTargetUpdateMs).coerceAtLeast(1L)
                } else {
                    0L
                }
                target = text
                if (addedChars > 0 && elapsedSinceTargetUpdate > 0L) {
                    val incomingRate = addedChars.toFloat() / elapsedSinceTargetUpdate.toFloat()
                    desiredCharsPerMs = incomingRate.coerceIn(MIN_CHARS_PER_MS, MAX_CHARS_PER_MS)
                }
                lastTargetUpdateMs = nowMs
            } else {
                // letta-mobile-1kz40 (head-clip + no-rewind fix):
                // The new text is NOT a pure append of the current target.
                // This happens on:
                //  - the smoother's first engage after a provisional seed()
                //    whose prefix was not actually a prefix of the real target
                //    (the head-clip cause: seed set revealedCount past the real
                //    head), and
                //  - a mid-stream rewrite/coalesce that replaces the tail.
                //
                // The OLD code reset revealedCount = 0, which both rewound the
                // visible text (re-typing from scratch) AND, combined with the
                // seed handoff, could leave the cursor misaligned. The fix:
                // compute the longest common prefix of the old target and the
                // new text, and CLAMP the reveal cursor to at most that prefix
                // length. We never advance past the verified common prefix, so
                // target.substring(0, revealedCount) is always a true prefix of
                // the new target. We KEEP whatever was already correctly
                // revealed (no visible rewind), and only future steps advance.
                val commonPrefix = longestCommonPrefixLength(oldTarget, text)
                target = text
                // Cursor must never exceed the verified common-prefix length
                // (head-clip guard) and never exceed the new target length.
                val maxCursor = minOf(commonPrefix, text.length)
                if (revealedCount > maxCursor) {
                    revealedCount = maxCursor
                    revealedFloat = maxCursor.toFloat()
                }
                // Re-measure rate from the net growth if any.
                val addedChars = text.length - oldTarget.length
                val elapsedSinceTargetUpdate = if (lastTargetUpdateMs >= 0L) {
                    (nowMs - lastTargetUpdateMs).coerceAtLeast(1L)
                } else {
                    0L
                }
                if (addedChars > 0 && elapsedSinceTargetUpdate > 0L) {
                    val incomingRate = addedChars.toFloat() / elapsedSinceTargetUpdate.toFloat()
                    desiredCharsPerMs = incomingRate.coerceIn(MIN_CHARS_PER_MS, MAX_CHARS_PER_MS)
                }
                lastTargetUpdateMs = nowMs
            }
        }
        streaming = isStreaming
        if (lastStepMs < 0L) lastStepMs = nowMs
        if (lastTargetUpdateMs < 0L) lastTargetUpdateMs = nowMs
        logProfile("updateTarget", nowMs)
    }

    /**
     * Length of the longest common prefix shared by [a] and [b]. Used to keep
     * the reveal cursor on a verified prefix of the (possibly rewritten)
     * target so the head is never clipped and the visible text never rewinds.
     */
    private fun longestCommonPrefixLength(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
    }

    /**
     * Advance the reveal cursor and return the substring to render.
     */
    fun step(nowMs: Long): String {
        if (target.isEmpty()) return ""

        val elapsed = if (lastStepMs >= 0L) (nowMs - lastStepMs).coerceAtLeast(0L) else 0L
        lastStepMs = nowMs

        val backlog = (target.length - revealedFloat).coerceAtLeast(0f)
        val backlogCatchupRate = if (backlog > BACKLOG_SOFT_LIMIT_CHARS) {
            ((backlog - BACKLOG_SOFT_LIMIT_CHARS) / BACKLOG_CATCHUP_WINDOW_MS)
                .coerceAtMost(MAX_BACKLOG_CATCHUP_CHARS_PER_MS)
        } else {
            0f
        }
        val targetVelocity = (desiredCharsPerMs + backlogCatchupRate)
            .coerceIn(MIN_CHARS_PER_MS, MAX_CHARS_PER_MS)

        val tweenAlpha = if (elapsed <= 0L) {
            0f
        } else {
            (elapsed.toFloat() / VELOCITY_TWEEN_MS).coerceIn(0f, 1f)
        }
        currentCharsPerMs += (targetVelocity - currentCharsPerMs) * tweenAlpha

        if (elapsed > 0L && revealedCount < target.length) {
            val advance = (elapsed * currentCharsPerMs).coerceAtLeast(MIN_ADVANCE_PER_FRAME)
            revealedFloat = (revealedFloat + advance).coerceAtMost(target.length.toFloat())
            revealedCount = revealedFloat.toInt().coerceIn(0, target.length)
            if (revealedCount == 0) revealedCount = 1
        }

        logProfile("step", nowMs)
        return target.substring(0, revealedCount)
    }

    /**
     * letta-mobile-1kz40 instrumentation: gated arrival-vs-reveal profiling.
     *
     * When [PROFILE] is true, logs `arrival` (target.length) and `reveal`
     * (revealedCount) with a monotonic timestamp on every updateTarget/seed/
     * step so we can read the choppy-arrival profile off logcat (tag
     * [PROFILE_TAG]) and tune the cadence constants from real data rather than
     * guesses. Compiled out (no-op) when [PROFILE] is false so it costs nothing
     * in shipping builds. Disable before finalizing.
     */
    private fun logProfile(phase: String, nowMs: Long) {
        if (!PROFILE) return
        android.util.Log.d(
            PROFILE_TAG,
            "$phase t=$nowMs arrival=${target.length} reveal=$revealedCount " +
                "backlog=${(target.length - revealedFloat).coerceAtLeast(0f)} " +
                "vel=${"%.4f".format(currentCharsPerMs)} streaming=$streaming",
        )
    }

    /** True when the full target has been revealed. */
    val isFullyRevealed: Boolean
        get() = revealedCount >= target.length

    companion object {
        // letta-mobile-1kz40: constants RE-TUNED FROM DEVICE DATA (Pixel 9 Pro,
        // real streamed turns captured via the gated logcat instrumentation
        // below). The measured arrival profile showed bursty deltas of
        // ~50-190 chars every 17-57ms => instantaneous arrival rates of
        // ~1.1-12 chars/ms (1,100-12,000 c/s), with a 4,785-char response fully
        // arriving in ~7s. The OLD constants capped reveal at 0.15 c/ms
        // (~144 c/s) with a feeble +0.02 c/ms catch-up, so the reveal fell
        // ~3,700 chars behind and NEVER caught up within 12s of arrival ending
        // (the "still typing a finished response" sluggishness). The new values
        // were validated against the captured trace in simulation:
        //   - peak backlog drops from ~3,742 chars (never converging) to a
        //     bounded buffer that fully reveals ~250ms after arrival ends,
        //   - per-frame reveal is hard-capped at ~100 chars (MAX * 50ms paint
        //     window) so a burst can never dump more than ~1 line in one frame
        //     (no lurch), while the median frame reveals ~12 chars (steady,
        //     readable cadence that EVENS OUT the choppy arrival gaps).

        /** Initial reveal rate so the head reveals promptly on engage. */
        const val DEFAULT_CHARS_PER_MS = 0.45f

        /**
         * Readable floor (slow streams still read smoothly) and a throughput
         * ceiling that bounds the single-frame lurch (MAX * paint window ≈
         * 100 chars/frame at 50ms) WITHOUT throttling overall throughput the
         * way the old 0.15 ceiling did.
         */
        private const val MIN_CHARS_PER_MS = 0.05f
        private const val MAX_CHARS_PER_MS = 2.0f

        /** Fast velocity adaptation so a burst speeds up reveal within ~2 frames. */
        private const val VELOCITY_TWEEN_MS = 110f

        /**
         * Catch-up engages once the reveal is ~1 line behind and drains the
         * backlog over a short window with a strong cap, so the reveal tracks
         * arrival instead of falling thousands of chars behind.
         */
        private const val BACKLOG_SOFT_LIMIT_CHARS = 70f
        private const val BACKLOG_CATCHUP_WINDOW_MS = 450f
        private const val MAX_BACKLOG_CATCHUP_CHARS_PER_MS = 2.0f
        private const val MIN_ADVANCE_PER_FRAME = 1f

        /**
         * letta-mobile-1kz40: gate for temporary arrival-vs-reveal logcat
         * instrumentation. MUST be false in committed/shipping code; flip true
         * locally to capture a tuning profile (see [logProfile]).
         */
        private const val PROFILE = false
        private const val PROFILE_TAG = "SmootherProfile"
    }
}
