package com.letta.mobile.data.system1

/**
 * Tier 0 of System 1: deterministic, on-device, sub-millisecond.
 *
 * Three of the questions an interactive composer asks on every keystroke —
 * *is the user done?*, *have they changed enough to be worth re-evaluating?*,
 * *have they paused?* — are structural, not semantic. This gate answers them in
 * pure Kotlin with no model, no network and no cost, and decides whether the
 * remaining, genuinely semantic question (*do they expect a response?*) is worth
 * an ~90ms call to the Laya service.
 *
 * This split is not merely an optimisation. Measured on the stock
 * `convaiinnovations/laya` checkpoint, completeness framings produce
 * *overlapping* distributions between finished messages and truncated drafts
 * (best min/max separation +0.007, against +0.87 for the shipped jailbreak
 * preset on the same harness — see `tools/system1-laya/calibration_probe.py`).
 * A heuristic that reads the final token is both exact and free where the model
 * is neither.
 */
class System1ReflexGate(
    private val config: Config = Config(),
) {
    /**
     * @param idleMsBeforeEvaluate quiet period after the last keystroke before a
     *   draft is worth evaluating. Below this the user is still mid-flow.
     * @param minTokenDelta tokens that must change before re-consulting the
     *   service. Guards against paying ~90ms per character.
     * @param minTokensForCompletion words below which a draft without terminal
     *   punctuation is assumed unfinished.
     */
    data class Config(
        val idleMsBeforeEvaluate: Long = 400L,
        val minTokenDelta: Int = 2,
        val minTokensForCompletion: Int = 3,
    )

    /** Why the gate judged a draft finished or unfinished. */
    enum class CompletionReason {
        /** Nothing typed yet. */
        EMPTY,

        /** Ends in `.`, `?`, `!` or their full-width equivalents. */
        TERMINAL_PUNCTUATION,

        /** Ends on a word that cannot end a sentence ("and", "the", "to"...). */
        DANGLING_WORD,

        /** Too few words to be a request on its own. */
        TOO_SHORT,

        /** No structural objection; reads as a finished thought. */
        SETTLED,
    }

    data class Signals(
        val isComplete: Boolean,
        val completionReason: CompletionReason,
        val isSubstantiveChange: Boolean,
        val changedTokenCount: Int,
        val isIdle: Boolean,
        /**
         * True when every structural precondition holds and the semantic
         * question is now worth an HTTP round-trip.
         */
        val shouldConsultSystem1: Boolean,
    )

    /**
     * @param draft the live composer buffer.
     * @param lastEvaluatedDraft the buffer at the last System 1 evaluation, or
     *   empty if there has not been one.
     * @param msSinceLastEdit elapsed time since the last keystroke.
     */
    fun evaluate(
        draft: String,
        lastEvaluatedDraft: String = "",
        msSinceLastEdit: Long = Long.MAX_VALUE,
    ): Signals {
        val tokens = tokenize(draft)
        val previousTokens = tokenize(lastEvaluatedDraft)
        val reason = completionReason(draft, tokens)
        val isComplete = reason == CompletionReason.TERMINAL_PUNCTUATION ||
            reason == CompletionReason.SETTLED
        val delta = tokenDelta(tokens, previousTokens)
        val substantive = delta >= config.minTokenDelta
        val idle = msSinceLastEdit >= config.idleMsBeforeEvaluate

        return Signals(
            isComplete = isComplete,
            completionReason = reason,
            isSubstantiveChange = substantive,
            changedTokenCount = delta,
            isIdle = idle,
            shouldConsultSystem1 = isComplete && substantive && idle,
        )
    }

    private fun completionReason(draft: String, tokens: List<String>): CompletionReason {
        val trimmed = draft.trim()
        if (trimmed.isEmpty()) return CompletionReason.EMPTY
        if (trimmed.last() in TERMINAL_PUNCTUATION) return CompletionReason.TERMINAL_PUNCTUATION
        if (tokens.isNotEmpty() && tokens.last() in DANGLING_WORDS) {
            return CompletionReason.DANGLING_WORD
        }
        if (tokens.size < config.minTokensForCompletion) return CompletionReason.TOO_SHORT
        return CompletionReason.SETTLED
    }

    /**
     * Symmetric-difference size over token *multisets*, so reordering a sentence
     * registers as change while retyping the same words does not.
     *
     * The final token of each side is excluded because it is the one currently
     * under the cursor. Without that, typing a single character into a word
     * scores 2 (the partial token removed, the longer one added) and every
     * keystroke would look substantive — the exact cost this gate exists to
     * avoid.
     */
    private fun tokenDelta(current: List<String>, previous: List<String>): Int {
        if (previous.isEmpty()) return current.size
        val settledCurrent = current.dropLast(1)
        val remaining = previous.dropLast(1).toMutableList()
        var added = 0
        for (token in settledCurrent) {
            if (!remaining.remove(token)) added++
        }
        return added + remaining.size
    }

    private fun tokenize(text: String): List<String> = text
        .lowercase()
        .split(*TOKEN_DELIMITERS)
        .filter { it.isNotBlank() }

    private companion object {
        val TERMINAL_PUNCTUATION = charArrayOf('.', '?', '!', '。', '？', '！')

        val TOKEN_DELIMITERS = charArrayOf(
            ' ', '\t', '\n', '\r', ',', '.', '?', '!', ';', ':', '(', ')', '[', ']', '"',
        )

        /**
         * Words that cannot end a finished sentence. Function words only — a
         * content word can legitimately be the last word of a request
         * ("run the tests"), so including them would read finished messages as
         * unfinished.
         */
        val DANGLING_WORDS = setOf(
            "a", "an", "the", "and", "but", "or", "nor", "so", "yet",
            "to", "of", "in", "on", "at", "by", "for", "with", "from", "into",
            "that", "which", "who", "whom", "whose", "if", "when", "while",
            "is", "are", "was", "were", "be", "been", "being", "am",
            "my", "your", "our", "their", "its", "his", "her",
            "i", "you", "we", "they", "he", "she", "it",
            "can", "could", "would", "should", "will", "shall", "may", "might", "must",
            "do", "does", "did", "have", "has", "had", "how",
        )
    }
}
