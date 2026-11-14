package com.letta.mobile.data.voice.vad.ear

import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.vad.TurnDiscardReason
import com.letta.mobile.data.voice.vad.TurnEndReason
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** What the ear measured about one turn: its voiced audio and levels. Carries no audio or text. */
data class EarTurnReport(
    /** Audio the detector judged voiced. */
    val voiced: Duration,
    /** The loudest hop, in dBFS. */
    val peakDb: Double,
    /** The detector's noise floor when the turn ended, in dBFS; null when it keeps none. */
    val floorDb: Double?,
) {
    /** How far the loudest hop rose above the floor, in dB; null without a floor. */
    val peakSnrDb: Double? get() = floorDb?.let { peakDb - it }

    /** Shared reports. */
    companion object {
        /** Nothing measured. */
        val Unmeasured: EarTurnReport = EarTurnReport(Duration.ZERO, SILENT_DB, null)
    }
}

private const val SILENT_DB = -120.0

/**
 * Hears what the ear decides about turns, for diagnostics. Every call carries measurements and
 * reasons only, never audio or words. Called from the listening coroutine; must not block.
 */
interface EarObserver {
    /** A candidate had enough voice: a turn opened. */
    fun turnOpened() = Unit

    /** A turn ended for [reason] and goes to transcription. */
    fun turnClosed(report: EarTurnReport, reason: TurnEndReason) = Unit

    /** A heard sound or a transcribed turn was dropped for [reason]; nothing is sent. */
    fun turnDiscarded(report: EarTurnReport, reason: TurnDiscardReason) = Unit

    /** A transcript of [characters] characters was accepted as the person's turn. */
    fun transcriptAccepted(characters: Int) = Unit

    /** Shared observers. */
    companion object {
        /** Observes nothing. */
        val None: EarObserver = object : EarObserver {}
    }
}

/**
 * The second gate, after transcription: a transcript that is not the person's words is dropped
 * and the ear goes on listening. A transcript is dropped when it is empty, when it holds fewer than
 * [minWords] letters or digits (punctuation, a stray letter), or, for a short quiet turn only
 * (less than [shortVoice] of voice, its loudest hop less than [quietPeakSnrDb] above the floor),
 * when it is one of [stockPhrases]: what recognisers like Whisper write for noise. A turn with no
 * floor measured counts as quiet.
 */
data class TranscriptPolicy(
    /** Fewer letters or digits than this is not words. */
    val minWords: Int = 2,
    /** A turn with less voice than this is short. */
    val shortVoice: Duration = 1_200.milliseconds,
    /** A turn whose loudest hop is less than this far above the floor is quiet. */
    val quietPeakSnrDb: Double = 24.0,
    /** Stock noise phrases, lower case, letters, digits and single spaces only. */
    val stockPhrases: Set<String> = DEFAULT_STOCK_PHRASES,
) {
    /** Why [text], transcribed from a turn measured as [report], is dropped; null when it is kept. */
    fun discardReason(text: VoiceText, report: EarTurnReport): TurnDiscardReason? = when {
        text.isBlank -> TurnDiscardReason.EMPTY_TRANSCRIPT
        text.value.count { it.isLetterOrDigit() } < minWords -> TurnDiscardReason.NO_WORDS
        isShortAndQuiet(report) && normalized(text.value) in stockPhrases -> TurnDiscardReason.STOCK_PHRASE
        else -> null
    }

    private fun isShortAndQuiet(report: EarTurnReport): Boolean {
        if (report.voiced >= shortVoice) return false
        val snr = report.peakSnrDb ?: return true
        return snr < quietPeakSnrDb
    }

    private fun normalized(text: String): String {
        val spaced = text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
        return spaced.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Shared phrase lists. */
    companion object {
        /** Phrases Whisper-style recognisers commonly produce from noise or silence. */
        val DEFAULT_STOCK_PHRASES: Set<String> = setOf(
            "you", "thank you", "thanks for watching", "thank you for watching", "thank you so much for watching",
            "please subscribe", "bye", "bye bye", "uh", "um", "hmm", "mm", "oh", "ah", "the end",
            "subtitles by the amara org community",
        )
    }
}
