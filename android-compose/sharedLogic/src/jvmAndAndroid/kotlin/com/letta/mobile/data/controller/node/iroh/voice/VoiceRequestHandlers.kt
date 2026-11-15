package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.controller.node.iroh.AdminRpcConnection
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcStreamOutcome
import com.letta.mobile.data.controller.node.iroh.AdminRpcStreamedCall
import com.letta.mobile.data.controller.node.iroh.AdminRpcStreamedHandler
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import com.letta.mobile.data.voice.audio.pcm.WavStreamDecoder
import com.letta.mobile.data.voice.host.HOST_SPEECH_SAMPLE_FORMAT
import com.letta.mobile.data.voice.host.HostSpeechSummary
import com.letta.mobile.data.voice.host.HostTranscriptFormat
import com.letta.mobile.data.voice.host.HostTranscriptSegment
import com.letta.mobile.data.voice.host.HostTranscriptionResult
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import com.letta.mobile.data.voice.host.HostVoiceWire
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `voice.transcribe` and the streamed `voice.speech`. Each checks, in order: its params, that the
 * stored setup serves this kind, the shared grant for this payload, and the per-connection and host-wide
 * limits; only then does anything go to the server. Audio and text live in memory for the request only.
 */
internal class VoiceRequestHandlers(
    private val store: VoiceSetupStore,
    private val upstream: VoiceSpeechUpstream,
    private val inFlight: VoiceInFlight,
) {
    /** The setup revision whose server refused `verbose_json`; it gets plain `json` from then on. */
    @Volatile
    private var verboseRefusedAt: Long? = null

    /** One clip to text. */
    suspend fun transcribe(params: JsonObject?, context: AdminRpcRequestContext): JsonElement {
        val clip = VoiceParams.clip(params)
        val snapshot = store.snapshot() ?: throw VoiceRefusal(HostVoiceErrorCode.NOT_CONFIGURED)
        val model = snapshot.setup.transcriptionModel ?: throw VoiceRefusal(HostVoiceErrorCode.NOT_CONFIGURED)
        if (!snapshot.setup.grants.audioForTranscription) throw VoiceRefusal(HostVoiceErrorCode.CONSENT_REQUIRED)
        val slot = inFlight.tryAcquire(connectionKey(context), VoiceRequestKind.TRANSCRIPTION)
            ?: throw VoiceRefusal(HostVoiceErrorCode.BUSY)
        val result = try {
            transcript(snapshot, VoiceTranscriptionCall(clip.wav, model, clip.language ?: snapshot.setup.language, verbose = true))
        } finally {
            slot.release()
        }
        return voiceJson.encodeToJsonElement(HostTranscriptionResult.serializer(), result)
    }

    private suspend fun transcript(snapshot: VoiceSetupSnapshot, verbose: VoiceTranscriptionCall): HostTranscriptionResult {
        val askVerbose = verboseRefusedAt != snapshot.revision
        val first = if (askVerbose) upstreamCall { upstream.transcribe(snapshot.target, verbose) } else VoiceTranscriptionAnswer.FormatRefused
        val (answer, format) = when (first) {
            is VoiceTranscriptionAnswer.Transcript -> first to HostTranscriptFormat.VERBOSE_JSON
            VoiceTranscriptionAnswer.FormatRefused -> {
                val plain = VoiceTranscriptionCall(verbose.audio, verbose.model, verbose.language, verbose = false)
                val retried = upstreamCall { upstream.transcribe(snapshot.target, plain) }
                val transcript = retried as? VoiceTranscriptionAnswer.Transcript
                    ?: throw VoiceRefusal(HostVoiceErrorCode.UPSTREAM_REJECTED)
                verboseRefusedAt = snapshot.revision
                transcript to HostTranscriptFormat.JSON
            }
        }
        val segments = if (format == HostTranscriptFormat.JSON) emptyList() else answer.transcript.segments.map {
            HostTranscriptSegment(it.noSpeechProbability, it.averageLogProbability)
        }
        return HostTranscriptionResult(answer.transcript.text.value, format, segments)
    }

    /** The streamed synthesis handler; on any other channel the method is refused. */
    val speech: AdminRpcStreamedHandler = object : AdminRpcStreamedHandler {
        override val cancelledError: String = HostVoiceErrorCode.CANCELLED.wire

        override suspend fun serve(call: AdminRpcStreamedCall): AdminRpcStreamOutcome = try {
            synthesize(call)
        } catch (refusal: VoiceRefusal) {
            AdminRpcStreamOutcome.Failure(refusal.code.wire)
        }
    }

    private suspend fun synthesize(call: AdminRpcStreamedCall): AdminRpcStreamOutcome {
        val text = VoiceParams.speechText(call.params)
        val snapshot = store.snapshot() ?: throw VoiceRefusal(HostVoiceErrorCode.NOT_CONFIGURED)
        val model = snapshot.setup.speechModel ?: throw VoiceRefusal(HostVoiceErrorCode.NOT_CONFIGURED)
        val voice = snapshot.setup.voice ?: throw VoiceRefusal(HostVoiceErrorCode.NOT_CONFIGURED)
        if (!snapshot.setup.grants.textForSynthesis) throw VoiceRefusal(HostVoiceErrorCode.CONSENT_REQUIRED)
        val slot = inFlight.tryAcquire(connectionKey(call.context), VoiceRequestKind.SPEECH)
            ?: throw VoiceRefusal(HostVoiceErrorCode.BUSY)
        try {
            val stream = SpeechFrames(call)
            upstreamCall { upstream.speech(snapshot.target, VoiceSpeechCall(text, model, voice)) { stream.push(it) } }
            if (!stream.complete) throw VoiceRefusal(HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE)
            return AdminRpcStreamOutcome.Success(stream.summary())
        } finally {
            // Released before the exchange writes the final response, so the next request never races it.
            slot.release()
        }
    }

    /** The serving connection, so the per-connection limit holds across request streams. */
    private suspend fun connectionKey(context: AdminRpcRequestContext): Any =
        currentCoroutineContext()[AdminRpcConnection] ?: context.authenticatedEndpointId ?: Any()

    /** Decodes the server's WAVE stream and writes its format once, then each run of samples at once. */
    private class SpeechFrames(private val call: AdminRpcStreamedCall) {
        private val decoder = WavStreamDecoder()
        private var formatSent = false
        private var audioFrames = 0
        private var sampleFrames = 0L

        /** Whether the stream started and ended on a whole sample frame. */
        val complete: Boolean get() = decoder.started && decoder.pendingBytes == 0

        suspend fun push(bytes: ByteArray) {
            val chunk = try {
                decoder.push(bytes)
            } catch (refused: VoiceAudioException) {
                throw VoiceUpstreamFailure(HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE)
            }
            val format = decoder.format
            if (!formatSent && format != null) {
                call.emit(
                    buildJsonObject {
                        put("event", "format")
                        put("sample_rate_hz", format.sampleRateHz)
                        put("channels", format.channels)
                        put("sample_format", HOST_SPEECH_SAMPLE_FORMAT)
                    },
                )
                formatSent = true
            }
            chunk?.let { audio(it) }
        }

        /** Whole sample frames, at most 64 KiB of PCM per frame. */
        private suspend fun audio(chunk: PcmChunk) {
            val frameBytes = chunk.format.channels * BYTES_PER_SAMPLE
            val perFrame = HostVoiceWire.MAX_STREAM_AUDIO_BYTES / frameBytes * frameBytes
            val pcm = littleEndian(chunk.samples)
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + perFrame, pcm.size)
                call.emit(
                    buildJsonObject {
                        put("event", "audio")
                        put("pcm", Base64.encode(pcm, offset, end))
                    },
                )
                audioFrames++
                sampleFrames += (end - offset) / frameBytes
                offset = end
            }
        }

        fun summary(): JsonElement =
            voiceJson.encodeToJsonElement(HostSpeechSummary.serializer(), HostSpeechSummary(audioFrames, sampleFrames))

        private fun littleEndian(samples: ShortArray): ByteArray {
            val bytes = ByteArray(samples.size * BYTES_PER_SAMPLE)
            samples.forEachIndexed { index, sample ->
                bytes[index * BYTES_PER_SAMPLE] = (sample.toInt() and BYTE_MASK).toByte()
                bytes[index * BYTES_PER_SAMPLE + 1] = (sample.toInt() shr BITS_PER_BYTE).toByte()
            }
            return bytes
        }

        private companion object {
            const val BYTES_PER_SAMPLE = 2
            const val BYTE_MASK = 0xFF
            const val BITS_PER_BYTE = 8
        }
    }
}
