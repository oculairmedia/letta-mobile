package com.letta.mobile.feature.chat.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.local.LocalRecognition
import com.letta.mobile.data.voice.local.LocalRecognitionError
import com.letta.mobile.data.voice.local.LocalRecognitionEvent
import com.letta.mobile.data.voice.local.LocalRecognitionRequest
import com.letta.mobile.data.voice.local.LocalRecognizerSession
import com.letta.mobile.data.voice.local.LocalSpeechAvailability
import com.letta.mobile.data.voice.local.LocalSpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LeakCanary showed the framework's recognizer binder stub pinning
 * VoiceInputViewModel, whose stored `onRecognitionDone` pinned the shared chat page. These pin the
 * fix: every session's recognizer is destroyed and its listener detached when the session ends,
 * and the view model has no way to hold a caller's callback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputViewModelLeakTest {
    private val dispatcher = StandardTestDispatcher()
    private val factory = FakeRecognizer()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onCleared destroys the recognizer and detaches the listener`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val vm = store.provide(VoiceInputViewModel(factory))
        vm.startSpeechRecognition()
        runCurrent()
        val session = factory.sessions.single()
        assertTrue(session.started)

        store.clear()
        runCurrent()

        assertTrue("recognizer must be released in onCleared", session.released)
        assertFalse("its recognition is no longer collected", session.collecting)
        session.send(LocalRecognitionEvent.Partial(VoiceText("late")))
        runCurrent()
        assertEquals("nothing reaches the view model after release", "", vm.uiState.value.recognizedText)
    }

    @Test
    fun `a final result releases the session and is emitted, not called back`() = runTest(dispatcher) {
        val vm = VoiceInputViewModel(factory)
        val received = mutableListOf<String>()
        val collector = launch { vm.dictations.toList(received) }
        runCurrent()

        vm.startSpeechRecognition()
        runCurrent()
        val session = factory.sessions.single()
        session.send(LocalRecognitionEvent.Partial(VoiceText("hel")))
        runCurrent()
        assertEquals("hel", vm.uiState.value.recognizedText)
        vm.stopSpeechRecognition()
        advanceUntilIdle()
        assertTrue(session.stopped)
        session.send(LocalRecognitionEvent.Final(VoiceText("hello")))
        runCurrent()

        assertEquals(listOf("hello"), received)
        assertTrue("result must release the recognizer", session.released)
        assertFalse(vm.uiState.value.recognizing)
        session.send(LocalRecognitionEvent.Final(VoiceText("again")))
        runCurrent()
        assertEquals("late callbacks after release go nowhere", listOf("hello"), received)
        collector.cancel()
    }

    @Test
    fun `an error releases the session`() = runTest(dispatcher) {
        val vm = VoiceInputViewModel(factory)
        vm.startSpeechRecognition()
        runCurrent()
        val session = factory.sessions.single()

        session.send(LocalRecognitionEvent.Failed(LocalRecognitionError.NO_MATCH))
        runCurrent()

        assertTrue(session.released)
        session.send(LocalRecognitionEvent.Level(AudioLevel(0.5f)))
        runCurrent()
        assertEquals(0, vm.uiState.value.amplitude)
    }

    @Test
    fun `cancel releases and each press gets a fresh recognizer`() = runTest(dispatcher) {
        val vm = VoiceInputViewModel(factory)
        vm.startSpeechRecognition()
        vm.cancelSpeechRecognition()
        vm.startSpeechRecognition()
        runCurrent()

        assertEquals(2, factory.sessions.size)
        assertTrue(factory.sessions[0].released)
        assertFalse(factory.sessions[1].released)
        assertTrue(vm.uiState.value.recognizing)
    }

    @Test
    fun `a stale stop does not stop a newer press`() = runTest(dispatcher) {
        val vm = VoiceInputViewModel(factory)
        vm.startSpeechRecognition()
        vm.stopSpeechRecognition()
        vm.startSpeechRecognition()
        advanceUntilIdle()

        assertFalse(factory.sessions[1].stopped)
        assertTrue(vm.uiState.value.recognizing)
    }

    @Test
    fun `the view model cannot hold a caller callback`() {
        val publicMethods = VoiceInputViewModel::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
        val callbackParams = publicMethods.flatMap { it.parameterTypes.toList() }
            .filter { Function::class.java.isAssignableFrom(it) }
        assertEquals("no public entry point takes a function", emptyList<Class<*>>(), callbackParams)
        val functionFields = VoiceInputViewModel::class.java.declaredFields
            .filter { Function::class.java.isAssignableFrom(it.type) }
        assertEquals("no stored lambdas", emptyList<Any>(), functionFields.map { it.name })
    }

    private fun <T : ViewModel> ViewModelStore.provide(vm: T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = vm as V
        }
        return ViewModelProvider(this, factory)[vm.javaClass]
    }

    private class FakeRecognizer : LocalSpeechRecognizer {
        val sessions = mutableListOf<FakeSession>()

        override fun availability(language: String?): LocalSpeechAvailability = LocalSpeechAvailability.PLATFORM_SERVICE

        override fun open(request: LocalRecognitionRequest): LocalRecognizerSession = FakeSession().also { sessions += it }
    }

    private class FakeSession : LocalRecognizerSession, LocalRecognition {
        private val channel = Channel<LocalRecognitionEvent>(Channel.UNLIMITED)
        var started = false
        var collecting = false
        var stopped = false
        var released = false

        fun send(event: LocalRecognitionEvent) {
            channel.trySend(event)
        }

        override val events: Flow<LocalRecognitionEvent> = channel.consumeAsFlow()
            .onStart {
                started = true
                collecting = true
            }
            .onCompletion { collecting = false }

        override fun recognize(): LocalRecognition = this

        override fun finish() {
            stopped = true
        }

        override fun close() {
            released = true
        }
    }
}
