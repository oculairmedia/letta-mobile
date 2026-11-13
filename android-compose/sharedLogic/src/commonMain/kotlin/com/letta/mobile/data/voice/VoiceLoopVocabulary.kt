package com.letta.mobile.data.voice

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Where the voice loop is. */
enum class VoicePhase {
    /** Not listening; the microphone is closed. */
    IDLE,

    /** Listening for the person's turn. */
    LISTENING,

    /**
     * The utterance was sent; waiting for reply text. The person may speak again meanwhile: what
     * they say is sent as a follow-up and its answer is spoken after this one.
     */
    THINKING,

    /** Speaking the reply. */
    SPEAKING,

    /**
     * Stopped on an error of the microphone, an engine that would not let go, or an engine that
     * ended; the loop state's failure says which. Activating again starts over. A failed answer or a
     * failed segment of speech does not stop the loop: it is reported and the loop listens on.
     */
    FAILED,
}

/** Whether an interrupted answer is still arriving. */
enum class InterruptStatus {
    /** Nothing interrupted is outstanding. */
    NONE,

    /** An interrupt was requested and the answer is still arriving; its text is not spoken. */
    REQUESTED,

    /** The answerer did not confirm the interrupt in time; its text is still not spoken. */
    UNRESPONSIVE,
}

/** Why text the loop received was not spoken. */
enum class SpeechSkipReason {
    /** The person spoke over the answer: the rest of it was dropped. */
    BARGE_IN,

    /** The person interrupted (a tap or a key): every pending answer was dropped. */
    INTERRUPTED,

    /** Too many answers were pending: the oldest one not yet spoken was dropped. */
    PENDING_LIMIT,

    /** Speaking failed: the rest of that answer was dropped. */
    MOUTH_FAILED,

    /** The answer failed: the text it had sent ahead was dropped. */
    REPLY_FAILED,

    /** The answer produced no text in time and was stopped. */
    TIMED_OUT,
}

/** Why the app's speech was stopped before it finished. */
enum class SpeechStopReason {
    /** The person pressed to interrupt (the voice button, a key, the strip's control). */
    BUTTON,

    /** The person spoke over it. */
    BARGE_IN,

    /** Voice mode stopped (a stop, a conversation change, a failure the loop cannot go on after). */
    MODE_STOPPED,

    /** Speaking failed; the mouth was reset. */
    MOUTH_FAILED,

    /** The answer produced no text in time; its own immediate reply was stopped. */
    TIMED_OUT,
}

/** How the loop behaves. */
data class VoiceLoopConfig(
    /** Single shot or continuous conversation. */
    val mode: VoiceConversationMode = VoiceConversationMode.CONTINUOUS,
    /** Whether the microphone stays open while answering (for follow-ups and, with [voiceInterrupts], barge-in). */
    val bargeIn: Boolean = true,
    /**
     * Whether speech heard while the app speaks interrupts it. Turn it off where the microphone is
     * not echo-cancelled (half-duplex): the ear then ignores the microphone during output, speech
     * reported over the output never stops it, and the person interrupts with a press instead.
     */
    val voiceInterrupts: Boolean = true,
    /** What happens to replies that arrive after the loop went back to listening. */
    val lateReplies: LateReplyPolicy = LateReplyPolicy.SPEAK_WHEN_QUIET,
    /** Longest wait from sending an utterance to the first reply text. */
    val thinkingTimeout: Duration = 2.minutes,
    /** Longest wait for an engine to release its resources after being told to stop. */
    val releaseTimeout: Duration = 3.seconds,
    /** The chunker's longest spoken segment. */
    val maxSegmentLength: Int = DEFAULT_MAX_SEGMENT_LENGTH,
    /** The most late reply segments held while the person speaks; older ones are dropped. */
    val maxHeldLateSegments: Int = DEFAULT_MAX_HELD,
    /**
     * The most answers awaited at once (the one being spoken included); a further utterance drops
     * the oldest answer that has not started speaking.
     */
    val maxPendingReplies: Int = DEFAULT_MAX_PENDING,
) {
    private companion object {
        const val DEFAULT_MAX_HELD = 16
        const val DEFAULT_MAX_PENDING = 4
        const val DEFAULT_MAX_SEGMENT_LENGTH = 240
    }
}
