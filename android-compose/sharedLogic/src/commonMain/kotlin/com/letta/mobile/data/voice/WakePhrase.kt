package com.letta.mobile.data.voice

import kotlin.jvm.JvmInline

/** A BCP-47 language tag for a wake phrase, for example `en` or `de-CH`. */
@JvmInline
value class WakeLanguage(
    /** The tag. */
    val tag: String,
) {
    /** Shared tags. */
    companion object {
        /** English. */
        val English: WakeLanguage = WakeLanguage("en")
    }
}

/**
 * How readily a phrase triggers, from 0 (never) to 1 (on the faintest match). A hit passes when its
 * confidence is at least `1 - value`. Calibration produces one per phrase.
 */
@JvmInline
value class WakeSensitivity(
    /** The sensitivity, within 0..1. */
    val value: Float,
) {
    init {
        require(value in 0f..1f) { "WakeSensitivity outside 0..1" }
    }

    /** The lowest confidence that passes at this sensitivity. */
    val threshold: Float get() = 1f - value

    /** Shared sensitivities. */
    companion object {
        /** The default: a hit needs confidence 0.5. */
        val Default: WakeSensitivity = WakeSensitivity(0.5f)
    }
}

/** The id a wake-word engine registers under, for example `alkove`. */
@JvmInline
value class WakeWordEngineId(
    /** The id. */
    val value: String,
)

/** Why a wake phrase's text is not acceptable. */
enum class WakePhraseProblem {
    /** Nothing but whitespace. */
    EMPTY,

    /** Shorter than [WakePhrase.MIN_LENGTH] characters. */
    TOO_SHORT,

    /** Longer than [WakePhrase.MAX_LENGTH] characters. */
    TOO_LONG,

    /** Contains a control or bidirectional-override character. */
    FORBIDDEN_CHARACTER,
}

/**
 * A wake phrase the person typed: its text, language and sensitivity. Construction validates the
 * text ([problemWith] must be null); trimmed text is stored.
 */
data class WakePhrase(
    /** The phrase, trimmed. */
    val text: String,
    /** Its language. */
    val language: WakeLanguage = WakeLanguage.English,
    /** How readily it triggers. */
    val sensitivity: WakeSensitivity = WakeSensitivity.Default,
) {
    init {
        require(problemWith(text) == null) { "Invalid wake phrase" }
        require(text == text.trim()) { "Wake phrase must be trimmed" }
    }

    /** Validation. */
    companion object {
        /** The shortest phrase. */
        const val MIN_LENGTH: Int = 3

        /** The longest phrase. */
        const val MAX_LENGTH: Int = 64

        private val bidiControls: Set<Char> = setOf(
            '‎', '‏', '؜', '‪', '‫', '‬', '‭', '‮',
            '⁦', '⁧', '⁨', '⁩',
        )

        /** What is wrong with [raw] as a phrase, or null when it is acceptable. */
        fun problemWith(raw: String): WakePhraseProblem? {
            val text = raw.trim()
            return when {
                text.isEmpty() -> WakePhraseProblem.EMPTY
                text.length < MIN_LENGTH -> WakePhraseProblem.TOO_SHORT
                text.length > MAX_LENGTH -> WakePhraseProblem.TOO_LONG
                text.any(::isForbidden) -> WakePhraseProblem.FORBIDDEN_CHARACTER
                else -> null
            }
        }

        private fun isForbidden(char: Char): Boolean = char.isISOControl() || char in bidiControls
    }
}
