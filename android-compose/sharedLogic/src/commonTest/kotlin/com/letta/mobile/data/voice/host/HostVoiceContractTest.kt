package com.letta.mobile.data.voice.host

import com.letta.mobile.data.model.provider.wire.SecretWriteValue
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.SpeechLanguage
import com.letta.mobile.data.voice.SpeechModelName
import com.letta.mobile.data.voice.SpeechVoiceName
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.PcmFormat
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Decodes and re-encodes every case of the voice-endpoint v1 fixture through the client wire types. */
class HostVoiceContractTest {
    private val fixture = json.decodeFromString(VoiceFixture.serializer(), voiceEndpointContractText)

    @Test
    fun fixtureNamesEveryMethodWithItsCapability() {
        assertEquals(HostVoiceWire.CONTRACT_VERSION, fixture.version)
        assertEquals(HostVoiceWire.CAPABILITY, fixture.capability)
        val declared = fixture.methods.associate { it.method to it.requiredCapability } +
            fixture.streams.associate { it.method to it.requiredCapability }
        assertEquals(REQUIRED_CAPABILITIES, declared)
        assertEquals(HostVoiceWire.METHODS, declared.keys)
    }

    @Test
    fun everyFailureIsOneClosedCodeAndEchoesNothing() {
        val failures = fixture.methods.flatMap { block -> block.cases.map { it.expectedResponse to it.expectedCode } } +
            fixture.streams.flatMap { block -> block.cases.map { it.expectedResponse to it.expectedCode } }
        failures.filter { (response, _) -> !response.succeeded }.forEach { (response, expected) ->
            val error = response.getValue("error").jsonPrimitive.content
            assertEquals(expected, HostVoiceErrorCode.fromWire(error).wire, error)
            assertTrue(response.keys.all { it in FAILURE_KEYS }, "failure carries only the code: ${response.keys}")
        }
        assertEquals(HostVoiceErrorCode.INTERNAL, HostVoiceErrorCode.fromWire("speech server said something"))
    }

    @Test
    fun setupStatusesRoundTrip() {
        val statusMethods = setOf(HostVoiceWire.SETUP_GET, HostVoiceWire.SETUP_SET, HostVoiceWire.SETUP_CLEAR)
        val statuses = successes(statusMethods).map { roundTrip(HostVoiceSetupStatus.serializer(), it) }
        assertTrue(statuses.any { it.speech.available && !it.transcription.available }, "speaking-only setup is covered")
        val fields = statuses.flatMap { json.encodeToJsonElement(HostVoiceSetupStatus.serializer(), it).jsonObject.keys }.toSet()
        assertTrue("has_api_key" in fields && "api_key" !in fields, "status says whether a key exists, never the key")
    }

    @Test
    fun setupWritesEncodeExactlyAndCannotBeReadBack() {
        cases(HostVoiceWire.SETUP_SET).forEach { case ->
            val command = setupCommands.getValue(case.name)
            assertEquals(case.params, LettaJson.adminRpc.encodeToJsonElement(HostVoiceSetupCommand.serializer(), command), case.name)
            assertFalse(PLACEHOLDER_KEY in command.toString(), case.name)
            assertFailsWith<SerializationException> {
                LettaJson.adminRpc.decodeFromJsonElement(HostVoiceSetupCommand.serializer(), case.params)
            }
        }
        cases(HostVoiceWire.SETUP_CLEAR).forEach { roundTrip(HostVoiceClearParams.serializer(), it.params) }
    }

    @Test
    fun connectionChecksEncodeExactly() {
        cases(HostVoiceWire.SETUP_TEST).filter { it.params.isNotEmpty() }.forEach { case ->
            val key = case.params["api_key"]?.jsonPrimitive?.content?.let(::SecretWriteValue)
            val candidate = HostVoiceConnectionCandidate(SpeechEndpointUrl(case.params.string("base_url")), key)
            assertEquals(case.params, LettaJson.adminRpc.encodeToJsonElement(HostVoiceConnectionCandidate.serializer(), candidate))
            assertFalse(PLACEHOLDER_KEY in candidate.toString(), case.name)
        }
        successes(setOf(HostVoiceWire.SETUP_TEST)).forEach { roundTrip(HostVoiceTestResult.serializer(), it) }
        val catalogs = successes(setOf(HostVoiceWire.MODELS_LIST)).map { roundTrip(HostVoiceModels.serializer(), it) }
        assertEquals(setOf(HostVoiceListSource.UPSTREAM, HostVoiceListSource.CONFIGURED), catalogs.map { it.voicesSource }.toSet())
    }

