package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.VoiceLoopConfig
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.activation.VoiceActivationKind
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Clock
import kotlin.time.Instant

/** A loop over scripted fakes on the test scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LoopHarness(private val test: TestScope, config: VoiceLoopConfig = VoiceLoopConfig()) {
    val log = EngineLog()
    val ear = FakeEar(log)
    val mouth = FakeMouth(log)
    val responder = FakeResponder()
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(test.testScheduler.currentTime)
    }
    val loop = VoiceLoop(
        VoiceEngines(ear, responder, mouth),
        config,
        VoiceLoopRuntime(test.backgroundScope, StandardTestDispatcher(test.testScheduler), clock),
    )

    val state: VoiceLoopState get() = loop.state.value

    fun settle() = test.runCurrent()

    fun listening() {
        loop.activate(VoiceActivationKind.TAP)
        settle()
    }

    suspend fun turn(words: String = "what time is it") {
        ear.say(words)
        settle()
    }

    fun reply(event: VoiceReplyEvent) {
        responder.last.send(event)
        settle()
    }

    fun replyText(words: String) = reply(VoiceReplyEvent.Text(VoiceText(words)))

    /** Listening, one turn, and the first spoken sentence queued. */
    suspend fun speaking(words: String = "It is noon. ") {
        listening()
        turn()
        replyText(words)
    }

    suspend fun finishSpeech() {
        mouth.finishAll()
        settle()
    }
}
