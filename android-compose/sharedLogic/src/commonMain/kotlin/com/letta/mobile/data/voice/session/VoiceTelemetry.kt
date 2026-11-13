package com.letta.mobile.data.voice.session

import com.letta.mobile.data.flow.DerivedStateFlow
import com.letta.mobile.data.voice.InterruptStatus
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.endpoint.SpeechEndpointException
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.VoiceCancelOutcome
import com.letta.mobile.data.voice.engine.VoiceMouth
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import com.letta.mobile.data.voice.engine.VoiceReply
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import com.letta.mobile.data.voice.engine.VoiceResponder
import com.letta.mobile.data.voice.loop.VoiceLoopState
import com.letta.mobile.data.voice.responder.ClientMessageId
import com.letta.mobile.data.voice.responder.SentUtterance
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.responder.VoiceChatState
import com.letta.mobile.data.voice.vad.TurnDiscardReason
import com.letta.mobile.data.voice.vad.TurnEndReason
import com.letta.mobile.data.voice.vad.ear.EarObserver
import com.letta.mobile.data.voice.vad.ear.EarTurnReport
import com.letta.mobile.data.voice.vad.ear.SpeechTranscriber
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryValue
import com.letta.mobile.util.attr
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.onEach

/**
 * Voice mode's diagnostics, as `VoiceMode` telemetry events. Every event carries states, reasons,
 * ids, counts and levels only: never audio, transcript or reply text, an API key or a server
 * address.
 */
internal object VoiceTelemetry {
    private const val TAG = "VoiceMode"

    fun event(name: String, vararg attrs: Pair<String, TelemetryValue>) = Telemetry.event(TAG, name, *attrs)

    /** Voice mode started a session. */
    fun started() = event("mode.started")

    /** Voice mode stopped for [reason]. */
    fun stopped(reason: String) = event("mode.stopped", "reason" attr reason)

    /** The loop moved to [state]'s phase (or its failure changed). */
    fun loopState(state: VoiceLoopState) = event(
        "loop.state",
        "phase" attr state.phase.name,
        "failure" attr state.failure?.code?.name,
        "failedPort" attr state.failure?.port?.name,
        "microphoneOpen" attr state.microphoneOpen,
        "interrupt" attr state.interrupt.name,
    )

    /** One ear decision ([name]) for [reason], with the turn's voiced time and levels. */
    fun earTurn(name: String, reason: String, report: EarTurnReport) = Telemetry.event(
        TAG,
        name,
        "reason" attr reason,
        "voicedMs" attr report.voiced.inWholeMilliseconds,
        "peakDb" attr report.peakDb.roundToInt(),
        "floorDb" attr report.floorDb?.roundToInt(),
    )
}

/** The loop state that matters for diagnostics: a change of any of these is one `loop.state` event. */
internal data class LoopDiagnostics(
    val phase: VoicePhase,
    val failure: VoiceError?,
    val microphoneOpen: Boolean,
    val interrupt: InterruptStatus,
)

/** This state's [LoopDiagnostics]. */
internal fun VoiceLoopState.diagnosticKey(): LoopDiagnostics = LoopDiagnostics(phase, failure, microphoneOpen, interrupt)

/** Reports the ear's turn decisions. */
internal object VoiceEarTelemetry : EarObserver {
    override fun turnOpened() = VoiceTelemetry.event("turn.opened")

    override fun turnClosed(report: EarTurnReport, reason: TurnEndReason) =
        VoiceTelemetry.earTurn("turn.closed", reason.name, report)

    override fun turnDiscarded(report: EarTurnReport, reason: TurnDiscardReason) =
        VoiceTelemetry.earTurn("turn.discarded", reason.name, report)

    override fun transcriptAccepted(characters: Int) = VoiceTelemetry.event("transcript.accepted", "characters" attr characters)
}

/** Reports endpoint failures of [delegate] by kind and HTTP status, then rethrows them. */
internal class TelemetryTranscriber(private val delegate: SpeechTranscriber) : SpeechTranscriber {
    override suspend fun transcribe(audio: AudioClip): VoiceText = try {
        delegate.transcribe(audio)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: SpeechEndpointException) {
        VoiceTelemetry.event(
            "endpoint.error",
            "operation" attr failure.operation.name,
            "kind" attr failure.code.name,
            "httpStatus" attr failure.status,
        )
        throw failure
    }
}

