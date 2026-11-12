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

    /** The utterance was sent; waiting for the first reply text. */
    THINKING,

    /** Speaking the reply. */
    SPEAKING,

    /** Stopped on an error; the loop state's failure says which. Activating again starts over. */
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

/** How the loop behaves. */
data class VoiceLoopConfig(
    /** Single shot or continuous conversation. */
    val mode: VoiceConversationMode = VoiceConversationMode.CONTINUOUS,
    /** Whether speech while the app speaks interrupts it (keeps the microphone open while speaking). */
    val bargeIn: Boolean = true,
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
) {
    private companion object {
        const val DEFAULT_MAX_HELD = 16
        const val DEFAULT_MAX_SEGMENT_LENGTH = 240
    }
}
