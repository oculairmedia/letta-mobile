package com.letta.mobile.data.voice.smoke

import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.SpeechModelName
import com.letta.mobile.data.voice.SpeechVoiceName
import com.letta.mobile.data.voice.VoiceEndpointSettings
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.PcmFormat
import com.letta.mobile.data.voice.audio.desktop.DesktopAudio
import com.letta.mobile.data.voice.audio.device.RecoveringFrameSource
import com.letta.mobile.data.voice.audio.pcm.PcmAudio
import com.letta.mobile.data.voice.audio.pcm.PcmLevels
import com.letta.mobile.data.voice.audio.pcm.WavCodec
import com.letta.mobile.data.voice.audio.pcm.play
import com.letta.mobile.data.voice.consent.EndpointOrigin
import com.letta.mobile.data.voice.consent.VoiceEndpointGrant
import com.letta.mobile.data.voice.consent.VoiceEndpointGrants
import com.letta.mobile.data.voice.consent.VoiceEndpointPayload
import com.letta.mobile.data.voice.endpoint.EndpointSpeechSynthesizer
import com.letta.mobile.data.voice.endpoint.EndpointTranscriber
import com.letta.mobile.data.voice.endpoint.EndpointVoiceMouth
import com.letta.mobile.data.voice.endpoint.SpeechApiKey
import com.letta.mobile.data.voice.endpoint.SpeechEndpointAddress
import com.letta.mobile.data.voice.endpoint.SpeechEndpointClient
import com.letta.mobile.data.voice.endpoint.SpeechEndpointConfig
import com.letta.mobile.data.voice.endpoint.SpeechTransportPolicy
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.SpeechSegmentId
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import com.letta.mobile.data.voice.vad.ear.SpeechTranscriber
import com.letta.mobile.data.voice.vad.ear.VadVoiceEar
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

/**
 * Headless device smoke runner for a person at the keyboard. Never run by a
 * test; it opens the real microphone and speakers.
 *
 * - `record [seconds]`: records from the default microphone (3 s by default) to a WAVE file in a
 *   new temp directory, prints its path, level and dropped frames, then plays it back.
 * - `loop <base-url> [minutes] [speech-model] [voice] [transcription-model]`: listens with the
 *   voice-activity ear, transcribes each turn through the OpenAI-compatible server at base-url,
 *   and speaks "You said ..." back through the endpoint mouth, printing every state change.
 *   A bearer key, if the server needs one, is read from VOICE_SMOKE_API_KEY; with a key the URL must
 *   be https or a loopback host, otherwise the run stops before sending anything (INSECURE_TRANSPORT).
 *   The mouth asks the server for WAVE audio. Destination grants
 *   for that one origin are created here, explicitly, for this run only.
 *
 * Run: `./gradlew :sharedLogic:voiceSmoke --args="record"` or
 * `./gradlew :sharedLogic:voiceSmoke --args="loop http://127.0.0.1:8880"`.
 */
fun main(args: Array<String>) = runBlocking {
    when (args.firstOrNull()) {
        "record" -> record(args.getOrNull(1)?.toIntOrNull() ?: DEFAULT_SECONDS)
        "loop" -> args.getOrNull(1)?.let { loop(LoopArgs.parse(args.drop(1))) } ?: usage()
        else -> usage()
    }
}

private const val DEFAULT_SECONDS = 3
private const val DEFAULT_MINUTES = 5

private fun usage() {
    println("usage: record [seconds] | loop <base-url> [minutes] [speech-model] [voice] [transcription-model]")
}

private suspend fun record(seconds: Int) {
    val format = AudioFrameFormat()
    val source = DesktopAudio.frameSource(format)
    val wanted = seconds.toLong() * format.sampleRateHz
    val samples = ArrayList<Short>()
    println("recording $seconds s from the default microphone...")
    source.frames().takeWhile { it.start.value < wanted }.collect { frame -> frame.samples.forEach { samples += it } }
    val pcm = PcmAudio(samples.toShortArray(), PcmFormat(format.sampleRateHz, format.channels))
    val file = Files.createTempDirectory("voice-smoke").resolve("recording.wav")
    Files.write(file, WavCodec.encode(pcm))
    println("wrote $file (${pcm.frames} samples, level ${PcmLevels.level(pcm.samples).value}, dropped ${source.droppedFrames})")
    println("playing it back...")
    DesktopAudio.player().play(AudioClip(WavCodec.encode(pcm), AudioEncoding.WAV)) { }
    println("done")
}

/** The loop mode's arguments. */
private class LoopArgs(val settings: VoiceEndpointSettings, val minutes: Int) {
    companion object {
        fun parse(rest: List<String>): LoopArgs {
            val defaults = VoiceEndpointSettings()
            val settings = VoiceEndpointSettings(
                baseUrl = SpeechEndpointUrl(rest[0]),
                speechModel = rest.getOrNull(2)?.let(::SpeechModelName) ?: defaults.speechModel,
                voice = rest.getOrNull(3)?.let(::SpeechVoiceName) ?: defaults.voice,
                transcriptionModel = rest.getOrNull(4)?.let(::SpeechModelName) ?: defaults.transcriptionModel,
            )
            return LoopArgs(settings, rest.getOrNull(1)?.toIntOrNull() ?: DEFAULT_MINUTES)
        }
    }
}

