package com.letta.mobile.data.transport.iroh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
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
    fun connectionLostInvalidatesReadyHandleBeforeRetry() = runTest {
        var dials = 0
        val supervisor = supervisor(config = { configA }) {
            dials += 1
            fakeHandle(it, "dial-$dials")
        }

        val first = supervisor.ready()
        supervisor.onConnectionLost("admin_rpc_failed")
        val retry = async { supervisor.ready() }
        runCurrent()

        assertIs<IrohConnectionState.Degraded>(supervisor.state.value)
        assertTrue(!retry.isCompleted)
        advanceTimeBy(500)
        advanceUntilIdle()
        val second = retry.await()

        assertNotSame(first, second)
        assertEquals("dial-2", second.sessionId)
        assertEquals(2, dials)
    }

    @Test
    fun authFailureBecomesTerminalAndDoesNotRedial() = runTest {
        var dials = 0
        val supervisor = supervisor(config = { configA }) {
            dials += 1
            throw IrohAuthFailure("bad token")
        }

        val error = assertFailsWith<IllegalStateException> {
            supervisor.ready(timeoutMs = 100)
        }
        runCurrent()
        advanceTimeBy(5_000)
        advanceUntilIdle()

        assertTrue(error.message.orEmpty().contains("bad token"))
        assertIs<IrohConnectionState.AuthFailed>(supervisor.state.value)
        assertEquals(1, dials)
    }

    @Test
    fun readyRespectsScheduledBackoffAfterFailure() = runTest {
        var dials = 0
        val supervisor = supervisor(config = { configA }) {
            dials += 1
            if (dials == 1) throw IllegalStateException("temporary outage")
            fakeHandle(it, "dial-$dials")
        }

        assertFailsWith<IllegalStateException> {
            supervisor.ready(timeoutMs = 1)
        }
        assertEquals(1, dials)

        val retry = async { supervisor.ready(timeoutMs = 2_000) }
        runCurrent()
        advanceTimeBy(499)
        runCurrent()
        assertEquals(1, dials)
        assertTrue(!retry.isCompleted)

        advanceTimeBy(1)
        advanceUntilIdle()
        assertEquals("dial-2", retry.await().sessionId)
        assertEquals(2, dials)
    }

    @Test
    fun disconnectAllowsLaterReconnect() = runTest {
        var dials = 0
        val closed = mutableListOf<String>()
        val supervisor = supervisor(config = { configA }) {
            dials += 1
            fakeHandle(it, "dial-$dials") { reason -> closed += reason }
        }

        val first = supervisor.ready()
        supervisor.disconnect("user_disconnect")
        advanceUntilIdle()
        assertIs<IrohConnectionState.Disconnected>(supervisor.state.value)

        val second = supervisor.ready()

        assertNotSame(first, second)
        assertEquals("dial-2", second.sessionId)
        assertEquals(listOf("user_disconnect"), closed)
    }

    @Test
    fun readerExitClosesStaleHandleOnceDegradesAndRedials() = runTest {
        var dials = 0
        val closed = mutableListOf<String>()
        val supervisor = supervisor(config = { configA }) {
            dials += 1
            fakeHandle(it, "dial-$dials") { reason -> closed += reason }
        }

        supervisor.ready()
        supervisor.onConnectionLost("reader_stopped:Stream")
        supervisor.onConnectionLost("reader_stopped:Control")
        runCurrent()

        assertEquals(listOf("reader_stopped:Stream"), closed)
        assertIs<IrohConnectionState.Degraded>(supervisor.state.value)

        advanceTimeBy(500)
        advanceUntilIdle()

        val ready = assertIs<IrohConnectionState.Ready>(supervisor.state.value)
        assertEquals("dial-2", ready.handle.sessionId)
        assertEquals(2, dials)
    }

    @Test
    fun staleConnectionLossForSupersededHandleIsIgnored() = runTest {
        // letta-mobile-r3i1z: a dead transport reports its loss up to twice
        // (close watcher + reader exit) and the SECOND report lands after the
        // supervisor has already redialed. It must be attributed to the dead
        // handle and IGNORED — not treated as a loss of the healthy new
        // connection (which used to tear down the redialed handle and the
        // observer-ingestion collector that had just re-armed against it).
        var dials = 0
        val closed = mutableListOf<String>()
        val supervisor = supervisor(config = { configA }) {
            dials += 1
            val sessionId = "dial-$dials"
            fakeHandle(it, sessionId) { reason -> closed += "$sessionId:$reason" }
        }

        val first = supervisor.ready()
        // Genuine loss of dial-1 (attributed) -> degrade + automatic redial.
        supervisor.onConnectionLost("connection_closed: timed out", first)
        runCurrent()
        assertIs<IrohConnectionState.Degraded>(supervisor.state.value)
        advanceTimeBy(500)
        advanceUntilIdle()
        assertEquals("dial-2", assertIs<IrohConnectionState.Ready>(supervisor.state.value).handle.sessionId)

        // dial-1's late duplicate loss report (triggered by closing it) lands
        // AFTER the redial completed: stale, must not touch dial-2.
        supervisor.onConnectionLost("reader_stopped:Stream", first)
        advanceUntilIdle()

        val still = assertIs<IrohConnectionState.Ready>(supervisor.state.value)
        assertEquals("dial-2", still.handle.sessionId)
        assertEquals(2, dials)
        assertTrue(closed.none { it.startsWith("dial-2") }, "healthy redialed handle must not be closed; closed=$closed")

        // Unattributed losses (legacy callers) keep today's behavior: they DO
        // invalidate whatever is current.
        supervisor.onConnectionLost("legacy_unattributed")
        runCurrent()
        assertIs<IrohConnectionState.Degraded>(supervisor.state.value)
    }

    @Test
    fun configChangeCancelsInFlightDialAndStartsNewConfig() = runTest {
        var config = configA
        val firstDialGate = CompletableDeferred<Unit>()
        val seenConfigs = mutableListOf<IrohConnectConfig>()
        val supervisor = supervisor(config = { config }) {
            seenConfigs += it
            if (it == configA) firstDialGate.await()
            fakeHandle(it, it.baseShimUrl)
        }

        val first = async { supervisor.ready() }
        runCurrent()
        config = configB
        supervisor.refreshConfig()
        val second = async { supervisor.ready() }
        runCurrent()
        firstDialGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("iroh://ticket-b", first.await().sessionId)
        assertEquals("iroh://ticket-b", second.await().sessionId)
        assertEquals(listOf(configA, configB), seenConfigs)
        val ready = assertIs<IrohConnectionState.Ready>(supervisor.state.value)
        assertEquals(configB, ready.handle.config)
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
