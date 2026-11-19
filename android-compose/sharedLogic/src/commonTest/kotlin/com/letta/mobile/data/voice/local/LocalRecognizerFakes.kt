package com.letta.mobile.data.voice.local

import com.letta.mobile.data.voice.CapturedAudioHandle
import com.letta.mobile.data.voice.engine.VoiceEar
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A local recogniser the test drives: it records every session and recognition it hands out. */
internal class FakeLocalRecognizer(
    var availability: LocalSpeechAvailability = LocalSpeechAvailability.ON_DEVICE,
    override val echoCancelled: Boolean = false,
) : LocalSpeechRecognizer {
    val sessions = mutableListOf<FakeRecognizerSession>()
    val recognitions = mutableListOf<FakeRecognition>()

    /** Every request a session was opened for, in order. */
    val requests = mutableListOf<LocalRecognitionRequest>()

    override fun availability(language: String?): LocalSpeechAvailability = availability

    override fun open(request: LocalRecognitionRequest): LocalRecognizerSession {
        requests += request
        return FakeRecognizerSession(this).also { sessions += it }
    }

    /** The recognition started last. */
    val current: FakeRecognition get() = recognitions.last()
}

internal class FakeRecognizerSession(private val owner: FakeLocalRecognizer) : LocalRecognizerSession {
    var closed = false
        private set

    override fun recognize(): LocalRecognition = FakeRecognition().also { owner.recognitions += it }

    override fun close() {
        closed = true
    }
}

/** One recognition: events the test sends come out of [events] until a final result or a failure. */
internal class FakeRecognition : LocalRecognition {
    private val script = Channel<LocalRecognitionEvent>(Channel.UNLIMITED)
    var collecting = false
        private set
    var collected = false
        private set
    var finished = false
        private set

    fun send(vararg events: LocalRecognitionEvent) = events.forEach { script.trySend(it) }

    override val events: Flow<LocalRecognitionEvent> = flow {
        collecting = true
        collected = true
        try {
            for (event in script) {
                emit(event)
                if (event is LocalRecognitionEvent.Final || event is LocalRecognitionEvent.Failed) break
            }
        } finally {
            collecting = false
        }
    }

    override fun finish() {
        finished = true
    }
}

/** The server ear stand-in: it listens until cancelled and records what it is told. */
internal class FallbackEar : VoiceEar, AutoCloseable {
    var listens = 0
        private set
    var outputActive = false
        private set
    var commits = 0
        private set
    var closed = false
        private set

    override fun listen(): Flow<VoiceEarEvent> = flow {
        listens++
        emit(VoiceEarEvent.ListeningStarted)
        awaitCancellation()
    }

    override fun commitTurn() {
        commits++
    }

    override fun setOutputActive(active: Boolean) {
        outputActive = active
    }

    override fun retry(handle: CapturedAudioHandle): Boolean = false

    override fun close() {
        closed = true
    }
}