/**
 * [delegate] with each send reported (`utterance.sent` with its client message id) and, the first
 * time the session publishes the run a sent message started, `reply.attributed` with the run ids.
 */
internal class TelemetryVoiceChatSession(private val delegate: VoiceChatSession) : VoiceChatSession by delegate {
    private val sent = MutableStateFlow<List<ClientMessageId>>(emptyList())
    private val attributed = MutableStateFlow<Set<ClientMessageId>>(emptySet())

    /** The last message sent, for the responder's failure reports. */
    val lastSent: ClientMessageId? get() = sent.value.lastOrNull()

    override val state: StateFlow<VoiceChatState> =
        DerivedStateFlow(delegate.state.onEach(::reportAttributions)) { delegate.state.value }

    override suspend fun send(text: VoiceText): SentUtterance {
        val utterance = delegate.send(text)
        sent.value = (sent.value + utterance.id).takeLast(MAX_TRACKED)
        VoiceTelemetry.event("utterance.sent", "clientMessageId" attr utterance.id.value, "characters" attr text.value.length)
        return utterance
    }

    private fun reportAttributions(state: VoiceChatState) {
        sent.value.forEach { id ->
            val runs = state.startedRuns[id].orEmpty()
            if (runs.isEmpty() || id in attributed.getAndUpdate { it + id }) return@forEach
            VoiceTelemetry.event(
                "reply.attributed",
                "clientMessageId" attr id.value,
                "runIds" attr runs.joinToString(",") { it.value },
            )
        }
    }

    private companion object {
        const val MAX_TRACKED = 8
    }
}

/**
 * [delegate] with how each reply ended reported: `reply.completed` with the characters received, or
 * `reply.unattributed` / `reply.failed` with the reason and the client message id of [lastSent].
 */
internal class TelemetryResponder(
    private val delegate: VoiceResponder,
    private val lastSent: () -> ClientMessageId?,
) : VoiceResponder {
    override fun respond(utterance: VoiceUtterance): VoiceReply {
        val reply = delegate.respond(utterance)
        var characters = 0
        return object : VoiceReply {
            override val events: Flow<VoiceReplyEvent> = reply.events.onEach { event ->
                when (event) {
                    is VoiceReplyEvent.Text -> characters += event.delta.value.length
                    VoiceReplyEvent.Completed -> VoiceTelemetry.event(
                        "reply.completed",
                        "clientMessageId" attr lastSent()?.value,
                        "characters" attr characters,
                    )
                    is VoiceReplyEvent.Failed -> reportFailure(event)
                    else -> Unit
                }
            }

            override suspend fun cancel(): VoiceCancelOutcome =
                reply.cancel().also { VoiceTelemetry.event("reply.cancelled", "outcome" attr it.name) }
        }
    }

    private fun reportFailure(event: VoiceReplyEvent.Failed) {
        val code = event.error.code
        val name = if (code.name == UNATTRIBUTED || code.name == NOT_DELIVERED) "reply.unattributed" else "reply.failed"
        VoiceTelemetry.event(name, "reason" attr code.name, "clientMessageId" attr lastSent()?.value)
    }

    private companion object {
        const val UNATTRIBUTED = "UNATTRIBUTED"
        const val NOT_DELIVERED = "NOT_DELIVERED"
    }
}

/**
 * [delegate] with each segment it is asked to speak reported by character count, and each
 * segment's playback start, end or failure (by error kind) as the loop reads it.
 */
internal class TelemetryMouth(private val delegate: VoiceMouth) : VoiceMouth by delegate {
    override val events: Flow<VoiceMouthEvent> = delegate.events.onEach { event ->
        when (event) {
            is VoiceMouthEvent.Started -> VoiceTelemetry.event("speech.started", "segment" attr event.id.value)
            is VoiceMouthEvent.Finished -> VoiceTelemetry.event("speech.played", "segment" attr event.id.value)
            is VoiceMouthEvent.Failed -> VoiceTelemetry.event(
                "speech.failed",
                "segment" attr event.id.value,
                "kind" attr event.error.code.name,
            )
            else -> Unit
        }
    }

    override fun speak(segment: SpeechSegment) {
        VoiceTelemetry.event("speech.requested", "segment" attr segment.id.value, "characters" attr segment.text.value.length)
        delegate.speak(segment)
    }
}
