package com.letta.mobile.data.voice.session

import com.letta.mobile.data.flow.DerivedStateFlow
import com.letta.mobile.data.voice.InterruptStatus
import com.letta.mobile.data.voice.SpeechSkipReason
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
import com.letta.mobile.data.voice.loop.VoiceLoopObserver
import com.letta.mobile.data.voice.loop.VoiceLoopState
import com.letta.mobile.data.voice.responder.ClientMessageId
import com.letta.mobile.data.voice.responder.SentUtterance
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.responder.VoiceChatState
import com.letta.mobile.data.voice.responder.VoiceRunId
import com.letta.mobile.data.voice.vad.TurnDiscardReason
import com.letta.mobile.data.voice.vad.TurnEndReason
import com.letta.mobile.data.voice.vad.ear.EarObserver
import com.letta.mobile.data.voice.vad.ear.EarTurnReport
import com.letta.mobile.data.voice.vad.ear.SpeechTranscriber
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryValue
import com.letta.mobile.util.attr
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * Voice mode's diagnostics, as `VoiceMode` telemetry events. Every event carries `tMs`, the
 * milliseconds since the session's `mode.started` on [timeSource] (monotonic; null before the
 * first start), and otherwise states, reasons, ids, counts and levels only: never audio,
 * transcript or reply text, an API key or a server address.
 */
internal class VoiceTelemetry(private val timeSource: TimeSource = TimeSource.Monotonic) {
    @Volatile
    private var since: TimeMark? = null

    /** Reports [name] with [attrs] and the time since the session started. */
    fun event(name: String, vararg attrs: Pair<String, TelemetryValue>) =
        Telemetry.event(TAG, name, *attrs, "tMs" attr since?.elapsedNow()?.inWholeMilliseconds)

    /** Voice mode started a session: the session's clock starts here. */
    fun started() {
        since = timeSource.markNow()
        event("mode.started")
    }

    /** Voice mode stopped for [reason]. */
    fun stopped(reason: String) = event("mode.stopped", "reason" attr reason)

    /** The loop moved to [state]'s phase (or its failure, its turn failure or its pending answers changed). */
    fun loopState(state: VoiceLoopState) = event(
        "loop.state",
        "phase" attr state.phase.name,
        "failure" attr state.failure?.code?.name,
        "failedPort" attr state.failure?.port?.name,
        "turnFailure" attr state.turnFailure?.code?.name,
        "turnFailedPort" attr state.turnFailure?.port?.name,
        "pendingReplies" attr state.pendingReplies,
        "microphoneOpen" attr state.microphoneOpen,
        "interrupt" attr state.interrupt.name,
    )

    /** One ear decision ([name]) for [reason], with the turn's voiced time and levels. */
    fun earTurn(name: String, reason: String, report: EarTurnReport) = event(
        name,
        "reason" attr reason,
        "voicedMs" attr report.voiced.inWholeMilliseconds,
        "peakDb" attr report.peakDb.roundToInt(),
        "floorDb" attr report.floorDb?.roundToInt(),
    )

    /** The loop's own decisions: `speech.skipped` with its reason and how many segments. */
    val loop: VoiceLoopObserver = object : VoiceLoopObserver {
        override fun speechSkipped(reason: SpeechSkipReason, segments: Int) =
            event("speech.skipped", "reason" attr reason.name, "segments" attr segments)
    }

    private companion object {
        const val TAG = "VoiceMode"
    }
}

/** The loop state that matters for diagnostics: a change of any of these is one `loop.state` event. */
internal data class LoopDiagnostics(
    val phase: VoicePhase,
    val failure: VoiceError?,
    val turnFailure: VoiceError?,
    val pendingReplies: Int,
    val microphoneOpen: Boolean,
    val interrupt: InterruptStatus,
)

/** This state's [LoopDiagnostics]. */
internal fun VoiceLoopState.diagnosticKey(): LoopDiagnostics =
    LoopDiagnostics(phase, failure, turnFailure, pendingReplies, microphoneOpen, interrupt)

/** Reports the ear's turn decisions through [telemetry]. */
internal class VoiceEarTelemetry(private val telemetry: VoiceTelemetry) : EarObserver {
    override fun turnOpened() = telemetry.event("turn.opened")

    override fun turnClosed(report: EarTurnReport, reason: TurnEndReason) =
        telemetry.earTurn("turn.closed", reason.name, report)

    override fun turnDiscarded(report: EarTurnReport, reason: TurnDiscardReason) =
        telemetry.earTurn("turn.discarded", reason.name, report)

    override fun transcriptAccepted(characters: Int) = telemetry.event("transcript.accepted", "characters" attr characters)
}

/** Reports endpoint failures of [delegate] by kind and HTTP status, then rethrows them. */
internal class TelemetryTranscriber(
    private val delegate: SpeechTranscriber,
    private val telemetry: VoiceTelemetry,
) : SpeechTranscriber {
    override suspend fun transcribe(audio: AudioClip): VoiceText = try {
        delegate.transcribe(audio)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: SpeechEndpointException) {
        telemetry.event(
            "endpoint.error",
            "operation" attr failure.operation.name,
            "kind" attr failure.code.name,
            "httpStatus" attr failure.status,
        )
        throw failure
    }
}