    @Test
    fun transcriptionsCarryTheClipAndTheSegmentNumbers() {
        cases(HostVoiceWire.TRANSCRIBE).forEach { case ->
            val params = roundTrip(HostTranscriptionParams.serializer(), case.params)
            if (params.encoding == HostTranscriptionParams.WAV) {
                val clip = AudioClip(Base64.decode(params.audio), AudioEncoding.WAV)
                assertEquals(params, HostTranscriptionParams.of(clip, params.language), case.name)
            }
        }
        val transcripts = successes(setOf(HostVoiceWire.TRANSCRIBE)).map { roundTrip(HostTranscriptionResult.serializer(), it) }
        val verbose = transcripts.single { it.format == HostTranscriptFormat.VERBOSE_JSON }.toTranscript()
        assertEquals(listOf(0.02, 0.9), verbose.segments.map { it.noSpeechProbability })
        assertEquals(listOf(-0.25, -1.5), verbose.segments.map { it.averageLogProbability })
        assertTrue(transcripts.single { it.format == HostTranscriptFormat.JSON }.toTranscript().segments.isEmpty())
    }

    @Test
    fun speechStreamsAnnounceTheirFormatThenWholeSampleFrames() {
        fixture.streams.single().cases.forEach { case ->
            roundTrip(HostSpeechParams.serializer(), case.params)
            case.clientFrames.forEach { roundTrip(HostSpeechCancelFrame.serializer(), it.frame) }
            val frames = case.serverFrames.map { roundTrip(HostSpeechStreamFrame.serializer(), it) }
            assertEquals(frames.indices.toList(), frames.map { it.seq }, case.name)
            val format = frames.firstOrNull()?.let { assertNotNull(it.format(), case.name) }
            val chunks = frames.drop(1).map { assertNotNull(it.chunk(assertNotNull(format)), case.name) }
            if (case.expectedResponse.succeeded) {
                val summary = roundTrip(HostSpeechSummary.serializer(), case.expectedResponse.getValue("result"))
                assertEquals(PcmFormat(SPEECH_RATE_HZ, 1), format)
                assertEquals(summary.audioFrames, chunks.size)
                assertEquals(summary.sampleFrames, chunks.sumOf { it.samples.size.toLong() })
            }
        }
    }

    @Test
    fun aClipAndALanguageBuildTheTranscriptionParams() {
        val clip = AudioClip(byteArrayOf(0, 0), AudioEncoding.WAV)
        val params = HostTranscriptionParams.of(clip, SpeechLanguage("en").value)
        assertEquals("AAA=", params.audio)
        assertFalse("AAA=" in params.toString())
    }

    private fun cases(method: String): List<VoiceCase> = fixture.methods.single { it.method == method }.cases

    private fun successes(methods: Set<String>): List<JsonElement> = fixture.methods.filter { it.method in methods }
        .flatMap { it.cases }.filter { it.expectedResponse.succeeded }.map { it.expectedResponse.getValue("result") }

    private fun <T> roundTrip(serializer: KSerializer<T>, element: JsonElement): T {
        val value = json.decodeFromJsonElement(serializer, element)
        assertEquals(element, json.encodeToJsonElement(serializer, value))
        return value
    }

    @Serializable
    private data class VoiceFixture(
        val version: Int,
        val capability: String,
        val methods: List<VoiceMethod>,
        val streams: List<VoiceStream>,
    )

    @Serializable
    private data class VoiceMethod(
        val method: String,
        @SerialName("required_capability") val requiredCapability: String,
        val cases: List<VoiceCase>,
    )

