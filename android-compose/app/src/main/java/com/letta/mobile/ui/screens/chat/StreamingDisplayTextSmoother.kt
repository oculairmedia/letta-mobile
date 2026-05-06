package com.letta.mobile.ui.screens.chat

/**
 * Meters out bursty streaming-text arrivals at word boundaries so the
 * chat bubble grows at a readable pace without layout thrashing.
 *
 * Design (letta-mobile-flk2): production apps (ChatGPT, Claude, GetStream)
 * use word-by-word reveal, not character-by-character. Each step advances
 * by one word token, keeping layout changes meaningful and reducing
 * LazyColumn recompositions by ~5x vs per-character reveal.
 *
 * Usage:
 *  1. Call [updateTarget] every time the ViewModel pushes new accumulated
 *     text (or flips `isStreaming` off).
 *  2. Call [step] once per frame to get the substring to render.
 *
 * Tokenization: splits on word boundaries using `(\s+|\S+)`, preserving
 * whitespace as separate tokens. A 500-char message has ~100 tokens
 * instead of 500 characters — 5x fewer layout changes.
 */
class StreamingDisplayTextSmoother(
    private val wordIntervalMs: Long = DEFAULT_WORD_INTERVAL_MS,
) {
    private var target: String = ""
    private var revealedWordCount: Int = 0
    private var lastStepMs: Long = -1L
    private var streaming: Boolean = false

    // Cached token list for the current target — rebuilt on updateTarget
    private var tokens: List<String> = emptyList()

    /**
     * Push the latest accumulated text.
     */
    fun updateTarget(text: String, isStreaming: Boolean, nowMs: Long) {
        if (text != target) {
            if (!text.startsWith(target)) {
                revealedWordCount = 0
                lastStepMs = nowMs
            }
            // Continuation: keep revealedWordCount, rebuild tokens
            target = text
            tokens = tokenize(text)
            // No clock reset for continuations — time accumulates
        }
        streaming = isStreaming
        if (lastStepMs < 0L) {
            lastStepMs = nowMs
        }
    }

    /**
     * Advance by one word token if enough time has passed, then return
     * the prefix of [target] to display.
     */
    fun step(nowMs: Long): String {
        if (target.isEmpty()) return ""

        val elapsed = if (lastStepMs >= 0L) (nowMs - lastStepMs).coerceAtLeast(0L) else 0L
        val interval = if (streaming) wordIntervalMs else wordIntervalMs / DRAIN_DIVISOR

        // Advance by one word token per interval tick.
        // When multiple intervals have passed (sleep/wake), catch up in bursts.
        val steps = ((elapsed / interval).toInt()).coerceAtLeast(1)
        lastStepMs = nowMs

        revealedWordCount = (revealedWordCount + steps).coerceAtMost(tokens.size)

        // Reconstruct the prefix from token count
        val prefix = tokens.take(revealedWordCount).joinToString("")
        return prefix
    }

    val isFullyRevealed: Boolean
        get() = revealedWordCount >= tokens.size

    companion object {
        /**
         * Reveal one word token every 30ms — matches GetStream's production
         * StreamingText component cadence. At ~5 chars/word, this produces
         * ~33 words/sec ~165 chars/sec, comfortably ahead of typical LLM
         * throughput (~40 tokens/sec × ~4 chars/token ≈ 160 chars/sec).
         */
        const val DEFAULT_WORD_INTERVAL_MS = 30L

        /** Drain 3× faster when stream ends. */
        private const val DRAIN_DIVISOR = 3L

        /** Split text into word tokens: words + whitespace preserved. */
        private val TOKEN_REGEX = Regex("(\\s+|\\S+)")

        private fun tokenize(text: String): List<String> {
            return TOKEN_REGEX.findAll(text).map { it.value }.toList()
        }
    }
}
