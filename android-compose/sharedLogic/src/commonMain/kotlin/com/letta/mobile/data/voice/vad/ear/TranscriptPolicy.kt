package com.letta.mobile.data.voice.vad.ear

import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.endpoint.RecognizerConfidence
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

    /** The turn's words were offered before its end, from live partial words ([fromPartials]) or an early transcription. */
    fun turnOffered(fromPartials: Boolean) = Unit

    /** The person went on speaking after the turn's words were offered: the offer was withdrawn. */
    fun offerWithdrawn() = Unit

    /** Shared observers. */
    companion object {
        /** Observes nothing. */
        val None: EarObserver = object : EarObserver {}
    }
}

/**
 * The second gate, after transcription: a transcript that is not the person's words is dropped
 * and the ear goes on listening. A transcript is dropped when it is empty; when it holds fewer than
 * [minWords] letters or digits (punctuation, a stray letter); when the recogniser's own
 * numbers say every segment is not speech ([notSpeech]); when the turn had less than
 * [shortVoice] of voice and the transcript is one of [stockPhrases], what recognisers like Whisper
 * write for noise, however loud the turn was (a loud room makes noise loud); or when the turn had
 * less than [fillerVoice] of voice and the transcript is a lone [fillerWords] word. Short answers
 * that are in neither list ("yes", "no", "ok", "sure") are kept unless the recogniser judged them not speech.
 */
data class TranscriptPolicy(
    /** Fewer letters or digits than this is not words. */
    val minWords: Int = 2,
    /** A turn with less voice than this is short: a stock phrase from it is noise. */
    val shortVoice: Duration = 1_200.milliseconds,
    /** A turn with less voice than this is very short: a lone filler word from it is noise. */
    val fillerVoice: Duration = 640.milliseconds,
    /** Stock noise phrases, lower case, letters, digits and single spaces only. */
    val stockPhrases: Set<String> = DEFAULT_STOCK_PHRASES,
    /** Lone filler words recognisers write for a very short noise, in the same form as [stockPhrases]. */
    val fillerWords: Set<String> = DEFAULT_FILLER_WORDS,
    /** When the recogniser's own numbers mean the audio was not speech. */
    val notSpeech: NotSpeechThresholds = NotSpeechThresholds(),
) {
    /**
     * Why [text], transcribed from a turn measured as [report] with the recogniser's numbers for each
     * of its [segments] (empty when the server reported none), is dropped; null when it is kept.
     */
    fun discardReason(text: VoiceText, report: EarTurnReport, segments: List<RecognizerConfidence> = emptyList()): TurnDiscardReason? =
        when {
            text.isBlank -> TurnDiscardReason.EMPTY_TRANSCRIPT
            text.value.count { it.isLetterOrDigit() } < minWords -> TurnDiscardReason.NO_WORDS
            segments.isNotEmpty() && segments.all(notSpeech::matches) -> TurnDiscardReason.NOT_SPEECH
            else -> phraseReason(normalized(text.value), report.voiced)
        }

    private fun phraseReason(phrase: String, voiced: Duration): TurnDiscardReason? = when {
        voiced < shortVoice && phrase in stockPhrases -> TurnDiscardReason.STOCK_PHRASE
        voiced < fillerVoice && phrase in fillerWords -> TurnDiscardReason.FILLER_WORD
        else -> null
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

        /** Lone words Whisper-style recognisers commonly produce from a very short noise. */
        val DEFAULT_FILLER_WORDS: Set<String> = setOf("so", "okay")
    }
}

/**
 * When a segment's [RecognizerConfidence] means its audio was not speech: its no-speech probability is at
 * least [certainNoSpeech]; or its log probability is at most [veryLowLogProbability]; or, Whisper's
 * own silence rule, its no-speech probability is above [likelyNoSpeech] while its log probability
 * is below [lowLogProbability]. The defaults of the last pair are Whisper's own.
 */
data class NotSpeechThresholds(
    /** A no-speech probability this high alone means not speech. */
    val certainNoSpeech: Double = 0.9,
    /** A log probability this low alone means the words are noise. */
    val veryLowLogProbability: Double = -2.0,
    /** Above this no-speech probability, a transcript below [lowLogProbability] is not speech. */
    val likelyNoSpeech: Double = 0.6,
    /** Below this log probability, a transcript above [likelyNoSpeech] is not speech. */
    val lowLogProbability: Double = -1.0,
) {
    /** True when [confidence] means the audio was not speech. */
    fun matches(confidence: RecognizerConfidence): Boolean {
        val noSpeech = confidence.noSpeechProbability
        val logProbability = confidence.averageLogProbability
        if (noSpeech >= certainNoSpeech || logProbability <= veryLowLogProbability) return true
        return noSpeech > likelyNoSpeech && logProbability < lowLogProbability
    }
}
