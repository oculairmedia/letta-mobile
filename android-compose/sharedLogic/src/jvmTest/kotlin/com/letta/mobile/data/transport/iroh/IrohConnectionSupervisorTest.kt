package com.letta.mobile.data.transport.iroh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IrohConnectionSupervisorTest {
    private val configA = IrohConnectConfig("iroh://ticket-a", "token", "device", "client")
    private val configB = IrohConnectConfig("iroh://ticket-b", "token", "device", "client")

    @Test
    fun concurrentReadyCallersShareOneDial() = runTest {
        var dials = 0
        val gate = CompletableDeferred<Unit>()
        val supervisor = supervisor(config = { configA }) {
            dials += 1
            gate.await()
            fakeHandle(it, "a")
        }

        val first = async { supervisor.ready() }
        val second = async { supervisor.ready() }
        runCurrent()
        assertEquals(1, dials)

        gate.complete(Unit)
        advanceUntilIdle()
        assertSame(first.await(), second.await())
        assertEquals(1, dials)
    }

    @Test
    fun connectionLostDegradesThenAutomaticallyRedialsToReady() = runTest {
        var dials = 0
        val supervisor = supervisor(config = { configA }) {
            dials += 1
            fakeHandle(it, "dial-$dials")
        }

        val first = supervisor.ready()
        supervisor.onConnectionLost("idle_timeout")
        runCurrent()
        assertIs<IrohConnectionState.Degraded>(supervisor.state.value)

        advanceTimeBy(500)
        advanceUntilIdle()
        val ready = assertIs<IrohConnectionState.Ready>(supervisor.state.value)
        assertTrue(ready.handle.sessionId != first.sessionId)
        assertEquals(2, dials)
    }

    @Test
    fun readyTimeoutThrowsWhenDialerKeepsFailing() = runTest {
        val supervisor = supervisor(config = { configA }) {
            throw IllegalStateException("no route")
        }

        val error = assertFailsWith<IllegalStateException> {
            supervisor.ready(timeoutMs = 1_200)
        }
        assertTrue(error.message.orEmpty().contains("Iroh connection not ready after 1200ms"))
        supervisor.close("test_done")
    }

    @Test
    fun backoffSequenceIsBounded() {
        val policy = IrohConnectionSupervisor.BackoffPolicy(initialMs = 500, maxMs = 8_000, jitterMs = 0)
        val delays = (1..8).map { attempt -> policy.delayMs(attempt) { 0 } }
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L, 8_000L), delays)
    }

    @Test
    fun configChangeClosesOldHandleAndRedials() = runTest {
        var config = configA
        val closed = mutableListOf<String>()
        val supervisor = supervisor(config = { config }) {
            fakeHandle(it, it.baseShimUrl) { reason -> closed += "${it.baseShimUrl}:$reason" }
        }

        val first = supervisor.ready()
        config = configB
        supervisor.refreshConfig()
        advanceUntilIdle()
        val second = supervisor.ready()

        assertTrue(first.sessionId != second.sessionId)
        assertEquals(listOf("iroh://ticket-a:config_changed"), closed)
        assertEquals("iroh://ticket-b", second.sessionId)
    }

    private fun TestScope.supervisor(
        config: () -> IrohConnectConfig?,
        dialer: suspend (IrohConnectConfig) -> IrohConnectionHandle,
    ): IrohConnectionSupervisor = IrohConnectionSupervisor(
        scope = this,
        configProvider = config,
        dialer = dialer,
        backoffPolicy = IrohConnectionSupervisor.BackoffPolicy(initialMs = 500, maxMs = 8_000, jitterMs = 0),
        randomJitterMs = { 0 },
    )

    private fun fakeHandle(
        config: IrohConnectConfig,
        sessionId: String,
        close: suspend (String) -> Unit = {},
    ): IrohConnectionHandle = IrohConnectionHandle(
        config = config,
        ticket = config.baseShimUrl.removePrefix("iroh://"),
        sessionId = sessionId,
        close = close,
    )
}
