package com.letta.mobile.data.voice.session.telemetry

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioPlayer
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.audio.PcmFormat
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import com.letta.mobile.data.voice.endpoint.SpeechEndpointException
import com.letta.mobile.data.voice.endpoint.SpeechOperation
import com.letta.mobile.data.voice.endpoint.SpeechSpeed
import com.letta.mobile.data.voice.endpoint.StreamingSpeechSynthesizer
import com.letta.mobile.data.voice.tone.ToneCue
import com.letta.mobile.data.voice.tone.ToneModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Each speech request and playback stream reports its stages, with numbers and codes only. */
class SpeechStageTelemetryTest {
    private val events = mutableListOf<Pair<String, Map<String, VoiceTelemetryValue>>>()
    private val telemetry = VoiceTelemetry { _, name, fields -> events += name to fields.toMap() }
    private val words = VoiceText("A secret reply the telemetry must never carry.")
    private val chunk = PcmChunk(ShortArray(480), PcmFormat(sampleRateHz = 24_000, channels = 1))

    private fun named(name: String) = events.filter { it.first == name }.map { it.second }

    private fun synthesizer(audio: Flow<PcmChunk>) = TelemetrySynthesizer(
        object : StreamingSpeechSynthesizer {
            override fun stream(text: VoiceText, speed: SpeechSpeed): Flow<PcmChunk> = audio
        },
        telemetry,
    )

    @Test
    fun aRequestReportsItsOpeningItsFirstFrameFormatAndItsEnd() = runTest {
        synthesizer(flowOf(chunk, chunk)).stream(words).toList()

        assertEquals(VoiceTelemetryValue.Numeric(words.value.length), named("speech.request.opened").single()["characters"])
        val first = named("speech.firstFrame").single()
        assertEquals(VoiceTelemetryValue.Numeric(24_000), first["sampleRateHz"])
        assertEquals(VoiceTelemetryValue.Numeric(1), first["channels"])
        assertEquals(VoiceTelemetryValue.Numeric(2), named("speech.request.completed").single()["frames"])
    }

    @Test
    fun aRefusedRequestReportsTheCodeAndThatNoAudioArrived() = runTest {
        val refused = flow<PcmChunk> { throw SpeechEndpointException(SpeechOperation.SPEECH, VoiceErrorCode.UNREACHABLE) }

        assertFailsWith<SpeechEndpointException> { synthesizer(refused).stream(words).collect() }

        val failed = named("speech.request.failed").single()
        assertEquals(VoiceTelemetryValue.Text("UNREACHABLE"), failed["code"])
        assertEquals(VoiceTelemetryValue.Numeric(0), failed["frames"])
        assertEquals(emptyList(), named("speech.firstFrame"))
    }

    @Test
    fun theWrapperPassesTheToneOnSoItsInstructionsReachTheServer() = runTest {
        val tones = mutableListOf<ToneCue?>()
        val wrapped = TelemetrySynthesizer(
            object : StreamingSpeechSynthesizer {
                override fun stream(text: VoiceText, speed: SpeechSpeed): Flow<PcmChunk> = flowOf(chunk)

                override fun stream(text: VoiceText, speed: SpeechSpeed, tone: ToneCue?): Flow<PcmChunk> {
                    tones += tone
                    return flowOf(chunk)
                }
            },
            telemetry,
        )

        wrapped.stream(words, SpeechSpeed.Normal, ToneCue(ToneModule.WARM)).toList()
        wrapped.stream(words, SpeechSpeed.Normal).toList()

        assertEquals(listOf("warm", null), tones.map { it?.instructions })
        assertTrue(events.flatMap { it.second.values }.none { it == VoiceTelemetryValue.Text("warm") }, "the module is never reported")
    }

    @Test
    fun playbackReportsItsStartWithTheFormatAndItsEnd() = runTest {
        TelemetryPlayer(DrainingPlayer(), telemetry).play(flowOf(chunk)) {}

        val started = named("playback.started").single()
        assertEquals(VoiceTelemetryValue.Numeric(24_000), started["sampleRateHz"])
        assertEquals(VoiceTelemetryValue.Flag(true), named("playback.ended").single()["started"])
    }

    @Test
    fun aPlaybackFailureReportsItsCode() = runTest {
        val player = TelemetryPlayer(DrainingPlayer(VoiceAudioException(VoiceErrorCode.PLAYBACK_FAILED)), telemetry)

        assertFailsWith<VoiceAudioException> { player.play(flowOf(chunk)) {} }

        val failed = named("playback.failed").single()
        assertEquals(VoiceTelemetryValue.Text("PLAYBACK_FAILED"), failed["code"])
        assertEquals(VoiceTelemetryValue.Flag(true), failed["started"])
    }

    @Test
    fun noStageEverCarriesTheReplyText() = runTest {
        synthesizer(flowOf(chunk)).stream(words).toList()
        TelemetryPlayer(DrainingPlayer(), telemetry).play(flowOf(chunk)) {}

        val texts = events.flatMap { it.second.values }.filterIsInstance<VoiceTelemetryValue.Text>().map { it.value }
        assertTrue(texts.none { words.value.contains(it) && it.length > 3 }, "no reply text in any field")
    }

    /** Collects every chunk, then fails with [failure] if one is given. */
    private class DrainingPlayer(private val failure: Exception? = null) : AudioPlayer {
        override suspend fun play(chunks: Flow<PcmChunk>, onLevel: (AudioLevel) -> Unit) {
            chunks.collect()
            failure?.let { throw it }
        }

        override fun setMuted(muted: Boolean) = Unit
    }
}
