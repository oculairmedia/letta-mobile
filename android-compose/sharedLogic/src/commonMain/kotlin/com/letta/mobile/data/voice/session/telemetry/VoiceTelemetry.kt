package com.letta.mobile.data.voice.session.telemetry

import com.letta.mobile.data.flow.DerivedStateFlow
import com.letta.mobile.data.voice.InterruptStatus
import com.letta.mobile.data.voice.SpeechSkipReason
import com.letta.mobile.data.voice.SpeechStopReason
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
import com.letta.mobile.data.voice.responder.VoiceChatRow
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.responder.VoiceChatState
import com.letta.mobile.data.voice.responder.VoiceRowKey
import com.letta.mobile.data.voice.responder.VoiceRowKind
import com.letta.mobile.data.voice.responder.VoiceRunId
import com.letta.mobile.data.voice.vad.TurnDiscardReason
import com.letta.mobile.data.voice.vad.TurnEndReason
import com.letta.mobile.data.voice.vad.ear.EarObserver
import com.letta.mobile.data.voice.vad.ear.EarTurnReport
import com.letta.mobile.data.voice.vad.ear.SpeechTranscriber
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Voice mode's diagnostics, as `VoiceMode` telemetry events. Every event carries `tMs`, the
 * milliseconds since the session's `mode.started` on [timeSource] (monotonic; null before the
 * first start), and otherwise states, reasons, ids, counts and levels only: never audio,
 * transcript or reply text, an API key or a server address. They go to [sink] (none by default;
 * the chat page passes the app's telemetry).
 */
internal class VoiceTelemetry(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val sink: VoiceTelemetrySink = VoiceTelemetrySink.None,
) {
    @Volatile
    private var since: TimeMark? = null

    /** Reports [name] with [attrs] and the time since the session started. */
    fun event(name: String, vararg attrs: VoiceTelemetryField) =
        sink.event(TAG, name, attrs.asList() + ("tMs" attr since?.elapsedNow()?.inWholeMilliseconds))

    /**
     * Voice mode started a session: the session's clock starts here. Reports whether the microphone
     * path is echo-cancelled and whether speaking interrupts replies (false: half-duplex).
     */
    fun started(echoCancelled: Boolean = false, bargeInByVoice: Boolean = true) {
        since = timeSource.markNow()
        event("mode.started", "echoCancelled" attr echoCancelled, "bargeInByVoice" attr bargeInByVoice)
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

    /**
     * The loop's own decisions: `speech.skipped` with its reason and how many segments, and
     * `speech.stopped` with its reason and `silentMs`, the time from the stop request until the
     * speaker was released.
     */
    val loop: VoiceLoopObserver = object : VoiceLoopObserver {
        override fun speechSkipped(reason: SpeechSkipReason, segments: Int) =
            event("speech.skipped", "reason" attr reason.name, "segments" attr segments)

        override fun speechStopped(reason: SpeechStopReason, silentAfter: Duration) =
            event("speech.stopped", "reason" attr reason.name, "silentMs" attr silentAfter.inWholeMilliseconds)
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
 *
 * While a reply is pending (sent, not yet reported finished through [finished]): `reply.text` each
 * time an assistant text row of its run grows, with the characters added and the row's run id;
 * and, once per pending message, `reply.row_unmatched` with the run id and kind of the first new
 * non-user row whose run is in no pending message's runs. Rows already shown when a message was
 * sent are never reported. No text, only counts and ids.
 */
internal class TelemetryVoiceChatSession(
    private val delegate: VoiceChatSession,
    private val telemetry: VoiceTelemetry,
) : VoiceChatSession by delegate {
    private val sent = MutableStateFlow<List<ClientMessageId>>(emptyList())
    private val attributed = MutableStateFlow<Set<ClientMessageId>>(emptySet())
    private val queued = MutableStateFlow<Set<ClientMessageId>>(emptySet())
    private val stopping = MutableStateFlow<Set<VoiceRunId>>(emptySet())
    private val rows = ReplyRowTelemetry(telemetry)
    private val sentCount = MutableStateFlow(0)
    private val sentInOrder = MutableStateFlow<Map<Int, ClientMessageId>>(emptyMap())

    /** The last message sent, for the responder's failure reports. */
    val lastSent: ClientMessageId? get() = sent.value.lastOrNull()

    /** How many messages this session has sent; the next one sent is number [sendCount]. */
    val sendCount: Int get() = sentCount.value

    /** The message sent as number [index], once it has been sent. */
    fun sentAs(index: Int): ClientMessageId? = sentInOrder.value[index]

    /** The current state, for the responder's completion reports. */
    val current: VoiceChatState get() = delegate.state.value

    /** The reply to [id] has been reported finished: it is no longer pending. */
    fun finished(id: ClientMessageId?) {
        if (id != null) rows.finished(id)
    }

    override val state: StateFlow<VoiceChatState> =
        DerivedStateFlow(delegate.state.onEach(::observe)) { delegate.state.value }

    override suspend fun send(text: VoiceText): SentUtterance {
        val before = delegate.state.value
        val utterance = delegate.send(text)
        rows.pending(utterance.id, before)
        val index = sentCount.getAndUpdate { it + 1 }
        sentInOrder.update { (it + (index to utterance.id)).filterKeys { key -> key > index - MAX_TRACKED } }
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
        rows.observe(state)
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
    private val session: TelemetryVoiceChatSession? = null,
    private val lastSent: () -> ClientMessageId? = { session?.lastSent },
) : VoiceResponder {
    override fun respond(utterance: VoiceUtterance): VoiceReply {
        val index = session?.sendCount
        val reply = delegate.respond(utterance)
        var characters = 0
        val own = { index?.let { session?.sentAs(it) } ?: lastSent() }
        return object : VoiceReply {
            override val events: Flow<VoiceReplyEvent> = reply.events.onEach { event ->
                when (event) {
                    is VoiceReplyEvent.Text -> characters += event.delta.value.length
                    VoiceReplyEvent.Completed -> reportCompleted(own(), characters)
                    is VoiceReplyEvent.Failed -> reportFailure(event, own())
                    else -> Unit
                }
            }

            override suspend fun cancel(): VoiceCancelOutcome =
                reply.cancel().also { telemetry.event("reply.cancelled", "outcome" attr it.name) }
        }
    }

    /**
     * `reply.completed` with the characters received, why the reply counted as complete (its run
     * ended, failed, or none was ever published for it) and its run ids at that moment.
     */
    private fun reportCompleted(id: ClientMessageId?, characters: Int) {
        val state = session?.current
        val runs = id?.let { state?.startedRuns?.get(it) }.orEmpty()
        val reason = when {
            state == null -> "unknown"
            runs.isEmpty() -> "noRun"
            runs.any { it in state.activeRuns } -> "runStillActive"
            runs.any { it in state.failedRuns } -> "runFailed"
            else -> "runEnded"
        }
        telemetry.event(
            "reply.completed",
            "clientMessageId" attr id?.value,
            "characters" attr characters,
            "reason" attr reason,
            "runIds" attr runs.joinToString(",") { it.value },
        )
        session?.finished(id)
    }

    private fun reportFailure(event: VoiceReplyEvent.Failed, id: ClientMessageId?) {
        val code = event.error.code
        val name = if (code.name == UNATTRIBUTED || code.name == NOT_DELIVERED) "reply.unattributed" else "reply.failed"
        telemetry.event(name, "reason" attr code.name, "clientMessageId" attr id?.value)
        session?.finished(id)
    }

    private companion object {
        const val UNATTRIBUTED = "UNATTRIBUTED"
        const val NOT_DELIVERED = "NOT_DELIVERED"
    }
}

/**
 * [delegate] with each segment it is asked to speak reported by character count, and each
 * segment's playback start (`speech.started`), natural end (`speech.ended`) or failure
 * (`speech.failed`, by error kind) as the loop reads it. A stop is reported by the loop
 * (`speech.stopped`).
 */
internal class TelemetryMouth(private val delegate: VoiceMouth, private val telemetry: VoiceTelemetry) : VoiceMouth by delegate {
    override val events: Flow<VoiceMouthEvent> = delegate.events.onEach { event ->
        when (event) {
            is VoiceMouthEvent.Started -> telemetry.event("speech.started", "segment" attr event.id.value)
            is VoiceMouthEvent.Finished -> telemetry.event("speech.ended", "segment" attr event.id.value)
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

/**
 * The row half of [TelemetryVoiceChatSession]: which pending message each new row belongs to, by
 * its run id, reported as counts and ids only.
 */
private class ReplyRowTelemetry(private val telemetry: VoiceTelemetry) {
    private val lock = SynchronizedObject()
    private val pending = linkedSetOf<ClientMessageId>()
    private val baseline = HashSet<VoiceRowKey>()
    private val lengths = HashMap<VoiceRowKey, Int>()
    private val unmatchedReported = HashSet<ClientMessageId>()

    fun pending(id: ClientMessageId, before: VoiceChatState) = synchronized(lock) {
        before.rows.forEach { if (it.key !in lengths) baseline += it.key }
        pending += id
        while (pending.size > MAX_PENDING) pending.remove(pending.first())
    }

    fun finished(id: ClientMessageId) {
        synchronized(lock) { pending.remove(id) }
    }

    fun observe(state: VoiceChatState) = synchronized(lock) {
        if (pending.isEmpty()) return@synchronized
        val owners = pending.flatMap { id -> state.startedRuns[id].orEmpty().map { it to id } }.toMap()
        state.rows.filter { it.kind != VoiceRowKind.USER && it.key !in baseline }.forEach { observeRow(it, owners) }
    }

    private fun observeRow(row: VoiceChatRow, owners: Map<VoiceRunId, ClientMessageId>) {
        val owner = row.runId?.let(owners::get)
        if (owner == null) return reportUnmatched(row)
        if (row.kind != VoiceRowKind.ASSISTANT_TEXT) return
        val grown = row.text.value.length - (lengths[row.key] ?: 0)
        if (grown <= 0) return
        lengths[row.key] = row.text.value.length
        telemetry.event(
            "reply.text",
            "clientMessageId" attr owner.value,
            "runId" attr row.runId?.value,
            "characters" attr grown,
        )
    }

    private fun reportUnmatched(row: VoiceChatRow) {
        val waiting = pending.lastOrNull() ?: return
        if (!unmatchedReported.add(waiting)) return
        telemetry.event(
            "reply.row_unmatched",
            "clientMessageId" attr waiting.value,
            "runId" attr row.runId?.value,
            "kind" attr row.kind.name,
        )
    }

    private companion object {
        const val MAX_PENDING = 8
    }
}

/** One telemetry field: its name and its value. */
typealias VoiceTelemetryField = Pair<String, VoiceTelemetryValue>

/** A telemetry field's value: some text, a number, a flag, or nothing. */
sealed interface VoiceTelemetryValue {
    /** Text: a state, a reason, an id. */
    data class Text(
        /** The text. */
        val value: String,
    ) : VoiceTelemetryValue

    /** A count, a level or a duration. */
    data class Numeric(
        /** The number. */
        val value: Number,
    ) : VoiceTelemetryValue

    /** A yes or no. */
    data class Flag(
        /** The flag. */
        val value: Boolean,
    ) : VoiceTelemetryValue

    /** No value. */
    data object Missing : VoiceTelemetryValue
}

/**
 * Where voice mode's diagnostics go: one event named `name` under `tag`, with its fields. Voice mode
 * reports only states, reasons, ids, counts, levels and timings through it, never audio, transcript
 * or reply text, an API key or an address.
 */
fun interface VoiceTelemetrySink {
    /** Reports the event [name] under [tag] with [fields]. */
    fun event(tag: String, name: String, fields: List<VoiceTelemetryField>)

    /** Sinks. */
    companion object {
        /** Reports nothing. */
        val None: VoiceTelemetrySink = VoiceTelemetrySink { _, _, _ -> }
    }
}

/** A text field; missing when [value] is null. */
internal infix fun String.attr(value: String?): VoiceTelemetryField =
    this to (value?.let { VoiceTelemetryValue.Text(it) } ?: VoiceTelemetryValue.Missing)

/** A numeric field; missing when [value] is null. */
internal infix fun String.attr(value: Number?): VoiceTelemetryField =
    this to (value?.let { VoiceTelemetryValue.Numeric(it) } ?: VoiceTelemetryValue.Missing)

/** A flag field; missing when [value] is null. */
internal infix fun String.attr(value: Boolean?): VoiceTelemetryField =
    this to (value?.let { VoiceTelemetryValue.Flag(it) } ?: VoiceTelemetryValue.Missing)
