package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.CapturedAudioHandle
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.SpeechSegmentId
import com.letta.mobile.data.voice.engine.VoiceCancelOutcome
import com.letta.mobile.data.voice.engine.VoiceEar
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceMouth
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import com.letta.mobile.data.voice.engine.VoiceReply
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import com.letta.mobile.data.voice.engine.VoiceResponder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/** One ordered log shared by the fakes, so tests can assert the order engines were driven in. */
internal class EngineLog {
    val entries = mutableListOf<String>()

    fun add(entry: String) {
        entries += entry
    }
}

/** A scripted ear: [hear] pushes events into the running [listen] collection. */
internal class FakeEar(private val log: EngineLog) : VoiceEar {
    private val heard = MutableSharedFlow<VoiceEarEvent>(extraBufferCapacity = 64)
    var listening = false
        private set
    var releaseDelay: Duration = Duration.ZERO
    var throwOnRelease = false
    val outputHints = mutableListOf<Boolean>()
    val retried = mutableListOf<CapturedAudioHandle>()
    var retryAccepted = true
    var commits = 0
        private set

    override fun listen(): Flow<VoiceEarEvent> = flow {
        listening = true
        log.add("ear:open")
        try {
            emit(VoiceEarEvent.ListeningStarted)
            heard.collect { emit(it) }
        } finally {
            if (releaseDelay > Duration.ZERO) withContext(NonCancellable) { delay(releaseDelay) }
            listening = false
            log.add("ear:close")
            check(!throwOnRelease) { "microphone stuck" }
        }
    }

    suspend fun hear(event: VoiceEarEvent) = heard.emit(event)

    suspend fun say(words: VoiceText) {
        hear(VoiceEarEvent.SpeechStarted)
        hear(VoiceEarEvent.EndOfTurn(VoiceUtterance(words)))
    }

    override fun commitTurn() {
        commits++
    }

    override fun setOutputActive(active: Boolean) {
        outputHints += active
        log.add("ear:output=$active")
    }

    override fun retry(handle: CapturedAudioHandle): Boolean {
        retried += handle
        log.add("ear:retry")
        return retryAccepted
    }

    /** How many times the microphone was opened. */
    fun opens(): Int = log.entries.count { it == "ear:open" }
}

/** A scripted mouth: records what it was asked to say; tests drive [started] and [finished]. */
internal class FakeMouth(private val log: EngineLog) : VoiceMouth {
    private val emitted = MutableSharedFlow<VoiceMouthEvent>(extraBufferCapacity = 64)
    val queued = mutableListOf<SpeechSegment>()
    val spokenTexts = mutableListOf<String>()
    var stops = 0
        private set
    var hangOnStop = false
    var throwOnSpeak = false
    var throwOnStop = false
    private val stopGate = CompletableDeferred<Unit>()

    override val events: Flow<VoiceMouthEvent> = emitted

    override fun speak(segment: SpeechSegment) {
        check(!throwOnSpeak) { "speaker gone" }
        queued += segment
        spokenTexts += segment.text.value
        log.add("mouth:speak")
    }

    override fun flush() = Unit

    override suspend fun stop() {
        if (hangOnStop) stopGate.await()
        check(!throwOnStop) { "speaker stuck" }
        stops++
        queued.clear()
        log.add("mouth:stop")
    }

    /** Lets a stop that was hanging finish. */
    fun releaseStop() {
        stopGate.complete(Unit)
    }

    suspend fun started(id: SpeechSegmentId) = emitted.emit(VoiceMouthEvent.Started(id))

    suspend fun emit(event: VoiceMouthEvent) = emitted.emit(event)

    /** Plays the oldest queued segment to its end. */
    suspend fun finishNext() {
        val segment = queued.removeFirst()
        emitted.emit(VoiceMouthEvent.Started(segment.id))
        emitted.emit(VoiceMouthEvent.Finished(segment.id))
    }

    suspend fun finishAll() {
        while (queued.isNotEmpty()) finishNext()
    }
}

/** A reply the test streams by hand. */
internal class FakeReply(val utterance: VoiceUtterance) : VoiceReply {
    private val channel = Channel<VoiceReplyEvent>(Channel.UNLIMITED)
    var cancelOutcome = VoiceCancelOutcome.STOPPED
    var cancels = 0
        private set

    /** Set: a cancel suspends until it completes (a stop that takes a while to settle). */
    var cancelGate: CompletableDeferred<Unit>? = null

    /** Whether the loop has started collecting (that is, sending) this reply. */
    var collected = false
        private set

    override val events: Flow<VoiceReplyEvent> = flow {
        collected = true
        emitAll(channel.receiveAsFlow())
    }

    fun send(event: VoiceReplyEvent) {
        channel.trySend(event)
    }

    fun text(words: String) = send(VoiceReplyEvent.Text(VoiceText(words)))

    override suspend fun cancel(): VoiceCancelOutcome {
        cancels++
        cancelGate?.await()
        return cancelOutcome
    }
}

/** A responder handing out [FakeReply]s in order. */
internal class FakeResponder : VoiceResponder {
    val replies = mutableListOf<FakeReply>()

    val last: FakeReply get() = replies.last()

    override fun respond(utterance: VoiceUtterance): VoiceReply = FakeReply(utterance).also { replies += it }
}
