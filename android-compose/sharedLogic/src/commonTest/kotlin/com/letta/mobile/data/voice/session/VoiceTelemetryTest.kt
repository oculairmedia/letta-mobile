package com.letta.mobile.data.voice.session

import com.letta.mobile.data.voice.SpeechSkipReason
import com.letta.mobile.data.voice.SpeechStopReason
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.endpoint.SpeechEndpointException
import com.letta.mobile.data.voice.endpoint.SpeechOperation
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.SpeechSegmentId
import com.letta.mobile.data.voice.engine.VoiceCancelOutcome
import com.letta.mobile.data.voice.engine.VoiceMouth
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import com.letta.mobile.data.voice.engine.VoiceReply
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import com.letta.mobile.data.voice.engine.VoiceResponder
import com.letta.mobile.data.voice.responder.ClientMessageId
import com.letta.mobile.data.voice.responder.SentUtterance
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.responder.VoiceChatState
import com.letta.mobile.data.voice.responder.VoiceRunId
import com.letta.mobile.data.voice.vad.TurnDiscardReason
import com.letta.mobile.data.voice.vad.ear.EarTurnReport
import com.letta.mobile.data.voice.vad.ear.SpeechTranscriber
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class VoiceTelemetryTest {
    private val recorder = TelemetryRecorder()
    private var installed: AutoCloseable? = null

    @BeforeTest
    fun install() {
        installed = Telemetry.installRecorder(recorder)
    }

    @AfterTest
    fun uninstall() {
        installed?.close()
    }

    private val clock = TestTimeSource()
    private val telemetry = VoiceTelemetry(clock)

    private fun voiceEvents(): List<Telemetry.Event> = recorder.events.value.filter { it.tag == "VoiceMode" }.reversed()

    private fun named(name: String): Telemetry.Event = voiceEvents().single { it.name == name }

    private fun attr(event: Telemetry.Event, key: String): String = event.attrs.getValue(key).toString()

    /** No event carries any of the words spoken or answered. */
    private fun assertNoText() {
        val values = voiceEvents().flatMap { event -> event.attrs.values.map { it.toString() } }
        assertTrue(values.none { SECRET in it || REPLY in it }, "$values")
    }

    private class FakeSession : VoiceChatSession {
        override val state = MutableStateFlow(VoiceChatState())

        override suspend fun send(text: VoiceText) = SentUtterance(ClientMessageId("otid-1"))

        override fun stopRun() = Unit
    }

    @Test
    fun aSendAndItsAttributionAreReportedByIdentityWithoutText() = runTest {
        val fake = FakeSession()
        val session = TelemetryVoiceChatSession(fake, telemetry)
        session.send(VoiceText(SECRET))
        fake.state.value = VoiceChatState(startedRuns = mapOf(ClientMessageId("otid-1") to setOf(VoiceRunId("local-run-57"))))
        session.state.first { it.startedRuns.isNotEmpty() }
        session.state.first()

        assertEquals("otid-1", attr(named("utterance.sent"), "clientMessageId"))
        assertEquals(SECRET.length.toString(), attr(named("utterance.sent"), "characters"))
        assertEquals("local-run-57", attr(named("reply.attributed"), "runIds"))
        assertNoText()
    }

    @Test
    fun anUnattributedReplyIsReportedWithItsReasonAndMessageId() = runTest {
        val responder = TelemetryResponder(
            object : VoiceResponder {
                override fun respond(utterance: VoiceUtterance): VoiceReply = object : VoiceReply {
                    override val events: Flow<VoiceReplyEvent> =
                        flowOf(VoiceReplyEvent.Failed(VoiceError(VoiceErrorCode.UNATTRIBUTED, VoicePort.RESPONDER)))

                    override suspend fun cancel() = VoiceCancelOutcome.ALREADY_FINISHED
                }
            },
            telemetry,
        ) { ClientMessageId("otid-1") }
        responder.respond(VoiceUtterance(VoiceText(SECRET))).events.toList()

        val event = named("reply.unattributed")
        assertEquals("UNATTRIBUTED", attr(event, "reason"))
        assertEquals("otid-1", attr(event, "clientMessageId"))
        assertNoText()
    }

    @Test
    fun aCompletedReplyReportsOnlyItsCharacterCount() = runTest {
        val responder = TelemetryResponder(
            object : VoiceResponder {
                override fun respond(utterance: VoiceUtterance): VoiceReply = object : VoiceReply {
                    override val events: Flow<VoiceReplyEvent> =
                        flowOf(VoiceReplyEvent.Text(VoiceText(REPLY)), VoiceReplyEvent.Completed)

                    override suspend fun cancel() = VoiceCancelOutcome.ALREADY_FINISHED
                }
            },
            telemetry,
        ) { ClientMessageId("otid-1") }
        responder.respond(VoiceUtterance(VoiceText(SECRET))).events.toList()

        assertEquals(REPLY.length.toString(), attr(named("reply.completed"), "characters"))
        assertNoText()
    }

    @Test
    fun speechRequestsAndFailuresAreReportedByCountAndKind() = runTest {
        val failed = VoiceMouthEvent.Failed(VoiceError(VoiceErrorCode.BAD_RESPONSE, VoicePort.MOUTH), SpeechSegmentId(3))
        val mouth = TelemetryMouth(
            object : VoiceMouth {
                override val events: Flow<VoiceMouthEvent> = flowOf(VoiceMouthEvent.Started(SpeechSegmentId(3)), failed)

                override fun speak(segment: SpeechSegment) = Unit

                override fun flush() = Unit

                override suspend fun stop() = Unit
            },
            telemetry,
        )
        mouth.speak(SpeechSegment(SpeechSegmentId(3), VoiceText(REPLY)))
        mouth.events.toList()

        assertEquals(REPLY.length.toString(), attr(named("speech.requested"), "characters"))
        assertEquals("3", attr(named("speech.started"), "segment"))
        assertEquals("BAD_RESPONSE", attr(named("speech.failed"), "kind"))
        assertNoText()
    }

    @Test
    fun aSegmentThatPlaysToItsEndIsReportedAsEnded() = runTest {
        val mouth = TelemetryMouth(
            object : VoiceMouth {
                override val events: Flow<VoiceMouthEvent> =
                    flowOf(VoiceMouthEvent.Started(SpeechSegmentId(2)), VoiceMouthEvent.Finished(SpeechSegmentId(2)))

                override fun speak(segment: SpeechSegment) = Unit

                override fun flush() = Unit

                override suspend fun stop() = Unit
            },
            telemetry,
        )
        mouth.events.toList()
        assertEquals("2", attr(named("speech.ended"), "segment"))
    }

    @Test
    fun theStartSaysWhetherTheMicrophoneIsEchoCancelledAndAStopHowLongTheSpeakerTookToFallSilent() {
        telemetry.started(echoCancelled = false, bargeInByVoice = false)
        telemetry.loop.speechStopped(SpeechStopReason.BUTTON, 42.milliseconds)
        telemetry.loop.speechStopped(SpeechStopReason.MODE_STOPPED, 7.milliseconds)

        val started = named("mode.started")
        assertEquals("false", attr(started, "echoCancelled"))
        assertEquals("false", attr(started, "bargeInByVoice"))
        val stops = voiceEvents().filter { it.name == "speech.stopped" }
        assertEquals(listOf("BUTTON", "MODE_STOPPED"), stops.map { attr(it, "reason") })
        assertEquals(listOf("42", "7"), stops.map { attr(it, "silentMs") })
    }

    @Test
    fun endpointErrorsAreReportedWithKindAndStatusAndRethrown() = runTest {
        val transcriber = TelemetryTranscriber(
            SpeechTranscriber { throw SpeechEndpointException(SpeechOperation.TRANSCRIPTION, VoiceErrorCode.REJECTED, 422) },
            telemetry,
        )
        assertFailsWith<SpeechEndpointException> { transcriber.transcribe(AudioClip(ByteArray(4), AudioEncoding.WAV)) }

        val event = named("endpoint.error")
        assertEquals("REJECTED", attr(event, "kind"))
        assertEquals("422", attr(event, "httpStatus"))
        assertEquals("TRANSCRIPTION", attr(event, "operation"))
    }

    @Test
    fun earDecisionsCarryLevelsAndReasons() {
        val ear = VoiceEarTelemetry(telemetry)
        ear.turnDiscarded(EarTurnReport(250.milliseconds, -31.4, -52.0), TurnDiscardReason.TOO_LITTLE_VOICE)
        ear.transcriptAccepted(12)

        val discarded = named("turn.discarded")
        assertEquals("TOO_LITTLE_VOICE", attr(discarded, "reason"))
        assertEquals("250", attr(discarded, "voicedMs"))
        assertEquals("-31", attr(discarded, "peakDb"))
        assertEquals("-52", attr(discarded, "floorDb"))
        assertEquals("12", attr(named("transcript.accepted"), "characters"))
    }

    @Test
    fun everyEventIsTimedFromTheSessionStartOnTheInjectedClock() {
        telemetry.event("before.start")
        clock += 5.seconds
        telemetry.started()
        clock += 1250.milliseconds
        telemetry.loop.speechSkipped(SpeechSkipReason.BARGE_IN, 2)
        clock += 750.milliseconds
        telemetry.stopped("stopRequested")

        assertEquals("null", attr(named("before.start"), "tMs"))
        assertEquals("0", attr(named("mode.started"), "tMs"))
        val skipped = named("speech.skipped")
        assertEquals("1250", attr(skipped, "tMs"))
        assertEquals("BARGE_IN", attr(skipped, "reason"))
        assertEquals("2", attr(skipped, "segments"))
        assertEquals("2000", attr(named("mode.stopped"), "tMs"))
    }

    @Test
    fun aQueuedSendAndAStopAreReportedByIdentity() = runTest {
        val fake = FakeSession()
        val session = TelemetryVoiceChatSession(fake, telemetry)
        val own = ClientMessageId("otid-1")
        fake.state.value = VoiceChatState(queued = setOf(own))
        session.send(VoiceText(SECRET))
        val run = VoiceRunId("local-run-401")
        fake.state.value = VoiceChatState(startedRuns = mapOf(own to setOf(run)), activeRuns = setOf(run, VoiceRunId("theirs")))
        session.state.first { it.activeRuns.isNotEmpty() }
        session.stopRun()
        fake.state.value = fake.state.value.copy(activeRuns = setOf(VoiceRunId("theirs")))
        session.state.first { run !in it.activeRuns }

        assertEquals("otid-1", attr(named("utterance.queued"), "clientMessageId"))
        assertEquals("local-run-401", attr(named("stop.requested"), "runIds"))
        assertEquals("local-run-401", attr(named("stop.settled"), "runIds"))
        assertNoText()
    }

    private companion object {
        const val SECRET = "the secret words"
        const val REPLY = "a private answer"
    }
}
