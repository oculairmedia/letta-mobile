package com.letta.mobile.data.voice.output

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.audio.AudioFrame
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.AudioPlayer
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.audio.SamplePosition
import com.letta.mobile.data.voice.endpoint.SpeechSpeed
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.SpeechSegmentId
import com.letta.mobile.data.voice.engine.VoiceEar
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceMouth
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import com.letta.mobile.data.voice.loop.VoiceLoopState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionControlsTest {
    private val clock = TestTimeSource()
    private val speed = MutableStateFlow(SpeechSpeed.Normal)
    private val controls = VoiceSessionControls(clock, speed) { speed.value = it }

    private fun segment(id: Long, text: String) = SpeechSegment(SpeechSegmentId(id), VoiceText(text))

    /** A mouth whose events the test emits. */
    private class ScriptedMouth : VoiceMouth {
        val emitted = MutableSharedFlow<VoiceMouthEvent>(extraBufferCapacity = 16)
        val spoken = mutableListOf<SpeechSegment>()
        var stops = 0

        override val events: Flow<VoiceMouthEvent> = emitted

        override fun speak(segment: SpeechSegment) {
            spoken += segment
        }

        override fun flush() = Unit

        override suspend fun stop() {
            stops++
        }
    }

    private fun mouthOf(): Pair<ScriptedMouth, VoiceMouth> {
        val inner = ScriptedMouth()
        return inner to controls.engines(SilentEar, inner).second
    }

    @Test
    fun aSegmentAppearsWhenItStartsAndItsDurationFirmsUpAsItsAudioArrives() = runTest {
        val (inner, mouth) = mouthOf()
        backgroundScope.launch { mouth.events.collect {} }
        runCurrent()
        mouth.speak(segment(1, "A".repeat(30)))
        mouth.speak(segment(2, "Second."))
        assertEquals(2, inner.spoken.size, "the segments still reach the mouth")
        assertEquals(emptyList(), controls.spokenProgress.value, "nothing is shown before it plays")
        clock += 1.seconds
        inner.emitted.emit(VoiceMouthEvent.Started(SpeechSegmentId(1)))
        runCurrent()
        val started = controls.spokenProgress.value.single()
        assertEquals(SpokenSegment(0, VoiceText("A".repeat(30)), 1_000, audioDurationMs = 2_000, finishedAtMs = null), started)
        controls.meter.measured(SpeechSegmentId(1), 2_500.milliseconds, complete = false)
        assertEquals(2_500, controls.spokenProgress.value.single().audioDurationMs, "more audio than the estimate lengthens it")
        controls.meter.measured(SpeechSegmentId(1), 1_800.milliseconds, complete = true)
        assertEquals(1_800, controls.spokenProgress.value.single().audioDurationMs, "exact once all of it arrived")
        clock += 1_800.milliseconds
        inner.emitted.emit(VoiceMouthEvent.Finished(SpeechSegmentId(1)))
        inner.emitted.emit(VoiceMouthEvent.Started(SpeechSegmentId(2)))
        runCurrent()
        val (first, second) = controls.spokenProgress.value
        assertEquals(2_800, first.finishedAtMs)
        assertEquals(1, second.index)
        assertEquals(2_800, second.startedAtMs)
        assertNull(second.finishedAtMs)
    }

    @Test
    fun theEstimateFollowsTheSpeed() = runTest {
        val (inner, mouth) = mouthOf()
        backgroundScope.launch { mouth.events.collect {} }
        runCurrent()
        controls.setSpeechSpeed(SpeechSpeed(1.5f))
        assertEquals(SpeechSpeed(1.5f), controls.speechSpeed.value)
        mouth.speak(segment(1, "A".repeat(45)))
        inner.emitted.emit(VoiceMouthEvent.Started(SpeechSegmentId(1)))
        runCurrent()
        assertEquals(2_000, controls.spokenProgress.value.single().audioDurationMs)
    }

    @Test
    fun stoppingTheMouthEndsThePlayingSegmentAndDropsTheQueue() = runTest {
        val (inner, mouth) = mouthOf()
        backgroundScope.launch { mouth.events.collect {} }
        runCurrent()
        mouth.speak(segment(1, "First."))
        mouth.speak(segment(2, "Second."))
        inner.emitted.emit(VoiceMouthEvent.Started(SpeechSegmentId(1)))
        runCurrent()
        clock += 400.milliseconds
        mouth.stop()
        assertEquals(1, inner.stops)
        assertEquals(400, controls.spokenProgress.value.single().finishedAtMs)
        inner.emitted.emit(VoiceMouthEvent.Started(SpeechSegmentId(2)))
        runCurrent()
        assertEquals(1, controls.spokenProgress.value.size, "a dropped segment never appears")
    }

    @Test
    fun theTurnClockFollowsWhoSpeaksAndANewTurnClearsTheReply() = runTest {
        val (inner, mouth) = mouthOf()
        backgroundScope.launch { mouth.events.collect {} }
        val states = MutableStateFlow(VoiceLoopState(phase = VoicePhase.LISTENING))
        var warmed = 0
        controls.warmUpWith { warmed++ }
        backgroundScope.launch { controls.follow(states) }
        runCurrent()
        assertNull(controls.turn.value, "waiting for the person")
        clock += 1.seconds
        states.value = VoiceLoopState(phase = VoicePhase.LISTENING, userSpeaking = true)
        runCurrent()
        assertEquals(VoiceTurnClock(VoiceSpeaker.USER, 1_000), controls.turn.value)
        assertEquals(1, warmed, "the speech path is warmed when the person starts speaking")
        clock += 2.seconds
        states.value = VoiceLoopState(phase = VoicePhase.THINKING)
        runCurrent()
        assertEquals(VoiceTurnClock(VoiceSpeaker.USER, 1_000), controls.turn.value, "still the person's turn while the answer is awaited")
        assertEquals(2_000, controls.turn.value?.elapsedMs(controls.nowMs()))
        states.value = VoiceLoopState(phase = VoicePhase.SPEAKING)
        mouth.speak(segment(1, "Answer."))
        inner.emitted.emit(VoiceMouthEvent.Started(SpeechSegmentId(1)))
        runCurrent()
        assertEquals(VoiceTurnClock(VoiceSpeaker.AGENT, 3_000), controls.turn.value)
        assertEquals(1, controls.spokenProgress.value.size)
        clock += 5.seconds
        states.value = VoiceLoopState(phase = VoicePhase.SPEAKING, userSpeaking = true)
        runCurrent()
        assertEquals(VoiceTurnClock(VoiceSpeaker.USER, 8_000), controls.turn.value)
        assertEquals(emptyList(), controls.spokenProgress.value, "the person spoke: a new reply starts")
        assertEquals(1, warmed, "at most one warm-up within the interval")
        states.value = VoiceLoopState(phase = VoicePhase.LISTENING)
        runCurrent()
        assertNull(controls.turn.value)
        clock += 30.seconds
        states.value = VoiceLoopState(phase = VoicePhase.LISTENING, userSpeaking = true)
        runCurrent()
        assertEquals(2, warmed, "warmed again once the interval passed")
    }

    @Test
    fun aFailingWarmUpIsIgnored() = runTest {
        val states = MutableStateFlow(VoiceLoopState(phase = VoicePhase.LISTENING))
        controls.warmUpWith { error("unreachable") }
        val following = backgroundScope.launch { controls.follow(states) }
        runCurrent()
        states.value = VoiceLoopState(phase = VoicePhase.LISTENING, userSpeaking = true)
        runCurrent()
        assertTrue(following.isActive)
        assertEquals(VoiceSpeaker.USER, controls.turn.value?.speaker)
    }

    @Test
    fun aTurnThatStartsWhileTheMicrophoneIsMutedIsDroppedWhole() = runTest {
        val heard = listOf(
            VoiceEarEvent.InputLevel(AudioLevel(0.6f)),
            VoiceEarEvent.SpeechStarted,
            VoiceEarEvent.PartialTranscript(VoiceText("muted words")),
            VoiceEarEvent.SpeechEnded,
            VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText("muted words"))),
        )
        controls.setMicrophoneMuted(true)
        assertTrue(controls.microphoneMuted.value)
        val ear = controls.engines(ScriptedEar(heard), ScriptedMouth()).first
        assertEquals(listOf<VoiceEarEvent>(VoiceEarEvent.InputLevel(AudioLevel.Silent)), ear.listen().toList())
        controls.setMicrophoneMuted(false)
        assertEquals(heard, ear.listen().toList(), "unmuted, everything goes through")
    }

    @Test
    fun aTurnStartedBeforeTheMuteStillEnds() = runTest {
        val muted = MutableStateFlow(false)
        val inner = ScriptedEar(
            flow {
                emit(VoiceEarEvent.SpeechStarted)
                muted.value = true
                emit(VoiceEarEvent.SpeechEnded)
                emit(VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText("said before"))))
            },
        )
        val events = MutedEar(inner, muted).listen().toList()
        assertEquals(3, events.size, "the loop is never left waiting for a turn that cannot end")
    }

    @Test
    fun aMutedMicrophoneProducesSilentFramesAtTheSamePositions() = runTest {
        val source = object : AudioFrameSource {
            override val format = AudioFrameFormat()
            override val echoCancelled = true

            override fun frames(): Flow<AudioFrame> = flowOf(AudioFrame(ShortArray(4) { 900 }, SamplePosition(512)))
        }
        controls.setMicrophoneMuted(true)
        val muted = controls.microphone(source)
        assertTrue(muted.echoCancelled)
        val frame = muted.frames().toList().single()
        assertContentEquals(ShortArray(4), frame.samples)
        assertEquals(SamplePosition(512), frame.start)
    }

    @Test
    fun theSpeakerFollowsTheMuteWhilePlaying() = runTest {
        val player = RecordingPlayer()
        val speaker = controls.speaker(player)
        controls.setSpeakerMuted(true)
        speaker.play(emptyFlow()) {}
        assertEquals(listOf(true), player.mutes)
        assertTrue(controls.speakerMuted.value)
    }

    @Test
    fun endingTheSessionClearsMutesTurnAndProgress() = runTest {
        controls.setMicrophoneMuted(true)
        controls.setSpeakerMuted(true)
        controls.endSession()
        assertEquals(false, controls.microphoneMuted.value)
        assertEquals(false, controls.speakerMuted.value)
        assertNull(controls.turn.value)
        assertEquals(emptyList(), controls.spokenProgress.value)
    }

    private class RecordingPlayer : AudioPlayer {
        val mutes = mutableListOf<Boolean>()

        override suspend fun play(chunks: Flow<PcmChunk>, onLevel: (AudioLevel) -> Unit) = chunks.collect {}

        override fun setMuted(muted: Boolean) {
            mutes += muted
        }
    }

    private class ScriptedEar(private val heard: Flow<VoiceEarEvent>) : VoiceEar {
        constructor(heard: List<VoiceEarEvent>) : this(heard.let { list -> flow { list.forEach { emit(it) } } })

        override fun listen(): Flow<VoiceEarEvent> = heard

        override fun commitTurn() = Unit
    }

    private object SilentEar : VoiceEar {
        override fun listen(): Flow<VoiceEarEvent> = emptyFlow()

        override fun commitTurn() = Unit
    }
}