private suspend fun loop(args: LoopArgs) {
    val url = checkNotNull(args.settings.baseUrl)
    val origin = checkNotNull(EndpointOrigin.of(url)) { "not an http(s) URL" }
    val grants = VoiceEndpointGrants(VoiceEndpointPayload.entries.map { VoiceEndpointGrant(origin, it) }.toSet())
    val key = System.getenv("VOICE_SMOKE_API_KEY")?.takeIf { it.isNotBlank() }?.let(::SpeechApiKey)
    val base = SpeechEndpointAddress.resolve(url, emptyList())
        ?: return println("refused (${explain(VoiceErrorCode.INVALID_ENDPOINT)}): ${url.value}; nothing was sent.")
    if (key != null && !SpeechTransportPolicy.protectsKey(base)) {
        return println(
            "refused (${VoiceErrorCode.INSECURE_TRANSPORT}): VOICE_SMOKE_API_KEY is set but ${url.value} is not https or a " +
                "loopback host (localhost, 127.x.x.x, ::1); nothing was sent. Use https or a loopback tunnel.",
        )
    }
    val client = SpeechEndpointClient(CIO.create(), SpeechEndpointConfig(url, key))
    val transcriber = SpeechTranscriber.endpoint(EndpointTranscriber(client, { grants }, args.settings))
    val source = RecoveringFrameSource(DesktopAudio.frameSource(), deviceChanges = DesktopAudio.deviceChanges())
    val ear = VadVoiceEar(source, transcriber)
    println("listening for ${args.minutes} min against ${origin.value}; speak, pause, and hear it back")
    try {
        withTimeoutOrNull(args.minutes.minutes) {
            val synthesizer = EndpointSpeechSynthesizer(client, { grants }, args.settings, AudioEncoding.WAV)
            val mouth = EndpointVoiceMouth(synthesizer, DesktopAudio.player(), this)
            val echo = EchoTurns(ear, mouth)
            launch { mouth.events.collect { echo.onMouth(it) } }
            ear.listen().collect { echo.onEar(it) }
        }
    } finally {
        runCatching { ear.close() }
        client.close()
        println("stopped")
    }
}

/** Prints state changes and speaks each transcript back; barge-in stops the mouth. */
private class EchoTurns(private val ear: VadVoiceEar, private val mouth: EndpointVoiceMouth) {
    private var segment = 0L
    private var retried = false

    fun onMouth(event: VoiceMouthEvent) {
        when (event) {
            is VoiceMouthEvent.Started -> ear.setOutputActive(true).also { println("mouth: speaking") }
            is VoiceMouthEvent.Finished -> ear.setOutputActive(false).also { println("mouth: finished") }
            is VoiceMouthEvent.Failed -> {
                ear.setOutputActive(false)
                println("mouth: segment ${event.id.value} failed ${explain(event.error.code)}")
            }
            is VoiceMouthEvent.OutputLevel -> Unit
        }
    }

    suspend fun onEar(event: VoiceEarEvent) {
        when (event) {
            VoiceEarEvent.ListeningStarted -> println("ear: listening")
            VoiceEarEvent.SpeechStarted -> bargeIn()
            is VoiceEarEvent.EndOfTurn -> heard(event.utterance.text)
            is VoiceEarEvent.Failed -> failed(event)
            else -> Unit
        }
    }

    /** Speech started: stop the mouth, which reports nothing when stopped, so the echo guard is lifted here. */
    private suspend fun bargeIn() {
        println("ear: speech started")
        mouth.stop()
        ear.setOutputActive(false)
    }

    private fun heard(text: VoiceText) {
        println("ear: end of turn: \"${text.value}\"")
        retried = false
        if (!text.isBlank) mouth.speak(SpeechSegment(SpeechSegmentId(++segment), VoiceText("You said: ${text.value}")))
    }

    private fun failed(event: VoiceEarEvent.Failed) {
        println("ear: failed ${explain(event.error.code)}")
        val handle = event.retry ?: return
        if (retried) return
        retried = true
        println("ear: retrying once -> ${ear.retry(handle)}")
    }
}

/** A failure code, with a hint for the ones a person can fix from the command line. */
private fun explain(code: VoiceErrorCode): String = when (code) {
    VoiceErrorCode.INSECURE_TRANSPORT -> "$code (a key over plain http to a non-loopback host; use https or a loopback tunnel)"
    VoiceErrorCode.INVALID_ENDPOINT -> "$code (use a plain base URL: no query, fragment or user info)"
    VoiceErrorCode.UNSUPPORTED_FORMAT -> "$code (the server did not return WAVE audio)"
    VoiceErrorCode.PERMISSION_REQUIRED -> "$code (microphone permission or destination grant missing)"
    VoiceErrorCode.UNAUTHORIZED -> "$code (check VOICE_SMOKE_API_KEY)"
    else -> code.toString()
}
