package com.letta.mobile.ui.chat.voice

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.letta.mobile.data.voice.session.VoiceModeController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.TimeSource

/**
 * One piece of the agent's reply as voice mode speaks it, in [index] order. Times are on the feed's
 * clock ([VoiceCardFeed.now]): a piece queued but not yet playing has no [startedAtMs]; one playing
 * has it, and [audioDurationMs] once its audio's length is known; one done (or cut off) has
 * [finishedAtMs].
 */
@Immutable
data class VoiceSpokenSegment(
    val index: Int,
    val text: String,
    val startedAtMs: Long? = null,
    val audioDurationMs: Long? = null,
    val finishedAtMs: Long? = null,
)

/** A switch the voice card offers (mute the microphone, mute the speaker): whether it is [on], and how to [set] it. */
@Stable
class VoiceCardSwitch(val on: StateFlow<Boolean>, val set: (Boolean) -> Unit)

/** The reply's speaking speed the voice card offers: the current multiple of normal speed, the [choices], and how to [set] one. */
@Stable
class VoiceCardSpeed(
    val value: StateFlow<Float>,
    val set: (Float) -> Unit,
    val choices: List<Float> = DEFAULT_CHOICES,
) {
    /** The speeds offered unless a controller says otherwise. */
    companion object {
        val DEFAULT_CHOICES: List<Float> = listOf(0.75f, 1f, 1.25f, 1.5f)
    }
}

/**
 * What the voice card reads beyond voice mode's state: the reply's pieces as they are spoken (so the
 * spoken words can be lit and the rest dimmed), and the controls it offers. A control is null where
 * the controller does not offer it, and the card then leaves its button out.
 */
@Stable
interface VoiceCardFeed {
    /** The reply's pieces, in order; empty before a reply is spoken. */
    val spokenSegments: StateFlow<List<VoiceSpokenSegment>>

    /** Mutes the microphone (voice mode keeps going, hearing nothing), or null. */
    val microphoneMute: VoiceCardSwitch?

    /** Mutes the reply's audio (the words still light up), or null. */
    val speakerMute: VoiceCardSwitch?

    /** The reply's speaking speed, or null. */
    val speed: VoiceCardSpeed?

    /** The clock the segments' times are on, in milliseconds; the card's turn timer reads it too. */
    fun now(): Long
}

/**
 * The voice card's feed over [controller]. The controller does not yet publish the reply's spoken
 * pieces, a speaking speed or mutes, so this offers none of them (the card shows the reply without
 * progress and leaves those buttons out) and keeps a monotonic clock for the turn timer; it maps
 * them here once the controller does.
 */
internal fun controllerVoiceCardFeed(@Suppress("UNUSED_PARAMETER") controller: VoiceModeController): VoiceCardFeed =
    MonotonicVoiceCardFeed()

/** A feed with no spoken pieces and no controls, timing on the monotonic clock. */
private class MonotonicVoiceCardFeed : VoiceCardFeed {
    private val origin = TimeSource.Monotonic.markNow()
    override val spokenSegments: StateFlow<List<VoiceSpokenSegment>> = MutableStateFlow(emptyList())
    override val microphoneMute: VoiceCardSwitch? = null
    override val speakerMute: VoiceCardSwitch? = null
    override val speed: VoiceCardSpeed? = null

    override fun now(): Long = origin.elapsedNow().inWholeMilliseconds
}