/**
 * [delegate] with each send reported (`utterance.sent` with its client message id), the first time
 * the chat reports a sent message as held in its send queue behind a run `utterance.queued`, the
 * first time the session publishes the run a sent message started `reply.attributed` with the run
 * ids, and each stop: `stop.requested` with the runs in flight that this session's messages
 * started, and `stop.settled` once none of those is in flight any more.
 */
internal class TelemetryVoiceChatSession(
    private val delegate: VoiceChatSession,
    private val telemetry: VoiceTelemetry,
) : VoiceChatSession by delegate {
    private val sent = MutableStateFlow<List<ClientMessageId>>(emptyList())
    private val attributed = MutableStateFlow<Set<ClientMessageId>>(emptySet())
    private val queued = MutableStateFlow<Set<ClientMessageId>>(emptySet())
    private val stopping = MutableStateFlow<Set<VoiceRunId>>(emptySet())

    /** The last message sent, for the responder's failure reports. */
    val lastSent: ClientMessageId? get() = sent.value.lastOrNull()

    override val state: StateFlow<VoiceChatState> =
        DerivedStateFlow(delegate.state.onEach(::observe)) { delegate.state.value }

    override suspend fun send(text: VoiceText): SentUtterance {
        val utterance = delegate.send(text)
        sent.update { (it + utterance.id).takeLast(MAX_TRACKED) }
        telemetry.event("utterance.sent", "clientMessageId" attr utterance.id.value, "characters" attr text.value.length)
        observe(delegate.state.value)
        return utterance
    }

    override fun stopRun() {
        val state = delegate.state.value
        val own = sent.value.flatMap { state.startedRuns[it].orEmpty() }.filterTo(mutableSetOf()) { it in state.activeRuns }
        stopping.update { it + own }
        telemetry.event("stop.requested", "runIds" attr own.joinToString(",") { it.value })
        delegate.stopRun()
    }

    private fun observe(state: VoiceChatState) {
        sent.value.forEach { id ->
            reportQueued(state, id)
            reportAttribution(state, id)
        }
        reportSettledStops(state)
    }

    private fun reportQueued(state: VoiceChatState, id: ClientMessageId) {
        if (id !in state.queued || id in queued.getAndUpdate { it + id }) return
        telemetry.event("utterance.queued", "clientMessageId" attr id.value)
    }

    private fun reportAttribution(state: VoiceChatState, id: ClientMessageId) {
        val runs = state.startedRuns[id].orEmpty()
        if (runs.isEmpty() || id in attributed.getAndUpdate { it + id }) return
        telemetry.event("reply.attributed", "clientMessageId" attr id.value, "runIds" attr runs.joinToString(",") { it.value })
    }

    private fun reportSettledStops(state: VoiceChatState) {
        var settled = emptySet<VoiceRunId>()
        stopping.update { current ->
            settled = current.filterTo(mutableSetOf()) { it !in state.activeRuns }
            current - settled
        }
        if (settled.isNotEmpty()) telemetry.event("stop.settled", "runIds" attr settled.joinToString(",") { it.value })
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
    private val telemetry: VoiceTelemetry,
    private val lastSent: () -> ClientMessageId?,
) : VoiceResponder {
    override fun respond(utterance: VoiceUtterance): VoiceReply {
        val reply = delegate.respond(utterance)
        var characters = 0
        return object : VoiceReply {
            override val events: Flow<VoiceReplyEvent> = reply.events.onEach { event ->
                when (event) {
                    is VoiceReplyEvent.Text -> characters += event.delta.value.length
                    VoiceReplyEvent.Completed -> telemetry.event(
                        "reply.completed",
                        "clientMessageId" attr lastSent()?.value,
                        "characters" attr characters,
                    )
                    is VoiceReplyEvent.Failed -> reportFailure(event)
                    else -> Unit
                }
            }

            override suspend fun cancel(): VoiceCancelOutcome =
                reply.cancel().also { telemetry.event("reply.cancelled", "outcome" attr it.name) }
        }
    }

    private fun reportFailure(event: VoiceReplyEvent.Failed) {
        val code = event.error.code
        val name = if (code.name == UNATTRIBUTED || code.name == NOT_DELIVERED) "reply.unattributed" else "reply.failed"
        telemetry.event(name, "reason" attr code.name, "clientMessageId" attr lastSent()?.value)
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
internal class TelemetryMouth(private val delegate: VoiceMouth, private val telemetry: VoiceTelemetry) : VoiceMouth by delegate {
    override val events: Flow<VoiceMouthEvent> = delegate.events.onEach { event ->
        when (event) {
            is VoiceMouthEvent.Started -> telemetry.event("speech.started", "segment" attr event.id.value)
            is VoiceMouthEvent.Finished -> telemetry.event("speech.played", "segment" attr event.id.value)
            is VoiceMouthEvent.Failed -> telemetry.event(
                "speech.failed",
                "segment" attr event.id.value,
                "kind" attr event.error.code.name,
            )
            else -> Unit
        }
    }

    override fun speak(segment: SpeechSegment) {
        telemetry.event("speech.requested", "segment" attr segment.id.value, "characters" attr segment.text.value.length)
        delegate.speak(segment)
    }
}
