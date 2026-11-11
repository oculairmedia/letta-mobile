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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    val outputHints = mutableListOf<Boolean>()
    val retried = mutableListOf<CapturedAudioHandle>()
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
        }
    }

    suspend fun hear(event: VoiceEarEvent) = heard.emit(event)

    suspend fun say(words: String) {
        hear(VoiceEarEvent.SpeechStarted)
        hear(VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText(words))))
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
        return true
    }
}

/** A scripted mouth: records what it was asked to say; tests drive [started] and [finished]. */
internal class FakeMouth(private val log: EngineLog) : VoiceMouth {
    private val emitted = MutableSharedFlow<VoiceMouthEvent>(extraBufferCapacity = 64)
    val queued = mutableListOf<SpeechSegment>()
    val spokenTexts = mutableListOf<String>()
    var stops = 0
        private set
    var hangOnStop = false

    override val events: Flow<VoiceMouthEvent> = emitted

    override fun speak(segment: SpeechSegment) {
        queued += segment
        spokenTexts += segment.text.value
        log.add("mouth:speak")
    }

    override fun flush() = Unit

    override suspend fun stop() {
        if (hangOnStop) awaitCancellation()
        stops++
        queued.clear()
        log.add("mouth:stop")
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

    override val events: Flow<VoiceReplyEvent> = channel.receiveAsFlow()

    fun send(event: VoiceReplyEvent) {
        channel.trySend(event)
    }

    fun text(words: String) = send(VoiceReplyEvent.Text(VoiceText(words)))

    override suspend fun cancel(): VoiceCancelOutcome {
        cancels++
        return cancelOutcome
    }
}

/** A responder handing out [FakeReply]s in order. */
internal class FakeResponder : VoiceResponder {
    val replies = mutableListOf<FakeReply>()

    val last: FakeReply get() = replies.last()

    override fun respond(utterance: VoiceUtterance): VoiceReply = FakeReply(utterance).also { replies += it }
}
