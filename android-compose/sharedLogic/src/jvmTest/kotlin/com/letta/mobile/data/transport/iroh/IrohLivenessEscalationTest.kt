package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-qygvv.22: liveness escalation on evidence of a dead path.
 *
 * INCIDENT (2026-09-25): a Tailscale path flap black-holed the phone's Iroh
 * connection for ~40 s. Six admin_rpcs were in flight, so every probe timeout was
 * soft-failed as CONGESTED (parg0) and no redial happened — each request waited the
 * full 30 s admin_rpc timeout.
 *
 * All scenarios run the REAL [IrohLivenessProbe] loop on virtual time (production
 * cadence: 20 s interval, 10 s timeout, 2 failures) against a fake admin_rpc lane
 * that owns in-flight work, proof of life and the real [AdminRpcTimeoutStreak].
 * They drive time with `advanceTimeBy` only: the loop is endless by design.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohLivenessEscalationTest {

    /** Fake admin_rpc lane on the test scheduler's clock. */
    private class Lane(private val clock: () -> Long) {
        private val inFlightStartedAt = mutableListOf<Long>()
        private var lastProofOfLifeAt = clock()
        private val streak = AdminRpcTimeoutStreak(clock)

        /** health.check hangs until this completes (null = answers immediately). */
        var healthCheckGate: CompletableDeferred<Unit>? = null
        val healthCheckStarts = mutableListOf<Long>()

        fun proofOfLife() {
            lastProofOfLifeAt = clock()
            streak.reset()
        }

        fun startRpc(): Long = clock().also { inFlightStartedAt += it }

        fun completeRpc(startedAt: Long) {
            inFlightStartedAt -= startedAt
            proofOfLife()
        }

        fun requestTimedOut(startedAt: Long) {
            inFlightStartedAt -= startedAt
            streak.record()
        }

        fun evidence(windowMs: Long): AdminRpcPathEvidence {
            val now = clock()
            return AdminRpcPathEvidence(
                youngInFlight = inFlightStartedAt.count { now - it < IrohLivenessProbe.CONGESTION_GRACE_MS },
                oldestInFlightAgeMs = inFlightStartedAt.minOrNull()?.let { now - it },
                recentRequestTimeouts = streak.countWithin(windowMs),
                proofOfLifeAgeMs = now - lastProofOfLifeAt,
            )
        }

        fun handle(sessionId: String) = IrohConnectionHandle(
            config = IrohConnectConfig(baseShimUrl = "iroh://ticket", token = "", deviceId = "d", clientVersion = "t"),
            ticket = "ticket",
            sessionId = sessionId,
            adminRpcCall = { method, _, _ ->
                healthCheckStarts += clock()
                healthCheckGate?.await()
                AppServerInboundFrame.AdminRpcResponse(requestId = method, success = true, result = JsonPrimitive("ok"))
            },
            connectionAlive = { true },
            close = {},
        )
    }

    private class Harness(val test: TestScope, sessionId: String) {
        val lane = Lane { test.testScheduler.currentTime }
        val losses = mutableListOf<Long>()
        val handle = lane.handle(sessionId)
        val probe = IrohLivenessProbe(
            intervalMs = IrohLivenessProbe.INTERVAL_MS,
            timeoutMs = IrohLivenessProbe.TIMEOUT_MS,
            failuresToDeclareDead = IrohLivenessProbe.FAILURES_TO_DECLARE_DEAD,
            millisSinceLastProofOfLife = { lane.evidence(0L).proofOfLifeAgeMs },
            adminRpcPathEvidence = lane::evidence,
            runtime = IrohLivenessProbe.Runtime(scope = test.backgroundScope, clock = { test.testScheduler.currentTime }),
            reportConnectionLost = { _, _ -> losses += test.testScheduler.currentTime },
        )

        /** Runs the scenario body at virtual times relative to probe start. */
        fun at(ms: Long, action: () -> Unit) {
            test.backgroundScope.launch {
                delay(ms - test.testScheduler.currentTime)
                action()
            }
        }
    }

    private fun verdictsFor(sessionId: String): List<String> = Telemetry.events.value
        .filter { it.tag == "IrohLiveness" && it.attrs["sessionId"] == sessionId }
        .mapNotNull { it.attrs["verdict"] as? String }

    // ============================================================
    // 1. Black-holed path with in-flight admin_rpcs → dead, redial
    //    scheduled within 2 × probe timeout of the first probe.
    // ============================================================
    @Test
    fun blackHoledPathWithStalledInFlightRpcsIsDeclaredDead() = runTest {
        val h = Harness(this, "qygvv22-blackhole")
        h.lane.healthCheckGate = CompletableDeferred() // never answers
        // The last answer the phone got was at t=5 s; then the path goes dark and the
        // screen fires admin_rpcs that will never be answered.
        h.at(5_000) { h.lane.proofOfLife() }
        h.at(6_000) { h.lane.startRpc() }
        h.at(7_000) { h.lane.startRpc() }
        h.probe.start(h.handle)

        advanceTimeBy(100_000)
        runCurrent()

        val firstProbe = h.lane.healthCheckStarts.first()
        val declaredAt = assertNotNull(h.losses.singleOrNull(), "exactly one loss report; losses=${h.losses}")
        assertTrue(
            declaredAt - firstProbe <= 2 * IrohLivenessProbe.TIMEOUT_MS,
            "dead path must be declared within 2x probe timeout of the first probe; " +
                "firstProbe=$firstProbe declaredAt=$declaredAt",
        )
        assertTrue(
            verdictsFor("qygvv22-blackhole").all { it == ProbeTimeoutVerdict.OLDEST_IN_FLIGHT_STALLED.name },
            "in-flight RPCs that are themselves stalled must escalate, never soft-fail; " +
                "verdicts=${verdictsFor("qygvv22-blackhole")}",
        )
        h.probe.stop("test_end")
    }

    // ============================================================
    // 2. Back-to-back admin_rpc timeouts are dead-path evidence even
    //    while the remaining in-flight work is still young.
    // ============================================================
    @Test
    fun consecutiveRequestTimeoutsEscalateDespiteYoungInFlight() = runTest {
        val h = Harness(this, "qygvv22-rpc-timeouts")
        h.lane.healthCheckGate = CompletableDeferred()
        h.at(5_000) { h.lane.proofOfLife() }
        h.at(36_000) { h.lane.requestTimedOut(h.lane.startRpc()) }
        h.at(38_000) { h.lane.requestTimedOut(h.lane.startRpc()) }
        // Started during the first probe's wait: too young to be "stalled" on its own.
        h.at(45_000) { h.lane.startRpc() }
        h.probe.start(h.handle)

        advanceTimeBy(100_000)
        runCurrent()

        val firstProbe = h.lane.healthCheckStarts.first()
        val declaredAt = assertNotNull(h.losses.singleOrNull(), "exactly one loss report; losses=${h.losses}")
        assertTrue(declaredAt - firstProbe <= 2 * IrohLivenessProbe.TIMEOUT_MS, "declaredAt=$declaredAt first=$firstProbe")
        assertEquals(
            ProbeTimeoutVerdict.CONSECUTIVE_RPC_TIMEOUTS.name,
            verdictsFor("qygvv22-rpc-timeouts").first(),
            "two back-to-back request timeouts in the window must count as a dead path",
        )
        h.probe.stop("test_end")
    }

    // ============================================================
    // 3. parg0 preserved: congested-but-alive (an RPC completes during
    //    each probe window) never redials.
    // ============================================================
    @Test
    fun congestedButAlivePathDoesNotRedial() = runTest {
        val h = Harness(this, "qygvv22-congested")
        val gate = CompletableDeferred<Unit>()
        h.lane.healthCheckGate = gate
        // A long hydrate RPC stays in flight across the probe windows...
        var hydrate = 0L
        var second = 0L
        h.at(1_000) { hydrate = h.lane.startRpc() }
        h.at(40_000) { second = h.lane.startRpc() }
        // ...while other RPCs keep COMPLETING inside each probe's wait: the path answers.
        h.at(25_000) { h.lane.completeRpc(h.lane.startRpc()) }
        h.at(55_000) { h.lane.completeRpc(h.lane.startRpc()) }
        // Congestion clears.
        h.at(70_000) {
            h.lane.completeRpc(hydrate)
            h.lane.completeRpc(second)
            gate.complete(Unit)
        }
        h.probe.start(h.handle)

        advanceTimeBy(200_000)
        runCurrent()

        assertTrue(h.losses.isEmpty(), "a live-but-congested path must never be redialed; losses=${h.losses}")
        val verdicts = verdictsFor("qygvv22-congested")
        assertTrue(
            verdicts.isNotEmpty() && verdicts.all { it == ProbeTimeoutVerdict.RECENT_PROOF_OF_LIFE.name },
            "probe timeouts during congestion must be soft-failed on proof of life; verdicts=$verdicts",
        )
        h.probe.stop("test_end")
    }

    // ============================================================
    // 4. Normal path: every probe answers, nothing escalates.
    // ============================================================
    @Test
    fun healthyPathIsUnchanged() = runTest {
        val h = Harness(this, "qygvv22-healthy")
        h.probe.start(h.handle)

        advanceTimeBy(200_000)
        runCurrent()

        assertTrue(h.losses.isEmpty(), "healthy path must never be redialed; losses=${h.losses}")
        assertEquals(
            (200_000 / IrohLivenessProbe.INTERVAL_MS).toInt(),
            h.lane.healthCheckStarts.size,
            "one probe per interval, as before; starts=${h.lane.healthCheckStarts}",
        )
        assertTrue(verdictsFor("qygvv22-healthy").isEmpty())
        h.probe.stop("test_end")
    }

    // ============================================================
    // 5. Classifier table (pure).
    // ============================================================
    @Test
    fun classifierOrdersEvidenceCorrectly() {
        val timeout = 10_000L
        val window = 30_000L
        fun verdict(young: Int, oldest: Long?, timeouts: Int, proofAge: Long) =
            classifyProbeTimeout(AdminRpcPathEvidence(young, oldest, timeouts, proofAge), timeout, window)

        assertEquals(ProbeTimeoutVerdict.NO_IN_FLIGHT, verdict(0, null, 5, 60_000))
        assertEquals(ProbeTimeoutVerdict.RECENT_PROOF_OF_LIFE, verdict(3, 40_000, 2, 5_000))
        assertEquals(ProbeTimeoutVerdict.CONSECUTIVE_RPC_TIMEOUTS, verdict(1, 2_000, 2, 45_000))
        assertEquals(ProbeTimeoutVerdict.OLDEST_IN_FLIGHT_STALLED, verdict(1, 11_000, 1, 45_000))
        assertEquals(ProbeTimeoutVerdict.YOUNG_IN_FLIGHT, verdict(1, 4_000, 1, 45_000))
        assertNull(AdminRpcPathEvidence.idle(0).oldestInFlightAgeMs)
    }
}
