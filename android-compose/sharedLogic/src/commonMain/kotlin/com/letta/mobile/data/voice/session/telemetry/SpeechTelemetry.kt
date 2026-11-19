package com.letta.mobile.data.voice.session.telemetry

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioPlayer
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import com.letta.mobile.data.voice.endpoint.SpeechEndpointException
import com.letta.mobile.data.voice.endpoint.SpeechSpeed
import com.letta.mobile.data.voice.endpoint.StreamingSpeechSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlin.concurrent.Volatile

/**
 * [delegate] with every speech request's stages reported, numbered per session (`request`):
 * `speech.request.opened` with the text's length, `speech.firstFrame` with the sample rate and channel
 * count of its first audio, then `speech.request.completed` with the audio frames received, or
 * `speech.request.failed` with the voice loop's code, the HTTP status when the server answered and
 * whether any audio had arrived. A request cancelled by the loop (a stop, a barge-in) reports
 * `speech.request.cancelled`. Never the text, the audio, the key or the address.
 */
internal class TelemetrySynthesizer(
    private val delegate: StreamingSpeechSynthesizer,
    private val telemetry: VoiceTelemetry,
) : StreamingSpeechSynthesizer {
    @Volatile
    private var requests = 0

    override fun stream(text: VoiceText, speed: SpeechSpeed): Flow<PcmChunk> = flow {
        val request = ++requests
        telemetry.event("speech.request.opened", "request" attr request, "characters" attr text.value.length)
        var frames = 0
        try {
            delegate.stream(text, speed).collect { chunk ->
                if (frames == 0) firstFrame(request, chunk)
                frames++
                emit(chunk)
            }
        } catch (cancelled: CancellationException) {
            telemetry.event("speech.request.cancelled", "request" attr request, "frames" attr frames)
            throw cancelled
        } catch (expectedFailure: Exception) {
            failed(request, expectedFailure, frames)
            throw expectedFailure
        }
        telemetry.event("speech.request.completed", "request" attr request, "frames" attr frames)
    }

    override suspend fun warm() = delegate.warm()

    private fun firstFrame(request: Int, chunk: PcmChunk) = telemetry.event(
        "speech.firstFrame",
        "request" attr request,
        "sampleRateHz" attr chunk.format.sampleRateHz,
        "channels" attr chunk.format.channels,
        "samples" attr chunk.samples.size,
    )

    private fun failed(request: Int, failure: Exception, frames: Int) {
        val endpoint = failure as? SpeechEndpointException
        telemetry.event(
            "speech.request.failed",
            "request" attr request,
            "code" attr (endpoint?.code?.name ?: UNKNOWN),
            "status" attr endpoint?.status,
            "frames" attr frames,
        )
    }
}

/**
 * [delegate] with each stream it plays reported, numbered per session (`stream`):
 * `playback.started` once its first audio reaches the player (with that audio's sample rate and
 * channel count), then `playback.ended` once it has played out, or `playback.failed` with the code of
 * an audio failure (PLAYBACK_FAILED, UNAVAILABLE, UNSUPPORTED_FORMAT; anything else is reported as
 * its kind) and whether any audio had reached the player: a failure before it did is the output
 * refusing to start (on Android, audio focus denied), one after it a failure opening or playing
 * the output (a track that does not open, a focus loss, a stalled device). A stream stopped by the loop reports
 * nothing more. Never the audio.
 */
internal class TelemetryPlayer(
    private val delegate: AudioPlayer,
    private val telemetry: VoiceTelemetry,
) : AudioPlayer {
    @Volatile
    private var streams = 0

    override suspend fun play(chunks: Flow<PcmChunk>, onLevel: (AudioLevel) -> Unit) {
        val stream = ++streams
        var started = false
        val observed = chunks.onEach { chunk ->
            if (!started) {
                started = true
                telemetry.event(
                    "playback.started",
                    "stream" attr stream,
                    "sampleRateHz" attr chunk.format.sampleRateHz,
                    "channels" attr chunk.format.channels,
                )
            }
        }
        try {
            delegate.play(observed, onLevel)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expectedFailure: Exception) {
            val code = (expectedFailure as? VoiceAudioException)?.code?.name ?: expectedFailure::class.simpleName ?: UNKNOWN
            telemetry.event("playback.failed", "stream" attr stream, "code" attr code, "started" attr started)
            throw expectedFailure
        }
        telemetry.event("playback.ended", "stream" attr stream, "started" attr started)
    }

    override fun setMuted(muted: Boolean) = delegate.setMuted(muted)
}

private const val UNKNOWN = "UNKNOWN"
