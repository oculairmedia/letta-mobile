package com.letta.mobile.data.voice.vad.ear

import com.letta.mobile.data.voice.CapturedAudioHandle
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.AudioFrame
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.PcmFormat
import com.letta.mobile.data.voice.audio.pcm.PcmAudio
import com.letta.mobile.data.voice.audio.pcm.PcmLevels
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import com.letta.mobile.data.voice.audio.pcm.WavCodec
import com.letta.mobile.data.voice.endpoint.SpeechEndpointException
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.vad.CaptureRecorder
import com.letta.mobile.data.voice.vad.Hop
import com.letta.mobile.data.voice.vad.HopObservation
import com.letta.mobile.data.voice.vad.HopSplitter
import com.letta.mobile.data.voice.vad.SpeechDetector
import com.letta.mobile.data.voice.vad.TurnEndReason
import com.letta.mobile.data.voice.vad.TurnSignal
import com.letta.mobile.data.voice.vad.TurnTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/** A finished turn's audio under its handle, with what was measured about it; [wipe] zeroes the audio. */
internal class CapturedTurn(
    val handle: CapturedAudioHandle,
    val samples: ShortArray,
    val report: EarTurnReport = EarTurnReport.Unmeasured,
) {
    fun wipe() = samples.fill(0)
}

/** What an ear keeps between listens: the retained capture, pending requests and the output hint. */
internal class EarMemory {
    private val retained = MutableStateFlow<CapturedTurn?>(null)
    private val handles = MutableStateFlow(0L)
    private val closed = MutableStateFlow(false)
    val retries = Channel<CapturedTurn>(Channel.CONFLATED) { it.wipe() }
    val commits = Channel<Unit>(Channel.CONFLATED)
    val outputActive = MutableStateFlow(false)

    val isClosed: Boolean get() = closed.value

    fun nextHandle(): CapturedAudioHandle = CapturedAudioHandle(handles.updateAndGet { it + 1 })

    /** Keeps [turn] for one retry, wiping whatever was kept before. */
    fun retain(turn: CapturedTurn) {
        if (isClosed) return turn.wipe()
        retained.getAndUpdate { turn }?.wipe()
    }

    /** Wipes the kept capture, if any. */
    fun forget() {
        retained.getAndUpdate { null }?.wipe()
    }

    /** Hands over the kept capture named [handle] for its one retry. */
    fun take(handle: CapturedAudioHandle): CapturedTurn? {
        val held = retained.value
        if (isClosed || held?.handle != handle) return null
        return if (retained.compareAndSet(held, null)) held else null
    }

    fun drainCommits() {
        do {
            val drained = commits.tryReceive().isSuccess
        } while (drained)
    }

    fun close() {
        closed.value = true
        forget()
        while (true) retries.tryReceive().getOrNull()?.wipe() ?: break
    }
}

/** Everything one listen needs from its ear. */
internal class EarParts(
    val source: AudioFrameSource,
    val transcriber: SpeechTranscriber,
    val config: VadEarConfig,
    val memory: EarMemory,
)

private sealed interface FrameItem {
    class Frame(val frame: AudioFrame) : FrameItem

    class Failed(val cause: Throwable) : FrameItem
}

private class TranscriptionJob(val turn: CapturedTurn, val isRetry: Boolean)

/** One collection of [VadVoiceEar.listen]: frames in, events out, transcriptions in a worker. */
internal class EarSession(private val parts: EarParts, private val out: ProducerScope<VoiceEarEvent>) {
    private val memory = parts.memory
    private val format = parts.config.format
    private val detector = parts.config.detectors.create(format)
    private val tracker = TurnTracker(parts.config.policy, format, detector.hop)
    private val splitter = HopSplitter(detector.hop)
    private val recorder = CaptureRecorder(samplesOf(parts.config.policy.preRoll), limit())
    // overflow: one entry per finished spoken turn (each within the hard cap); undelivered turns are wiped on close.
    private val turns = Channel<CapturedTurn>(Channel.UNLIMITED) { it.wipe() }
    private val observer = parts.config.observer
    private var started = false
    private var peakDb = SILENT_DB

    suspend fun run() {
        memory.drainCommits()
        if (memory.isClosed) return fail(VoiceErrorCode.UNAVAILABLE)
        if (!accepts(parts.source)) return fail(VoiceErrorCode.UNSUPPORTED_FORMAT)
        out.launch { TranscriptionWorker(parts, out, turns).run() }
        val frames = parts.source.frames()
            .map<AudioFrame, FrameItem> { FrameItem.Frame(it) }
            .catch { emit(FrameItem.Failed(it)) }
            .produceIn(out)
        try {
            while (true) {
                val ended = select<VoiceErrorCode?> {
                    frames.onReceiveCatching { onFrameItem(it.getOrNull()) }
                    memory.commits.onReceive { onCommit() }
                }
                if (ended != null) return fail(ended)
            }
        } finally {
            recorder.wipe()
            splitter.wipe()
            turns.close()
        }
    }

    private fun accepts(source: AudioFrameSource): Boolean =
        source.format.sampleRateHz == format.sampleRateHz && source.format.channels == format.channels

    /** Handles one item; returns the failure code when the source has ended. */
    private suspend fun onFrameItem(item: FrameItem?): VoiceErrorCode? = when (item) {
        is FrameItem.Frame -> null.also { onFrame(item.frame) }
        is FrameItem.Failed -> sourceCodeOf(item.cause)
        null -> VoiceErrorCode.ENGINE_FAILED
    }

    private suspend fun onCommit(): VoiceErrorCode? {
        apply(tracker.commit(), null)
        return null
    }