    @Serializable
    private data class VoiceCase(
        val name: String,
        val params: JsonObject,
        @SerialName("expected_response") val expectedResponse: JsonObject,
        @SerialName("expected_code") val expectedCode: String? = null,
    )

    @Serializable
    private data class VoiceStream(
        val method: String,
        @SerialName("required_capability") val requiredCapability: String,
        val cases: List<VoiceStreamCase>,
    )

    @Serializable
    private data class VoiceStreamCase(
        val name: String,
        val params: JsonObject,
        @SerialName("client_frames") val clientFrames: List<VoiceClientFrame>,
        @SerialName("server_frames") val serverFrames: List<JsonObject>,
        @SerialName("expected_response") val expectedResponse: JsonObject,
        @SerialName("expected_code") val expectedCode: String? = null,
    )

    @Serializable
    private data class VoiceClientFrame(
        @SerialName("after_seq") val afterSeq: Int,
        val frame: JsonObject,
    )

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        const val PLACEHOLDER_KEY = "placeholder-key-not-real"
        const val SPEECH_RATE_HZ = 24_000
        const val CHAT_READ = "chat.read"
        const val ADMIN = "admin.full"
        val FAILURE_KEYS = setOf("type", "request_id", "success", "error", "capability")

        val REQUIRED_CAPABILITIES = mapOf(
            HostVoiceWire.SETUP_GET to CHAT_READ,
            HostVoiceWire.MODELS_LIST to CHAT_READ,
            HostVoiceWire.SETUP_SET to ADMIN,
            HostVoiceWire.SETUP_CLEAR to ADMIN,
            HostVoiceWire.SETUP_TEST to ADMIN,
            HostVoiceWire.TRANSCRIBE to "chat.send",
            HostVoiceWire.SPEECH to "chat.send",
        )

        val JsonObject.succeeded: Boolean get() = getValue("success").jsonPrimitive.content == "true"

        fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

        private val primary = SpeechEndpointUrl("https://speech.example.invalid")
        private val tts = SpeechModelName("tts-model-a")
        private val voiceA = SpeechVoiceName("voice-a")
        private val speakOnly = HostVoiceGrants(audioForTranscription = false, textForSynthesis = true)
        private val both = HostVoiceGrants(audioForTranscription = true, textForSynthesis = true)

        private fun newKey() = HostVoiceKeyChange.Replace(SecretWriteValue(PLACEHOLDER_KEY))

        private fun speaking(url: SpeechEndpointUrl) = HostVoiceEndpointChoice(url, speechModel = tts, voice = voiceA)

        val setupCommands: Map<String, HostVoiceSetupCommand> = mapOf(
            "first setup with key and both grants" to HostVoiceSetupCommand(
                HostVoiceEndpointChoice(primary, SpeechModelName("stt-model-a"), tts, voiceA), newKey(), both,
            ),
            "same origin keeps the stored key" to HostVoiceSetupCommand(
                HostVoiceEndpointChoice(primary, SpeechModelName("stt-model-a"), tts, SpeechVoiceName("voice-b"), SpeechLanguage("en")),
                HostVoiceKeyChange.Keep, both, expectedRevision = "rev-1",
            ),
            "new origin never inherits the key" to HostVoiceSetupCommand(
                speaking(SpeechEndpointUrl("https://other-speech.example.invalid")), grants = speakOnly,
            ),
            "key removed" to HostVoiceSetupCommand(
                speaking(SpeechEndpointUrl("http://127.0.0.1:8880")), HostVoiceKeyChange.Remove, speakOnly,
            ),
            "key over plain http to another machine" to HostVoiceSetupCommand(
                speaking(SpeechEndpointUrl("http://speech.example.invalid")), newKey(), speakOnly,
            ),
            "address with a query" to HostVoiceSetupCommand(
                speaking(SpeechEndpointUrl("https://speech.example.invalid/?token=x")), grants = speakOnly,
            ),
            "stale revision" to HostVoiceSetupCommand(speaking(primary), grants = speakOnly, expectedRevision = "rev-4"),
            "chat pairing may not write" to HostVoiceSetupCommand(speaking(primary), newKey(), speakOnly),
        )
    }
}
