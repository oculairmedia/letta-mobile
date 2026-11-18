package com.letta.mobile.data.voice.host.client

import com.letta.mobile.data.voice.SpeechLanguage
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.audio.PcmFormat
import com.letta.mobile.data.voice.endpoint.SpeechTranscript
import com.letta.mobile.data.voice.host.HostSpeechEvent
import com.letta.mobile.data.voice.host.HostSpeechStreamFrame
import com.letta.mobile.data.voice.host.HostTranscriptionResult
import com.letta.mobile.data.voice.host.HostVoiceClearParams
import com.letta.mobile.data.voice.host.HostVoiceConnectionCandidate
import com.letta.mobile.data.voice.host.HostVoiceEndpoint
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import com.letta.mobile.data.voice.host.HostVoiceException
import com.letta.mobile.data.voice.host.HostVoiceModels
import com.letta.mobile.data.voice.host.HostVoiceSetupCommand
import com.letta.mobile.data.voice.host.HostVoiceSetupStatus
import com.letta.mobile.data.voice.host.HostVoiceTestResult
import com.letta.mobile.data.voice.host.HostVoiceWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The host's voice endpoint over the Iroh `admin_rpc` link ([HostVoiceLink]), the way provider and model routes are
 * called: every method is one request on the host connection, so the speech server setup and its key
 * stay on the host and every device reaches the same server through it.
 *
 * - Setup reads and writes, the connection check and the model list are single requests.
 * - [transcribe] sends one mono 16-bit WAVE clip as Base64; a clip over the contract's 1 MiB or 30 s
 *   is split into pieces within both limits, sent in order, and their words joined.
 * - [synthesize] opens a streamed request: the `format` frame states the PCM format once, each `audio`
 *   frame becomes a [PcmChunk] as soon as it is decoded, and the final response ends the flow.
 *   Cancelling the collector cancels the request on the host (and the host's call to the server). No
 *   frame arrives for [speechIdle]: the request fails with [HostVoiceErrorCode.UPSTREAM_TIMEOUT].
 * - A `voice.busy` answer before any audio (the host still finishing a request this device just
 *   cancelled) is retried up to [busyAttempts] times, [busyBackoff] apart.
 *
 * Every failure is a [HostVoiceException]; a transport failure is [HostVoiceErrorCode.INTERNAL]. Nothing
 * here logs, and nothing it throws carries audio, words, the address or a key.
 */
class IrohHostVoiceEndpoint(
    private val link: HostVoiceLink,
    private val speechIdle: Duration = DEFAULT_SPEECH_IDLE,
    private val busyBackoff: Duration = DEFAULT_BUSY_BACKOFF,
    private val busyAttempts: Int = DEFAULT_BUSY_ATTEMPTS,
) : HostVoiceEndpoint {
    init {
        require(busyAttempts >= 1) { "busyAttempts must be at least 1" }
    }

    override suspend fun status(): HostVoiceSetupStatus =
        decode(HostVoiceSetupStatus.serializer(), call(HostVoiceWire.SETUP_GET, EMPTY))

    override suspend fun writeSetup(command: HostVoiceSetupCommand): HostVoiceSetupStatus {
        val params = json.encodeToJsonElement(HostVoiceSetupCommand.serializer(), command) as JsonObject
        return decode(HostVoiceSetupStatus.serializer(), call(HostVoiceWire.SETUP_SET, params))
    }

    override suspend fun clearSetup(expectedRevision: String?): HostVoiceSetupStatus {
        val params = json.encodeToJsonElement(HostVoiceClearParams.serializer(), HostVoiceClearParams(expectedRevision)) as JsonObject
        return decode(HostVoiceSetupStatus.serializer(), call(HostVoiceWire.SETUP_CLEAR, params))
    }

    override suspend fun testConnection(candidate: HostVoiceConnectionCandidate?): HostVoiceTestResult {
        val params = candidate?.let { json.encodeToJsonElement(HostVoiceConnectionCandidate.serializer(), it) as JsonObject } ?: EMPTY
        return decode(HostVoiceTestResult.serializer(), call(HostVoiceWire.SETUP_TEST, params))
    }

    override suspend fun models(): HostVoiceModels =
        decode(HostVoiceModels.serializer(), call(HostVoiceWire.MODELS_LIST, EMPTY))

    override suspend fun transcribe(audio: AudioClip, language: SpeechLanguage?): SpeechTranscript {
        val parts = HostVoiceClips.split(audio).map { piece -> retryingBusy { transcribeOne(piece, language) } }
        if (parts.size == 1) return parts.single()
        val words = parts.map { it.text.value.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return SpeechTranscript(VoiceText(words), parts.flatMap { it.segments })
    }

    override fun synthesize(text: VoiceText): Flow<PcmChunk> = channelFlow {
        if (text.value.length > HostVoiceWire.MAX_SPEECH_TEXT_CHARS) throw HostVoiceException(HostVoiceErrorCode.PAYLOAD_TOO_LARGE)
        val params = buildJsonObject { put("text", text.value) }
        var attempt = 1
        while (speakOnce(this, params) == SpeechAttempt.BUSY) {
            if (attempt >= busyAttempts) throw HostVoiceException(HostVoiceErrorCode.BUSY)
            attempt++
            delay(busyBackoff)
        }
    }

    private suspend fun transcribeOne(piece: AudioClip, language: SpeechLanguage?): SpeechTranscript {
        val params = buildJsonObject {
            put("audio", Base64.encode(piece.bytes))
            put("encoding", piece.encoding.wireName)
            language?.let { put("language", it.value) }
        }
        return decode(HostTranscriptionResult.serializer(), call(HostVoiceWire.TRANSCRIBE, params)).toTranscript()
    }

    /** One streamed speech request: its audio sent into [out] as it arrives; BUSY when refused as busy before any audio. */
    private suspend fun speakOnce(out: ProducerScope<PcmChunk>, params: JsonObject): SpeechAttempt = coroutineScope {
        val frames = link.stream(HostVoiceWire.SPEECH, params).buffer(FRAME_BUFFER).produceIn(this)
        try {
            SpeechReader(out).readAll(frames)
        } finally {
            frames.cancel()
        }
    }

    /** Reads one speech exchange's frames in order and checks the sequence and the format. */
    private inner class SpeechReader(private val out: ProducerScope<PcmChunk>) {
        private var format: PcmFormat? = null
        private var nextSeq = 0
        private var audioSeen = false

        suspend fun readAll(frames: ReceiveChannel<JsonObject>): SpeechAttempt {
            while (true) {
                val received = withTimeoutOrNull(speechIdle) { frames.receiveCatching() }
                    ?: throw HostVoiceException(HostVoiceErrorCode.UPSTREAM_TIMEOUT)
                val frame = received.getOrNull() ?: throw transportFailure(received.exceptionOrNull())
                when (frame.type()) {
                    HostVoiceWire.STREAM_FRAME_TYPE -> streamFrame(frame)
                    RESPONSE_TYPE -> return finalResponse(frame)
                    else -> Unit
                }
            }
        }

        private suspend fun streamFrame(raw: JsonObject) {
            val frame = decode(HostSpeechStreamFrame.serializer(), raw, HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE)
            if (frame.seq != nextSeq++) throw HostVoiceException(HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE)
            when (frame.event) {
                HostSpeechEvent.FORMAT -> format = frame.format() ?: throw HostVoiceException(HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE)
                HostSpeechEvent.AUDIO -> {
                    val announced = format ?: throw HostVoiceException(HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE)
                    val chunk = frame.chunk(announced) ?: throw HostVoiceException(HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE)
                    audioSeen = true
                    out.send(chunk)
                }
            }
        }

        private fun finalResponse(frame: JsonObject): SpeechAttempt {
            if ((frame["success"] as? JsonPrimitive)?.content == "true") return SpeechAttempt.DONE
            val code = HostVoiceErrorCode.fromWire((frame["error"] as? JsonPrimitive)?.content)
            if (code == HostVoiceErrorCode.BUSY && !audioSeen) return SpeechAttempt.BUSY
            throw HostVoiceException(code)
        }
    }

    private suspend fun <T> retryingBusy(block: suspend () -> T): T {
        repeat(busyAttempts - 1) {
            val outcome = runCatching { block() }
            val failure = outcome.exceptionOrNull() ?: return outcome.getOrThrow()
            if (failure !is HostVoiceException || failure.code != HostVoiceErrorCode.BUSY) throw failure
            delay(busyBackoff)
        }
        return block()
    }

    /** One request: its result, or a [HostVoiceException] with the host's code (or INTERNAL when the link failed). */
    private suspend fun call(method: String, params: JsonObject): JsonElement? {
        val response = runCatching { link.call(method, params) }.getOrElse { throw transportFailure(it) }
        if (!response.success) throw HostVoiceException(HostVoiceErrorCode.fromWire(response.error))
        return response.result
    }

    private fun <T> decode(
        serializer: KSerializer<T>,
        element: JsonElement?,
        failure: HostVoiceErrorCode = HostVoiceErrorCode.INTERNAL,
    ): T {
        val present = element ?: throw HostVoiceException(failure)
        return runCatching { json.decodeFromJsonElement(serializer, present) }.getOrElse { throw HostVoiceException(failure) }
    }

    private enum class SpeechAttempt { DONE, BUSY }

    private companion object {
        val DEFAULT_SPEECH_IDLE: Duration = 20.seconds
        val DEFAULT_BUSY_BACKOFF: Duration = 150.milliseconds
        const val DEFAULT_BUSY_ATTEMPTS = 8
        const val FRAME_BUFFER = 64
        const val RESPONSE_TYPE = "admin_rpc_response"
        val EMPTY = JsonObject(emptyMap())
        // As the admin_rpc readers do: unknown fields ignored, an unknown enum value read as its
        // default (a newer host's reachability code), null optionals omitted on write.
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        fun JsonObject.type(): String? = (this["type"] as? JsonPrimitive)?.content

        /** Cancellation passes through; a host voice failure keeps its code; anything else is the link failing. */
        fun transportFailure(cause: Throwable?): Throwable = when (cause) {
            is CancellationException, is HostVoiceException -> cause
            else -> HostVoiceException(HostVoiceErrorCode.INTERNAL)
        }
    }
}
