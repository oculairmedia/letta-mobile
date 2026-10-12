package com.letta.mobile.feature.chat.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * letta-mobile-7a1vl: LeakCanary showed the framework's recognizer binder stub pinning
 * VoiceInputViewModel, whose stored `onRecognitionDone` pinned the shared chat page. These pin the
 * fix: every session's recognizer is destroyed and its listener detached when the session ends,
 * and the view model has no way to hold a caller's callback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputViewModelLeakTest {
    private val dispatcher = StandardTestDispatcher()
    private val factory = FakeRecognizerFactory()

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
        val session = factory.sessions.single()
        assertTrue(session.started)

        store.clear()

        assertTrue("recognizer must be destroyed in onCleared", session.released)
        session.events.onPartialText("late")
        session.events.onFinalText("late")
        assertEquals("a detached listener reaches nothing", "", vm.uiState.value.recognizedText)
    }

    @Test
    fun `a final result releases the session and is emitted, not called back`() = runTest(dispatcher) {
        val vm = VoiceInputViewModel(factory)
        val received = mutableListOf<String>()
        val collector = launch { vm.dictations.toList(received) }
        runCurrent()

        vm.startSpeechRecognition()
        val session = factory.sessions.single()
        session.events.onPartialText("hel")
        assertEquals("hel", vm.uiState.value.recognizedText)
        vm.stopSpeechRecognition()
        advanceUntilIdle()
        assertTrue(session.stopped)
        session.events.onFinalText("hello")
        runCurrent()

        assertEquals(listOf("hello"), received)
        assertTrue("result must release the recognizer", session.released)
        assertFalse(vm.uiState.value.recognizing)
        session.events.onFinalText("again")
        runCurrent()
        assertEquals("late callbacks after release go nowhere", listOf("hello"), received)
        collector.cancel()
    }

    @Test
    fun `an error releases the session`() = runTest(dispatcher) {
        val vm = VoiceInputViewModel(factory)
        vm.startSpeechRecognition()
        val session = factory.sessions.single()

        session.events.onError(7)

        assertTrue(session.released)
        session.events.onRms(50f)
        assertEquals(0, vm.uiState.value.amplitude)
    }

    @Test
    fun `cancel releases and each press gets a fresh recognizer`() = runTest(dispatcher) {
        val vm = VoiceInputViewModel(factory)
        vm.startSpeechRecognition()
        vm.cancelSpeechRecognition()
        vm.startSpeechRecognition()

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

    @Test
    fun `a detached framework listener forwards nothing`() {
        val events = RecordingEvents()
        val listener = DetachableRecognitionListener(events)
        listener.onRmsChanged(1f)
        listener.detach()
        listener.onRmsChanged(2f)
        listener.onError(3)

        assertTrue(listener.isDetached)
        assertEquals(listOf("rms"), events.calls)
    }

    private class RecordingEvents : VoiceRecognitionEvents {
        val calls = mutableListOf<String>()
        override fun onRms(rmsdB: Float) {
            calls += "rms"
        }
        override fun onPartialText(text: String) {
            calls += "partial"
        }
        override fun onFinalText(text: String) {
            calls += "final"
        }
        override fun onError(error: Int) {
            calls += "error"
        }
    }

    private fun <T : ViewModel> ViewModelStore.provide(vm: T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = vm as V
        }
        return ViewModelProvider(this, factory)[vm.javaClass]
    }

    private class FakeRecognizerFactory : VoiceRecognizerFactory {
        val sessions = mutableListOf<FakeSession>()

        override fun create(events: VoiceRecognitionEvents): VoiceRecognizerSession =
            FakeSession(events).also { sessions += it }
    }

    private class FakeSession(val events: VoiceRecognitionEvents) : VoiceRecognizerSession {
        var started = false
        var stopped = false
        var released = false

        override fun start() {
            started = true
        }

        override fun stop() {
            stopped = true
        }

        override fun release() {
            released = true
        }
    }
}
