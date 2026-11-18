package com.letta.mobile.data.voice.output

import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.AudioPlayer
import com.letta.mobile.data.voice.endpoint.SpeechSpeed
import com.letta.mobile.data.voice.engine.SpeechAudioMeter
import com.letta.mobile.data.voice.engine.VoiceEar
import com.letta.mobile.data.voice.engine.VoiceMouth
import com.letta.mobile.data.voice.loop.VoiceLoopState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * Voice mode's live controls and what it is saying, for the voice card: the current reply's
 * [spokenProgress] (for a word-by-word highlight), the current [turn] (who speaks, since when),
 * microphone and speaker mute, and the speech speed. Every time is in milliseconds on [nowMs]'s
 * clock (monotonic, from when the controls were created).
 *
 * Mutes apply at once, also mid-reply, and are cleared when a session ends. The microphone muted
 * means the ear hears nothing (nothing is transcribed or leaves the device); the speaker muted
 * means replies play silently at their own pace, so [spokenProgress] keeps moving. The speed is the
 * stored setting; a change applies from the next segment requested.
 */
class VoiceSessionControls internal constructor(
    timeSource: TimeSource,
    private val storedSpeed: StateFlow<SpeechSpeed>,
    private val saveSpeed: (SpeechSpeed) -> Unit,
) {
    private val origin = timeSource.markNow()
    private val tracker = SpokenProgressTracker(::nowMs) { storedSpeed.value }
    private val warmer = SpeechWarmer(timeSource)
    private val mutableTurn = MutableStateFlow<VoiceTurnClock?>(null)
    private val mutableMicrophoneMuted = MutableStateFlow(false)
    private val mutableSpeakerMuted = MutableStateFlow(false)

    /** The segments of the current reply that have started playing, in order; empty between replies. */
    val spokenProgress: StateFlow<List<SpokenSegment>> = tracker.segments

    /**
     * The current turn: the person's from when they start speaking until the reply starts playing,
     * then the agent's until the person speaks again; null while voice mode waits for the person
     * (or is off).
     */
    val turn: StateFlow<VoiceTurnClock?> = mutableTurn.asStateFlow()

    /** Whether the microphone is muted. */
    val microphoneMuted: StateFlow<Boolean> = mutableMicrophoneMuted.asStateFlow()

    /** Whether the speaker is muted. */
    val speakerMuted: StateFlow<Boolean> = mutableSpeakerMuted.asStateFlow()

    /** The speed replies are spoken at. */
    val speechSpeed: StateFlow<SpeechSpeed> get() = storedSpeed

    /** Now, in milliseconds on the clock every time here uses. */
    fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds

    /** Mutes or unmutes the microphone. */
    fun setMicrophoneMuted(muted: Boolean) {
        mutableMicrophoneMuted.value = muted
    }

    /** Mutes or unmutes the speaker. */
    fun setSpeakerMuted(muted: Boolean) {
        mutableSpeakerMuted.value = muted
    }

    /** Stores [speed] as the speed replies are spoken at. */
    fun setSpeechSpeed(speed: SpeechSpeed) = saveSpeed(speed)

    /** The meter a mouth reports each segment's audio length to. */
    internal val meter: SpeechAudioMeter get() = tracker

    /** The speed for the next request. */
    internal fun speed(): SpeechSpeed = storedSpeed.value

    /** [player], silenced while the speaker is muted. */
    internal fun speaker(player: AudioPlayer): AudioPlayer = MutedSpeaker(player, mutableSpeakerMuted)

    /** [source], silent while the microphone is muted. */
    internal fun microphone(source: AudioFrameSource): AudioFrameSource = MutedFrameSource(source, mutableMicrophoneMuted)

    /** [ear] and [mouth] as the session's loop drives them: muted turns dropped, speech followed. */
    internal fun engines(ear: VoiceEar, mouth: VoiceMouth): Pair<VoiceEar, VoiceMouth> =
        MutedEar(ear, mutableMicrophoneMuted) to tracker.track(mouth)

    /** Warms the session's speech path with [block] when the person starts speaking. */
    internal fun warmUpWith(block: suspend () -> Unit) = warmer.attach(block)

    /**
     * Follows one session's loop [states] until cancelled: keeps [turn], starts a new reply when the
     * person starts speaking, and warms the speech path then.
     */
    internal suspend fun follow(states: Flow<VoiceLoopState>) = coroutineScope {
        var speaking = false
        states.collect { state ->
            if (state.userSpeaking && !speaking) {
                tracker.newReply()
                warmer.due()?.let { block -> launch { warmer.run(block) } }
            }
            speaking = state.userSpeaking
            mutableTurn.value = nextTurn(mutableTurn.value, state)
        }
    }

    /** The session ended: clears progress, the turn and both mutes. */
    internal fun endSession() {
        warmer.detach()
        tracker.newReply()
        mutableTurn.value = null
        mutableMicrophoneMuted.value = false
        mutableSpeakerMuted.value = false
    }

    private fun nextTurn(current: VoiceTurnClock?, state: VoiceLoopState): VoiceTurnClock? {
        val speaker = when {
            state.userSpeaking -> VoiceSpeaker.USER
            state.phase == VoicePhase.SPEAKING -> VoiceSpeaker.AGENT
            state.phase == VoicePhase.THINKING -> current?.speaker
            else -> null
        } ?: return null
        return if (current?.speaker == speaker) current else VoiceTurnClock(speaker, nowMs())
    }
}
