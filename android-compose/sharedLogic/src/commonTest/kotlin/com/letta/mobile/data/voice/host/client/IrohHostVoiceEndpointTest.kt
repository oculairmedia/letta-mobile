package com.letta.mobile.data.voice.host.client

import com.letta.mobile.data.rpcwire.SecretWriteValue
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.SpeechLanguage
import com.letta.mobile.data.voice.SpeechModelName
import com.letta.mobile.data.voice.SpeechVoiceName
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.audio.PcmFormat
import com.letta.mobile.data.voice.audio.pcm.PcmAudio
import com.letta.mobile.data.voice.audio.pcm.WavCodec
import com.letta.mobile.data.voice.endpoint.SpeechEndpointException
import com.letta.mobile.data.voice.host.HostVoiceConnectionCandidate
import com.letta.mobile.data.voice.host.HostVoiceEndpointChoice
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import com.letta.mobile.data.voice.host.HostVoiceException
import com.letta.mobile.data.voice.host.HostVoiceGrants
import com.letta.mobile.data.voice.host.HostVoiceKeyChange
import com.letta.mobile.data.voice.host.HostVoiceSetupCommand
import com.letta.mobile.data.voice.host.HostVoiceWire
import com.letta.mobile.data.voice.tone.ToneCue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Iroh client of the voice-endpoint contract against a fake host that answers with the
 * contract's own fixtures: what it sends, how it reads every answer, streamed speech and its
 * cancellation, and how failures map to the closed codes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohHostVoiceEndpointTest {
    private val link = FakeHostVoiceLink()
    private val endpoint = IrohHostVoiceEndpoint(link)

    private suspend fun expectCode(case: VoiceCase, block: suspend () -> Unit) {
        val failure = assertFailsWith<HostVoiceException>(case.toString()) { block() }
        assertEquals(HostVoiceErrorCode.fromWire(case.error), failure.code, case.toString())
    }

    @Test
    fun setupReadsSendNothingAndReadEveryFixtureAnswer() = runTest {
        VoiceFixtures.of(HostVoiceWire.SETUP_GET).forEach { case ->
            link.answer = { _, _ -> reply(case.response) }
            if (case.succeeded) {
                val status = endpoint.status()
                val result = case.response.getValue("result") as JsonObject
                assertEquals((result.getValue("configured") as JsonPrimitive).content.toBoolean(), status.configured, case.toString())
            } else {
                val failure = assertFailsWith<HostVoiceException> { endpoint.status() }
                assertEquals(HostVoiceErrorCode.fromWire(case.error), failure.code, case.toString())
            }
        }
        assertTrue(link.calls.all { it.first == HostVoiceWire.SETUP_GET && it.second.isEmpty() })
    }

    @Test
    fun modelsChecksAndClearsSendTheFixtureParamsAndReadTheirAnswers() = runTest {
        VoiceFixtures.of(HostVoiceWire.MODELS_LIST).forEach { case ->
            link.answer = { _, _ -> reply(case.response) }
            if (case.succeeded) endpoint.models() else expectCode(case) { endpoint.models() }
        }
        VoiceFixtures.of(HostVoiceWire.SETUP_TEST).forEach { case ->
            link.calls.clear()
            link.answer = { _, _ -> reply(case.response) }
            val candidate = case.params["base_url"]?.let { url ->
                val key = case.params["api_key"]?.jsonPrimitive?.content?.let(::SecretWriteValue)
                HostVoiceConnectionCandidate(SpeechEndpointUrl(url.jsonPrimitive.content), key)
            }
            if (case.succeeded) endpoint.testConnection(candidate) else expectCode(case) { endpoint.testConnection(candidate) }
            assertEquals(case.params, link.calls.single().second, case.toString())
        }
        VoiceFixtures.of(HostVoiceWire.SETUP_CLEAR).forEach { case ->
            link.calls.clear()
            link.answer = { _, _ -> reply(case.response) }
            val revision = case.params["expected_revision"]?.jsonPrimitive?.content
            if (case.succeeded) {
                assertTrue(!endpoint.clearSetup(revision).configured)
            } else {
                expectCode(case) { endpoint.clearSetup(revision) }
            }
            assertEquals(case.params, link.calls.single().second, case.toString())
        }
    }

    @Test
    fun aSetupWriteCarriesTheKeyOnceAndReturnsTheNewStatus() = runTest {
        val case = VoiceFixtures.case(HostVoiceWire.SETUP_SET, "first setup with key and both grants")
        link.answer = { _, _ -> reply(case.response) }
        val choice = HostVoiceEndpointChoice(
            SpeechEndpointUrl("https://speech.example.invalid"),
            SpeechModelName("stt-model-a"),
            SpeechModelName("tts-model-a"),
            SpeechVoiceName("voice-a"),
        )
        val command = HostVoiceSetupCommand(
            choice,
            HostVoiceKeyChange.Replace(SecretWriteValue("placeholder-key-not-real")),
            HostVoiceGrants(audioForTranscription = true, textForSynthesis = true),
        )

        val status = endpoint.writeSetup(command)

        assertEquals(case.params, link.calls.single().second)
        assertTrue(status.configured && status.hasApiKey)
        VoiceFixtures.of(HostVoiceWire.SETUP_SET).filterNot { it.succeeded }.forEach { failing ->
            link.answer = { _, _ -> reply(failing.response) }
            expectCode(failing) { endpoint.writeSetup(command) }
        }
    }

    @Test
    fun transcriptionsSendTheClipAndReadTheTranscriptOrTheCode() = runTest {
        VoiceFixtures.of(HostVoiceWire.TRANSCRIBE).forEach { case ->
            link.calls.clear()
            link.answer = { _, _ -> reply(case.response) }
            val clip = AudioClip(Base64.decode(case.params.getValue("audio").jsonPrimitive.content), encodingOf(case))
            val language = case.params["language"]?.jsonPrimitive?.content?.let(::SpeechLanguage)
            if (case.succeeded) {
                val transcript = endpoint.transcribe(clip, language)
                val text = (case.response.getValue("result") as JsonObject).getValue("text").jsonPrimitive.content
                assertEquals(text, transcript.text.value, case.toString())
                assertEquals(case.params, link.calls.single().second, case.toString())
            } else {
                expectCode(case) { endpoint.transcribe(clip, language) }
            }
        }
    }

    @Test
    fun compressedAudioIsRefusedBeforeAnythingIsSent() = runTest {
        val clip = AudioClip(byteArrayOf(0, 0, 0, 0), AudioEncoding.MP3)
        val failure = assertFailsWith<HostVoiceException> { endpoint.transcribe(clip) }
        assertEquals(HostVoiceErrorCode.INVALID_REQUEST, failure.code)
        assertTrue(link.calls.isEmpty())
    }

    @Test
    fun aClipOverTheLimitsIsSentInPiecesWithinThemAndTheWordsJoined() = runTest {
        val rate = 16_000
        val samples = ShortArray(rate * 45) { (it % 100).toShort() }
        val clip = AudioClip(WavCodec.encode(PcmAudio(samples, PcmFormat(rate, 1))), AudioEncoding.WAV)
        var piece = 0
        link.answer = { _, _ -> HostVoiceReply(true, transcription("part ${++piece}")) }

        val transcript = endpoint.transcribe(clip)

        assertEquals(2, link.calls.size)
        link.calls.forEach { (_, params) ->
            val bytes = Base64.decode(params.getValue("audio").jsonPrimitive.content)
            assertTrue(bytes.size <= HostVoiceWire.MAX_TRANSCRIPTION_AUDIO_BYTES)
            assertTrue((bytes.size - WAV_HEADER) / 2 <= rate * 30)
        }
        assertEquals("part 1 part 2", transcript.text.value)
    }

    @Test
    fun aBusyHostIsAskedAgainAndAPersistentlyBusyOneFailsAsBusy() = runTest {
        val ok = VoiceFixtures.case(HostVoiceWire.TRANSCRIBE, "verbose answer with segment numbers")
        val busy = VoiceFixtures.case(HostVoiceWire.TRANSCRIBE, "one transcription already in flight")
        val clip = AudioClip(Base64.decode(ok.params.getValue("audio").jsonPrimitive.content), AudioEncoding.WAV)
        var answers = 0
        link.answer = { _, _ -> reply(if (answers++ == 0) busy.response else ok.response) }
        endpoint.transcribe(clip)
        assertEquals(2, link.calls.size)

        link.calls.clear()
        link.answer = { _, _ -> reply(busy.response) }
        val failure = assertFailsWith<HostVoiceException> { endpoint.transcribe(clip) }
        assertEquals(HostVoiceErrorCode.BUSY, failure.code)
        assertEquals(8, link.calls.size, "the default attempts")
    }

    @Test
    fun linkFailuresRefusalsAndOldHostsMapToTheirCodes() = runTest {
        link.answer = { _, _ -> error("connection reset") }
        assertEquals(HostVoiceErrorCode.INTERNAL, assertFailsWith<HostVoiceException> { endpoint.status() }.code)
        link.answer = { _, _ -> HostVoiceReply(false, error = "forbidden") }
        assertEquals(HostVoiceErrorCode.NOT_AUTHORIZED, assertFailsWith<HostVoiceException> { endpoint.status() }.code)
        link.answer = { _, _ -> HostVoiceReply(false, error = "Unknown method: voice.setup.get") }
        assertEquals(HostVoiceErrorCode.UNSUPPORTED, assertFailsWith<HostVoiceException> { endpoint.status() }.code)
        link.answer = { _, _ -> HostVoiceReply(true, JsonPrimitive("not a status")) }
        assertEquals(HostVoiceErrorCode.INTERNAL, assertFailsWith<HostVoiceException> { endpoint.status() }.code)
    }

    @Test
    fun aToneGoesToTheHostAsInstructionsAndNoToneSendsNone() = runTest {
        val case = VoiceFixtures.case(HostVoiceWire.SPEECH, "streamed reply")
        link.frames = { case.stream() }

        endpoint.synthesize(VoiceText("Placeholder reply."), ToneCue("excited")).toList()
        endpoint.synthesize(VoiceText("Placeholder reply."), null).toList()

        val (toned, plain) = link.calls.map { it.second }
        assertEquals(JsonPrimitive("excited"), toned["instructions"])
        assertEquals(JsonPrimitive("Placeholder reply."), toned["text"])
        assertEquals(null, plain["instructions"])
    }

    @Test
    fun aStreamedReplyBecomesPcmInTheAnnouncedFormat() = runTest {
        val case = VoiceFixtures.case(HostVoiceWire.SPEECH, "streamed reply")
        link.frames = { case.stream() }

        val chunks = endpoint.synthesize(VoiceText("Placeholder reply.")).toList()

        assertEquals(listOf(PcmFormat(24_000, 1), PcmFormat(24_000, 1)), chunks.map(PcmChunk::format))
        assertEquals(6, chunks.sumOf { it.samples.size })
        assertEquals(case.params, link.calls.single().second)
    }

    @Test
    fun everyFailedSpeechCaseFailsWithItsCode() = runTest {
        VoiceFixtures.of(HostVoiceWire.SPEECH).filterNot { it.succeeded || it.error == "voice.busy" }.forEach { case ->
            link.frames = { case.stream() }
            expectCode(case) { endpoint.synthesize(VoiceText("Placeholder reply.")).toList() }
        }
    }

    @Test
    fun stoppingTheCollectionCancelsTheRequestOnTheHost() = runTest {
        val case = VoiceFixtures.case(HostVoiceWire.SPEECH, "streamed reply")
        var cancelled = false
        link.frames = {
            flow {
                case.serverFrames.forEach { emit(it) }
                awaitCancellation()
            }.onCompletion { cause -> cancelled = cause != null }
        }

        endpoint.synthesize(VoiceText("Placeholder reply.")).first()

        assertTrue(cancelled, "the request stream is cancelled, which writes admin_rpc_cancel and finishes the send half")
    }

    @Test
    fun aSilentStreamTimesOutAndIsCancelled() = runTest {
        val format = VoiceFixtures.case(HostVoiceWire.SPEECH, "streamed reply").serverFrames.first()
        var cancelled = false
        link.frames = {
            flow {
                emit(format)
                awaitCancellation()
            }.onCompletion { cause -> cancelled = cause != null }
        }

        val failure = assertFailsWith<HostVoiceException> { endpoint.synthesize(VoiceText("hi")).toList() }

        assertEquals(HostVoiceErrorCode.UPSTREAM_TIMEOUT, failure.code)
        assertTrue(cancelled)
    }

    @Test
    fun outOfOrderOrUnannouncedAudioIsABadResponse() = runTest {
        val frames = VoiceFixtures.case(HostVoiceWire.SPEECH, "streamed reply").serverFrames
        val done = VoiceFixtures.case(HostVoiceWire.SPEECH, "streamed reply").response
        link.frames = { flowOf(frames[1], done) }
        val unannounced = assertFailsWith<HostVoiceException> { endpoint.synthesize(VoiceText("hi")).toList() }
        assertEquals(HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE, unannounced.code)
        link.frames = { flowOf(frames[0], frames[2], done) }
        val gap = assertFailsWith<HostVoiceException> { endpoint.synthesize(VoiceText("hi")).toList() }
        assertEquals(HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE, gap.code)
    }

    @Test
    fun aBusyRefusalBeforeAudioIsRetried() = runTest {
        val case = VoiceFixtures.case(HostVoiceWire.SPEECH, "streamed reply")
        val busy = buildJsonObject {
            put("type", "admin_rpc_response")
            put("request_id", "contract")
            put("success", false)
            put("error", "voice.busy")
        }
        var attempts = 0
        link.frames = { if (attempts++ == 0) flowOf(busy) else case.stream() }

        val chunks = endpoint.synthesize(VoiceText("Placeholder reply.")).toList()

        assertEquals(2, attempts)
        assertEquals(2, chunks.size)
    }

    @Test
    fun theHostTranscriberChecksThisDevicesAcknowledgementAndMapsHostCodes() = runTest {
        val case = VoiceFixtures.case(HostVoiceWire.TRANSCRIBE, "audio grant missing")
        val clip = AudioClip(Base64.decode(case.params.getValue("audio").jsonPrimitive.content), AudioEncoding.WAV)
        var acknowledged = false
        val transcriber = HostSpeechTranscriber(endpoint) { acknowledged }

        val refused = assertFailsWith<SpeechEndpointException> { transcriber.transcribe(clip) }
        assertEquals(VoiceErrorCode.PERMISSION_REQUIRED, refused.code)
        assertTrue(link.calls.isEmpty(), "nothing is sent before this device acknowledged it")

        acknowledged = true
        link.answer = { _, _ -> reply(case.response) }
        val failure = assertFailsWith<SpeechEndpointException> { transcriber.transcribe(clip) }
        assertEquals(HostVoiceErrorCode.CONSENT_REQUIRED.voiceCode, failure.code)
    }

    @Test
    fun theHostSynthesizerMapsHostCodesToTheLoopsCodes() = runTest {
        val case = VoiceFixtures.of(HostVoiceWire.SPEECH).first { !it.succeeded && it.serverFrames.isEmpty() && it.error != "voice.busy" }
        link.frames = { case.stream() }
        val failure = assertFailsWith<SpeechEndpointException> { HostSpeechSynthesizer(endpoint).stream(VoiceText("hi")).toList() }
        assertEquals(HostVoiceErrorCode.fromWire(case.error).voiceCode, failure.code)
    }

    private fun encodingOf(case: VoiceCase): AudioEncoding =
        AudioEncoding.entries.first { it.wireName == case.params.getValue("encoding").jsonPrimitive.content }

    private fun transcription(text: String) = buildJsonObject {
        put("text", text)
        put("format", "json")
    }

    private companion object {
        const val WAV_HEADER = 44
    }
}
