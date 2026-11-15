package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.controller.node.iroh.stream.AdminRpcConnection
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.stream.AdminRpcStreamOutcome
import com.letta.mobile.data.controller.node.iroh.stream.AdminRpcStreamedCall
import com.letta.mobile.data.controller.node.iroh.stream.AdminRpcStreamedHandler
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
        val snapshot = configured()
        val model = snapshot.setup.transcriptionModel ?: throw VoiceRefusal(HostVoiceErrorCode.NOT_CONFIGURED)
        requireGrant(snapshot.setup.grants.audioForTranscription)
        val call = VoiceTranscriptionCall(clip.wav, model, clip.language ?: snapshot.setup.language, verbose = true)
        val result = holding(connectionKey(context), VoiceRequestKind.TRANSCRIPTION) { transcript(snapshot, call) }
        return voiceJson.encodeToJsonElement(HostTranscriptionResult.serializer(), result)
    }

    /** Asks for `verbose_json` unless this setup's server already refused it, then falls back to `json`. */
    private suspend fun transcript(snapshot: VoiceSetupSnapshot, verbose: VoiceTranscriptionCall): HostTranscriptionResult {
        if (verboseRefusedAt != snapshot.revision) {
            val first = upstreamCall { upstream.transcribe(snapshot.target, verbose) }
            if (first is VoiceTranscriptionAnswer.Heard) return result(first, HostTranscriptFormat.VERBOSE_JSON)
        }
        val plain = VoiceTranscriptionCall(verbose.audio, verbose.model, verbose.language, verbose = false)
        val heard = upstreamCall { upstream.transcribe(snapshot.target, plain) } as? VoiceTranscriptionAnswer.Heard
            ?: throw VoiceRefusal(HostVoiceErrorCode.UPSTREAM_REJECTED)
        verboseRefusedAt = snapshot.revision
        return result(heard, HostTranscriptFormat.JSON)
    }

    /** The wire result: the text and, for `verbose_json`, each segment's numbers (never its text). */
    private fun result(heard: VoiceTranscriptionAnswer.Heard, format: HostTranscriptFormat): HostTranscriptionResult {
        val segments = heard.transcript.segments.takeIf { format == HostTranscriptFormat.VERBOSE_JSON }.orEmpty()
        return HostTranscriptionResult(
            heard.transcript.text.value,
            format,
            segments.map { HostTranscriptSegment(it.noSpeechProbability, it.averageLogProbability) },
        )
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
        val snapshot = configured()
        val request = speechCall(snapshot.setup, text)
        requireGrant(snapshot.setup.grants.textForSynthesis)
        // The slot is released before the exchange writes the final response, so the next request never races it.
        return holding(connectionKey(null), VoiceRequestKind.SPEECH) {
            val stream = SpeechFrames(call)
            upstreamCall { upstream.speech(snapshot.target, request) { stream.push(it) } }
            if (!stream.complete) throw VoiceRefusal(HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE)
            AdminRpcStreamOutcome.Success(stream.summary())
        }
    }

    private suspend fun configured(): VoiceSetupSnapshot = store.snapshot() ?: throw VoiceRefusal(HostVoiceErrorCode.NOT_CONFIGURED)

    private fun speechCall(setup: StoredVoiceSetup, text: String): VoiceSpeechCall {
        val model = setup.speechModel ?: throw VoiceRefusal(HostVoiceErrorCode.NOT_CONFIGURED)
        val voice = setup.voice ?: throw VoiceRefusal(HostVoiceErrorCode.NOT_CONFIGURED)
        return VoiceSpeechCall(text, model, voice)
    }

    private fun requireGrant(granted: Boolean) {
        if (!granted) throw VoiceRefusal(HostVoiceErrorCode.CONSENT_REQUIRED)
    }

    /** Runs [work] holding the [kind] slot of [connection], refused with `voice.busy` when it is taken. */
    private suspend fun <T> holding(connection: Any, kind: VoiceRequestKind, work: suspend () -> T): T {
        val slot = inFlight.tryAcquire(connection, kind) ?: throw VoiceRefusal(HostVoiceErrorCode.BUSY)
        try {
            return work()
        } finally {
            slot.release()
        }
    }

    /** The serving connection, so the per-connection limit holds across request streams. */
    private suspend fun connectionKey(context: AdminRpcRequestContext?): Any =
        currentCoroutineContext()[AdminRpcConnection] ?: context?.authenticatedEndpointId ?: Any()

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
