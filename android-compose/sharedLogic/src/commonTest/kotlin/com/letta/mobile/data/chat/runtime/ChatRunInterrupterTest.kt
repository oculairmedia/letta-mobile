package com.letta.mobile.data.chat.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-by4vm.14: the Stop lifecycle both chat clients now share. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRunInterrupterTest {
    private class RecordingHost : ChatRunInterruptHost<String> {
        val steps = mutableListOf<String>()

        override fun onCancelling(key: String) {
            steps += "cancelling:$key"
        }

        override fun onForcedClear(key: String, reason: String) {
            steps += "forced:$key:$reason"
        }

        override fun onSettled(key: String) {
            steps += "settled:$key"
        }

        override fun onAbortFailed(key: String, failure: Exception) {
            steps += "abortFailed:$key:${failure.message}"
        }
    }

    private val host = RecordingHost()
    private val aborted = mutableListOf<String>()

    private fun TestScope.interrupter() = ChatRunInterrupter(
        scope = backgroundScope,
        host = host,
        telemetry = ChatRunInterruptTelemetry("ChatRunInterrupterTest"),
        nowMs = { testScheduler.currentTime },
    )

    private fun stop(
        key: String = "conv-1",
        result: Boolean = true,
        awaitTerminal: (suspend () -> Unit)? = null,
    ) = ChatStopRequest(
        key = key,
        transport = "test",
        abort = {
            aborted += key
            result
        },
        awaitTerminal = awaitTerminal,
    )

    @Test
    fun stopHoldsCancellingUntilTheObservedTerminal() = runTest {
        val streaming = MutableStateFlow(true)
        val interrupter = interrupter()

        interrupter.request(stop(awaitTerminal = { streaming.first { !it } }))
        runCurrent()

        assertEquals(listOf("conv-1"), aborted)
        assertEquals("conv-1", interrupter.cancelling.value)
        assertEquals(listOf("cancelling:conv-1"), host.steps)

        streaming.value = false
        runCurrent()

        assertNull(interrupter.cancelling.value)
        assertEquals(listOf("cancelling:conv-1", "settled:conv-1"), host.steps)
    }

    @Test
    fun hostReportedTerminalSettlesTheStop() = runTest {
        val interrupter = interrupter()
        interrupter.request(stop())
        runCurrent()

        assertFalse(interrupter.settle("conv-other"))
        assertTrue(interrupter.settle("conv-1"))
        runCurrent()

        assertNull(interrupter.cancelling.value)
        assertEquals(listOf("cancelling:conv-1", "settled:conv-1"), host.steps)
        assertFalse(interrupter.settle("conv-1"))
    }

    @Test
    fun secondPressClearsLocallyWithoutASecondAbort() = runTest {
        val interrupter = interrupter()
        interrupter.request(stop())
        runCurrent()
        interrupter.request(stop())
        runCurrent()

        assertEquals(listOf("conv-1"), aborted)
        assertNull(interrupter.cancelling.value)
        assertEquals(listOf("cancelling:conv-1", "forced:conv-1:secondStopPress"), host.steps)
    }

    @Test
    fun aTransportThatCannotAbortClearsLocally() = runTest {
        val interrupter = interrupter()
        interrupter.request(ChatStopRequest(key = "conv-1", transport = "test", abort = null))
        runCurrent()

        assertNull(interrupter.cancelling.value)
        assertEquals(listOf("forced:conv-1:gatewayCannotAbort"), host.steps)
    }

    @Test
    fun anAbortThatDidNotGoOutClearsLocally() = runTest {
        val interrupter = interrupter()
        interrupter.request(stop(result = false))
        runCurrent()

        assertNull(interrupter.cancelling.value)
        assertEquals(listOf("cancelling:conv-1", "forced:conv-1:abortNotDispatched"), host.steps)
    }

    @Test
    fun anAbortThatThrowsIsReportedThenClearsLocally() = runTest {
        val interrupter = interrupter()
        interrupter.request(ChatStopRequest(key = "conv-1", transport = "test", abort = { error("socket down") }))
        runCurrent()

        assertNull(interrupter.cancelling.value)
        assertEquals(
            listOf("cancelling:conv-1", "abortFailed:conv-1:socket down", "forced:conv-1:abortNotDispatched"),
            host.steps,
        )
    }

    @Test
    fun aTerminalThatNeverLandsClearsLocallyAtTheTimeout() = runTest {
        val interrupter = interrupter()
        interrupter.request(stop())
        runCurrent()

        advanceTimeBy(ChatRunInterrupter.CANCEL_TERMINAL_TIMEOUT_MS - 1)
        runCurrent()
        assertEquals("conv-1", interrupter.cancelling.value)

        advanceTimeBy(2)
        runCurrent()
        assertNull(interrupter.cancelling.value)
        assertEquals(listOf("cancelling:conv-1", "forced:conv-1:terminalTimeout"), host.steps)
    }

    @Test
    fun aNewTurnRetiresThePendingStop() = runTest {
        val abortGate = CompletableDeferred<Boolean>()
        val interrupter = interrupter()
        interrupter.request(ChatStopRequest(key = "conv-1", transport = "test", abort = { abortGate.await() }))
        runCurrent()

        interrupter.retire()
        // The retired stop's abort comes back refused, and its timeout passes: neither touches the new turn.
        abortGate.complete(false)
        advanceTimeBy(ChatRunInterrupter.CANCEL_TERMINAL_TIMEOUT_MS * 2)
        runCurrent()

        assertNull(interrupter.cancelling.value)
        assertEquals(listOf("cancelling:conv-1"), host.steps)
    }

    @Test
    fun stoppingAnotherRunSupersedesTheFirst() = runTest {
        val interrupter = interrupter()
        interrupter.request(stop(key = "conv-1"))
        runCurrent()
        interrupter.request(stop(key = "conv-2"))
        runCurrent()

        assertEquals(listOf("conv-1", "conv-2"), aborted)
        assertEquals("conv-2", interrupter.cancelling.value)
        assertFalse(interrupter.settle("conv-1"))
        assertTrue(interrupter.settle("conv-2"))
        assertEquals(listOf("cancelling:conv-1", "cancelling:conv-2", "settled:conv-2"), host.steps)
    }
}
