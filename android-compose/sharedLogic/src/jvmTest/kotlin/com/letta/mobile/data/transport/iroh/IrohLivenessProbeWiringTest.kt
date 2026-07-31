package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.api.LivenessProbingChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * letta-mobile-wxy4s PRODUCTION WIRING GATE.
 *
 * [IrohLivenessProbeTest] compresses the probe cadence so it can run in
 * milliseconds. That leaves one silent-revert hole: shipping with the probe
 * effectively OFF (interval defaulted to 0, or the arm site removed) while every
 * compressed test still passes because it passes its own interval.
 *
 * This test therefore constructs the transport the way PRODUCTION does — no probe
 * overrides at all — and asserts the defaults are real and the loop is actually
 * armed on Ready. Mirrors AppServerServeIrohProductionWiringTest.
 */
class IrohLivenessProbeWiringTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private val config = IrohConnectConfig(
        baseShimUrl = "iroh://ticket",
        token = "",
        deviceId = "device",
        clientVersion = "test",
    )

    /** Production construction: probe cadence comes ONLY from the defaults. */
    private fun productionTransport(): IrohChannelTransport = IrohChannelTransport(
        scope = scope,
        activeConfigProvider = { config },
        testDialer = { dialConfig ->
            IrohConnectionHandle(
                config = dialConfig,
                ticket = "ticket",
                sessionId = "session",
                adminRpcCall = { method, _, _ ->
                    AppServerInboundFrame.AdminRpcResponse(
                        requestId = method,
                        success = true,
                        result = JsonPrimitive("ok"),
                    )
                },
                connectionAlive = { true },
                close = {},
            )
        },
    )

    @Test
    fun productionDefaultsKeepTheLivenessProbeEnabled() {
        val transport = productionTransport()
        assertTrue(
            transport.livenessProbeIntervalMs > 0L,
            "a zero/negative probe interval disables liveness detection entirely — the exact " +
                "silent revert that reproduces the 2026-07-31 outage; " +
                "interval=${transport.livenessProbeIntervalMs}",
        )
        assertTrue(
            transport.livenessProbeTimeoutMs > 0L,
            "the probe must bound its own health.check call (health.check is " +
                "legacy-fallback-safe: unbounded means a 30s-60s worst case per probe); " +
                "timeout=${transport.livenessProbeTimeoutMs}",
        )
        assertTrue(
            transport.livenessProbeTimeoutMs < transport.livenessProbeIntervalMs,
            "the probe timeout must fit inside one interval; " +
                "timeout=${transport.livenessProbeTimeoutMs} interval=${transport.livenessProbeIntervalMs}",
        )
        assertTrue(
            transport.livenessProbeFailuresToDeclareDead >= 1,
            "declaring death needs at least one failed probe; " +
                "failures=${transport.livenessProbeFailuresToDeclareDead}",
        )
        // Detection must stay meaningfully faster than a user noticing an outage.
        val worstCaseDetectionMs = transport.livenessProbeIntervalMs +
            transport.livenessProbeFailuresToDeclareDead *
            (transport.livenessProbeIntervalMs + transport.livenessProbeTimeoutMs)
        assertTrue(
            worstCaseDetectionMs <= 120_000L,
            "worst-case detection must stay well under the ~40min incident window; " +
                "worstCase=${worstCaseDetectionMs}ms",
        )
        assertEquals(
            IrohChannelTransport.LIVENESS_PROBE_INTERVAL_MS,
            transport.livenessProbeIntervalMs,
            "production construction must use the documented default interval",
        )
    }

    @Test
    fun productionConstructorArmsTheProbeOnReady() = runBlocking {
        val transport = productionTransport()
        assertTrue(!transport.isLivenessProbeArmed, "no probe before connect")
        transport.connect("iroh://ticket", "", "device", "test")
        try {
            withTimeout(5.seconds) {
                while (transport.state.value !is ChannelTransportState.Connected) delay(10.milliseconds)
            }
            assertTrue(
                transport.isLivenessProbeArmed,
                "the supervisor's Ready transition must ARM the liveness probe — deleting the " +
                    "arm site is the other silent revert this gate exists for",
            )
        } finally {
            transport.disconnect()
        }
        assertTrue(!transport.isLivenessProbeArmed, "disconnect disarms the probe")
    }

    @Test
    fun transportExposesProbeNowForLifecycleResume() {
        val transport: LivenessProbingChannelTransport = productionTransport()
        // Doze/background makes the periodic loop unreliable; the resume hook must
        // exist on the shared interface and be safe to call when idle.
        transport.probeNow()
    }
}
