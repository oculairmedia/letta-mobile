package com.letta.mobile.data.voice

import kotlin.jvm.JvmInline

/**
 * Text heard from, or spoken to, the person. Its [toString] reports only the length, so a
 * transcript or reply that reaches a log or a crash report by accident carries no words.
 */
@JvmInline
value class VoiceText(
    /** The words themselves. Never log or report this value. */
    val value: String,
) {
    /** True when there is nothing to say or send. */
    val isBlank: Boolean get() = value.isBlank()

    override fun toString(): String = "VoiceText(length=${value.length})"

    /** Shared texts. */
    companion object {
        /** No text. */
        val Empty: VoiceText = VoiceText("")
    }
}

/** A normalised loudness from 0 (silence) to 1 (full scale), for meters and the avatar's mouth. */
@JvmInline
value class AudioLevel(
    /** The level, within 0..1. */
    val value: Float,
) {
    init {
        require(value in MIN..MAX) { "AudioLevel outside 0..1" }
    }

    /** Shared levels and normalisation. */
    companion object {
        private const val MIN = 0f
        private const val MAX = 1f

        /** Silence. */
        val Silent: AudioLevel = AudioLevel(MIN)

        /** [raw] forced into 0..1; a NaN reading is silence. */
        fun clamped(raw: Float): AudioLevel = if (raw.isNaN()) Silent else AudioLevel(raw.coerceIn(MIN, MAX))
    }
}

/**
 * The name of a structured action a hearing model decided on (for example `research_task`).
 * Names are chosen by the model's own contract; the loop only carries them to the responder.
 */
@JvmInline
value class VoiceActionKind(
    /** The action's name as the model emits it. */
    val value: String,
)

/**
 * A structured action a hearing model decided on, carried to the responder unchanged. Its
 * [toString] names the action and the argument keys only, because arguments can quote the person.
 */
data class VoiceActionDecision(
    /** What to do. */
    val kind: VoiceActionKind,
    /** The action's arguments, as the model emitted them. */
    val arguments: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "VoiceActionDecision(kind=${kind.value}, keys=${arguments.keys.sorted()})"
}

/** Who answers an utterance, as decided by the ear at end of turn. */
sealed interface VoiceDecision {
    /** Hand the utterance to the responder. What a plain speech-to-text ear always decides. */
    data object Respond : VoiceDecision

    /** The ear answered the utterance itself (its immediate reply); the responder is not called. */
    data object AnsweredByEar : VoiceDecision

    /** The ear decided on a structured [action]; the responder receives it with the utterance. */
    data class Act(
        /** The action the ear decided on. */
        val action: VoiceActionDecision,
    ) : VoiceDecision
}

/**
 * The opaque handle of audio an ear captured and still holds for one explicit retry. It names the
 * capture; it never carries or prints the audio.
 */
@JvmInline
value class CapturedAudioHandle(
    /** The ear's own number for the capture. */
    val value: Long,
)

/**
 * One finished turn of the person's speech, as the ear decided it ended.
 *
 * A plain speech-to-text ear fills only [text]. A realtime hearing model can also answer at once
 * ([immediateReply]) and decide who answers ([decision]).
 */
data class VoiceUtterance(
    /** What the person said. */
    val text: VoiceText,
    /** A short reply the ear produced itself, spoken before anything else; null when it has none. */
    val immediateReply: VoiceText? = null,
    /** Who answers; [VoiceDecision.Respond] for a plain transcriber. */
    val decision: VoiceDecision = VoiceDecision.Respond,
    /** The capture this utterance came from, retained by the ear until its next capture starts. */
    val capture: CapturedAudioHandle? = null,
) {
    /** True when the turn carries nothing to answer, say or act on (an action-only turn is not empty). */
    val isEmpty: Boolean
        get() = when {
            decision is VoiceDecision.Act -> false
            immediateReply != null -> false
            else -> text.isBlank
        }
}