    private suspend fun onFrame(frame: AudioFrame) {
        if (!started) {
            started = true
            out.send(VoiceEarEvent.ListeningStarted)
        }
        out.send(VoiceEarEvent.InputLevel(PcmLevels.level(frame.samples)))
        splitter.split(frame).forEach { hop -> onHop(hop) }
    }

    private suspend fun onHop(hop: Hop) {
        val outputActive = memory.outputActive.value
        detector.setOutputActive(outputActive)
        val probability = detector.probability(hop.samples)
        val signal = tracker.onHop(HopObservation(probability, hop.end, outputActive))
        if (signal == TurnSignal.Candidate) peakDb = SILENT_DB
        if (tracker.recording || signal is TurnSignal.Ended) peakDb = maxOf(peakDb, PcmLevels.dbfs(hop.samples))
        apply(signal, hop)
        recorder.remember(hop.samples)
    }

    private suspend fun apply(signal: TurnSignal, hop: Hop?) {
        when (signal) {
            TurnSignal.None -> if (tracker.recording) record(hop)
            TurnSignal.Candidate -> {
                recorder.begin()
                record(hop)
            }
            TurnSignal.Confirmed -> speechStarted(hop)
            is TurnSignal.Abandoned -> {
                recorder.discard()
                observer.turnDiscarded(turnReport(tracker, peakDb, detector, format.sampleRateHz), signal.reason)
            }
            is TurnSignal.Ended -> endTurn(hop, signal.reason)
        }
    }

    private fun record(hop: Hop?) {
        if (hop != null) recorder.record(hop.samples)
    }

    private suspend fun speechStarted(hop: Hop?) {
        record(hop)
        memory.forget()
        observer.turnOpened()
        out.send(VoiceEarEvent.SpeechStarted)
    }

    private suspend fun endTurn(hop: Hop?, reason: TurnEndReason) {
        record(hop)
        val report = turnReport(tracker, peakDb, detector, format.sampleRateHz)
        observer.turnClosed(report, reason)
        turns.send(CapturedTurn(memory.nextHandle(), recorder.finish(), report))
    }

    private suspend fun fail(code: VoiceErrorCode): Nothing {
        out.send(VoiceEarEvent.Failed(VoiceError(code, VoicePort.EAR)))
        awaitCancellation()
    }

    private fun sourceCodeOf(error: Throwable): VoiceErrorCode = when (error) {
        is CancellationException -> throw error
        is VoiceAudioException -> error.code
        else -> VoiceErrorCode.ENGINE_FAILED
    }

    private fun limit(): Long = samplesOf(parts.config.policy.maxTurn) + samplesOf(parts.config.policy.preRoll) + detector.hop.samples

    private fun samplesOf(duration: Duration): Long = (duration.toDouble(DurationUnit.SECONDS) * format.sampleRateHz).toLong()
}

/** Transcribes finished turns and retries in order, one at a time, and reports the outcome. */
private class TranscriptionWorker(
    private val parts: EarParts,
    private val out: ProducerScope<VoiceEarEvent>,
    private val turns: ReceiveChannel<CapturedTurn>,
) {
    private val memory = parts.memory
    private val format = parts.config.format

    suspend fun run() {
        while (true) {
            val job = select<TranscriptionJob?> {
                turns.onReceiveCatching { result -> result.getOrNull()?.let { TranscriptionJob(it, isRetry = false) } }
                memory.retries.onReceive { TranscriptionJob(it, isRetry = true) }
            } ?: return
            deliver(job)
        }
    }

    private suspend fun deliver(job: TranscriptionJob) {
        val pcm = PcmAudio(job.turn.samples, PcmFormat(format.sampleRateHz, format.channels))
        val clip = AudioClip(WavCodec.encode(pcm), AudioEncoding.WAV)
        val outcome = runCatching { parts.transcriber.transcribe(clip) }
        clip.bytes.fill(0)
        val error = outcome.exceptionOrNull()
        if (error is CancellationException) {
            job.turn.wipe()
            throw error
        }
        if (error == null) {
            job.turn.wipe()
            return out.send(VoiceEarEvent.EndOfTurn(judged(outcome.getOrThrow(), job.turn.report)))
        }
        val failure = VoiceError(codeOf(error), VoicePort.EAR)
        if (job.isRetry) {
            job.turn.wipe()
            return out.send(VoiceEarEvent.Failed(failure))
        }
        memory.retain(job.turn)
        out.send(VoiceEarEvent.Failed(failure, retry = job.turn.handle))
    }

    /** The transcript as the turn's utterance, or an empty one (the loop listens on) when the policy drops it. */
    private fun judged(text: VoiceText, report: EarTurnReport): VoiceUtterance {
        val reason = parts.config.transcripts.discardReason(text, report)
        if (reason != null) {
            parts.config.observer.turnDiscarded(report, reason)
            return VoiceUtterance(VoiceText.Empty)
        }
        parts.config.observer.transcriptAccepted(text.value.length)
        return VoiceUtterance(text)
    }

    private fun codeOf(error: Throwable): VoiceErrorCode = when (error) {
        is CancellationException -> throw error
        is SpeechEndpointException -> error.code
        is VoiceAudioException -> error.code
        else -> VoiceErrorCode.TRANSCRIPTION_FAILED
    }
}

private const val SILENT_DB = -120.0

/** What [tracker] and [detector] measured about the turn that just ended or was dropped. */
private fun turnReport(
    tracker: TurnTracker,
    peakDb: Double,
    detector: SpeechDetector,
    sampleRateHz: Int,
): EarTurnReport {
    val voiced = (tracker.voicedSamples.toDouble() / sampleRateHz).seconds
    return EarTurnReport(voiced, peakDb, detector.noiseFloorDb)
}
