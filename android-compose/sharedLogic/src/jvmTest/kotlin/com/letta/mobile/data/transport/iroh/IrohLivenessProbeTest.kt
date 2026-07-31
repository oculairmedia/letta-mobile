package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * letta-mobile-wxy4s: APPLICATION-LEVEL CONNECTION LIVENESS.
 *
 * PRODUCTION INCIDENT (2026-07-31, ~40 min perceived outage): a host reboot
 * silently killed both clients' QUIC connections. NOTHING client-side noticed.
 * IrohAppServerTransport's only two loss reporters are the `connection.closed()`
 * watcher and reader-loop exit — neither can fire when the peer vanishes without
 * sending a CONNECTION_CLOSE (reboot / NAT rebind / black-hole). Worse, the 15s
 * UNACKED keepalive DATAGRAM succeeds locally regardless of peer liveness and
 * keeps resetting the local QUIC idle timer (RFC 9000 §10.1 resets on send OR
 * receive), so even the idle timeout that would eventually have killed the
 * connection never fired. The keepalive actively MASKED the death.
 *
 * FIX under test: while Ready, the transport periodically issues a `health.check`
 * admin_rpc through the connection HANDLE. That call opens a FRESH QUIC bidi
 * stream — precisely the operation a black-holed path fails and an unacked
 * datagram can never test. N consecutive failures report the loss into the
 * EXISTING supervisor redial machinery, attributed to the handle that died.
 *
 * FAIL-ON-REVERT: every test here drives the PUBLIC constructor + connect(), so
 * deleting the arm site in the supervisor's onStateChanged block makes
 * [probeFailureRedials] fail. No test instantiates a probe object directly.
 */
class IrohLivenessProbeTest {
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

    // Compressed cadence: the production defaults (20s/5s) are asserted
    // separately by IrohLivenessProbeWiringTest.
    private val probeIntervalMs = 300L
    private val probeTimeoutMs = 150L

    private data class ProbeCall(val session: String, val method: String, val atMs: Long)

    /**
     * A transport whose dialer hands out numbered sessions. [hangHealthCheckOn]
     * names the session whose `health.check` BLACK-HOLES (accepts the call and
     * never answers) — exactly the shape of the production incident: the write
     * succeeds locally, nothing ever comes back.
     */
    private fun transportWith(
        calls: MutableList<ProbeCall>,
        dials: AtomicInteger,
        hangHealthCheckOn: String?,
        observerStream: MutableSharedFlow<AppServerReceivedFrame>? = null,
        intervalMs: Long = probeIntervalMs,
    ): IrohChannelTransport = IrohChannelTransport(
        scope = scope,
        activeConfigProvider = { config },
        testDialer = { dialConfig ->
            val session = "session-${dials.incrementAndGet()}"
            IrohConnectionHandle(
                config = dialConfig,
                ticket = "ticket",
                sessionId = session,
                observerStreamFrames = observerStream,
                adminRpcCall = { method, _, _ ->
                    calls += ProbeCall(session, method, System.currentTimeMillis())
                    if (session == hangHealthCheckOn && method == "health.check") {
                        // Black hole: never completes. The caller MUST impose its
                        // own bound (the legacy control-channel fallback would
                        // otherwise stretch this to 30s/60s).
                        delay(600_000L)
                    }
                    AppServerInboundFrame.AdminRpcResponse(
                        requestId = method,
                        success = true,
                        result = JsonPrimitive(session),
                    )
                },
                connectionAlive = { true },
                close = {},
            )
        },
        livenessProbeIntervalMs = intervalMs,
        livenessProbeTimeoutMs = probeTimeoutMs,
        livenessProbeFailuresToDeclareDead = 2,
    )

    private suspend fun awaitTrue(timeout: kotlin.time.Duration = 10.seconds, predicate: () -> Boolean): Boolean =
        withTimeoutOrNull(timeout) {
            while (!predicate()) delay(20.milliseconds)
            true
        } == true

    // ============================================================
    // 1. A black-holed connection is DETECTED and REDIALED.
    //    (The incident: this reported nothing for 40 minutes.)
    // ============================================================
    @Test
    fun probeFailureRedials() = runBlocking {
        val calls = CopyOnWriteArrayList<ProbeCall>()
        val dials = AtomicInteger(0)
        val states = CopyOnWriteArrayList<ChannelTransportState>()
        val transport = transportWith(calls, dials, hangHealthCheckOn = "session-1")
        val stateCollector = scope.launch { transport.state.collect { states += it } }
        transport.connect("iroh://ticket", "", "device", "test")
        try {
            assertTrue(
                awaitTrue { dials.get() >= 2 },
                "a black-holed connection must be detected by the liveness probe and redialed; " +
                    "dials=${dials.get()} calls=${calls.toList()}",
            )
            assertTrue(
                awaitTrue {
                    states.any { it is ChannelTransportState.Disconnected && it.willReconnect }
                },
                "the loss must surface as Disconnected(willReconnect=true) so UI can show " +
                    "'reconnecting' instead of silently rendering cached data; states=${states.toList()}",
            )
            assertTrue(
                calls.any { it.session == "session-1" && it.method == "health.check" },
                "the probe must actually issue health.check on the dead session; calls=${calls.toList()}",
            )
        } finally {
            stateCollector.cancel()
            transport.disconnect()
        }
    }

    // ============================================================
    // 2. A HEALTHY connection is never redialed by the probe.
    // ============================================================
    @Test
    fun probeSuccessDoesNotRedial() = runBlocking {
        val calls = CopyOnWriteArrayList<ProbeCall>()
        val dials = AtomicInteger(0)
        val transport = transportWith(calls, dials, hangHealthCheckOn = null)
        transport.connect("iroh://ticket", "", "device", "test")
        try {
            // Several probe intervals of a healthy peer.
            assertTrue(
                awaitTrue { calls.count { it.method == "health.check" } >= 3 },
                "the probe must run periodically while Ready; calls=${calls.toList()}",
            )
            assertEquals(
                1, dials.get(),
                "a healthy connection must never be torn down by the liveness probe",
            )
            assertTrue(
                transport.state.value is ChannelTransportState.Connected,
                "state stays Connected across healthy probes; state=${transport.state.value}",
            )
        } finally {
            transport.disconnect()
        }
    }

    // ============================================================
    // 3. Live stream traffic is already proof of life — never probe
    //    (or escalate) mid-turn.
    // ============================================================
    @Test
    fun probeSkippedWhileStreamActive() = runBlocking {
        val calls = CopyOnWriteArrayList<ProbeCall>()
        val dials = AtomicInteger(0)
        val stream = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        // A deliberately long interval so the pump is provably flowing before the
        // first probe would ever be due (otherwise the test races its own setup,
        // not the production skip).
        val intervalMs = 1_000L
        // health.check would black-hole if it were ever issued: if the skip is
        // broken, this test redials mid-stream (the regression it guards).
        val transport = transportWith(
            calls, dials, hangHealthCheckOn = "session-1", observerStream = stream, intervalMs = intervalMs,
        )
        val observed = AtomicInteger(0)
        val eventCollector = scope.launch { transport.events.collect { observed.incrementAndGet() } }
        transport.connect("iroh://ticket", "", "device", "test")
        val pump = scope.launch {
            var seq = 1L
            while (true) {
                stream.emit(streamDelta("agent-1", "conv-live", seq, assistantDelta("cm-$seq", "chunk $seq")))
                seq += 1
                delay(60.milliseconds)
            }
        }
        try {
            withTimeout(2.seconds) {
                while (transport.state.value !is ChannelTransportState.Connected) delay(10.milliseconds)
            }
            // Frames are demonstrably reaching the transport's emit seam (which is
            // what records stream activity) before any probe is due.
            assertTrue(awaitTrue(5.seconds) { observed.get() > 0 }, "stream frames are flowing")
            // Now well past several probe intervals of CONTINUOUS stream activity.
            delay((intervalMs * 4).milliseconds)
            assertEquals(
                0, calls.count { it.method == "health.check" },
                "continuous stream traffic already proves liveness — the probe must be skipped; " +
                    "calls=${calls.toList()}",
            )
            assertEquals(1, dials.get(), "no mid-turn redial while frames are flowing")
        } finally {
            pump.cancel()
            eventCollector.cancel()
            transport.disconnect()
        }
    }

    // ============================================================
    // 4. r3i1z REGRESSION GUARD: a probe loss is attributed to the
    //    handle that died and must never destroy the redialed one.
    // ============================================================
    @Test
    fun probeLossAttributedToOwnHandle() = runBlocking {
        val calls = CopyOnWriteArrayList<ProbeCall>()
        val dials = AtomicInteger(0)
        val transport = transportWith(calls, dials, hangHealthCheckOn = "session-1")
        transport.connect("iroh://ticket", "", "device", "test")
        try {
            assertTrue(awaitTrue { dials.get() >= 2 }, "dead session-1 redials to session-2")
            // The new generation must take over probing...
            assertTrue(
                awaitTrue { calls.count { it.session == "session-2" && it.method == "health.check" } >= 2 },
                "the probe must re-arm against the redialed handle; calls=${calls.toList()}",
            )
            val session1ProbesAfterRedial = calls.count { it.session == "session-1" && it.method == "health.check" }
            // ...and the superseded loop must be gone, so it can neither probe nor
            // report a loss that would tear down the healthy new connection.
            delay(probeIntervalMs.milliseconds * 4)
            assertEquals(
                session1ProbesAfterRedial,
                calls.count { it.session == "session-1" && it.method == "health.check" },
                "the superseded probe loop must stop touching the dead handle; calls=${calls.toList()}",
            )
            assertEquals(
                2, dials.get(),
                "exactly ONE redial: an unattributed loss report landing after the redial would " +
                    "have discarded the healthy session-2 handle (the r3i1z regression)",
            )
        } finally {
            transport.disconnect()
        }
    }

    // ============================================================
    // 5. Not Ready => not probing. No probe traffic after disconnect.
    // ============================================================
    @Test
    fun probeStopsWhenNotReady() = runBlocking {
        val calls = CopyOnWriteArrayList<ProbeCall>()
        val dials = AtomicInteger(0)
        val transport = transportWith(calls, dials, hangHealthCheckOn = null)
        transport.connect("iroh://ticket", "", "device", "test")
        assertTrue(
            awaitTrue { calls.any { it.method == "health.check" } },
            "probe is armed while Ready",
        )
        transport.disconnect()
        val afterDisconnect = calls.count { it.method == "health.check" }
        delay(probeIntervalMs.milliseconds * 4)
        assertEquals(
            afterDisconnect,
            calls.count { it.method == "health.check" },
            "a disconnected transport must issue zero further probes; calls=${calls.toList()}",
        )
    }

    // ============================================================
    // 6. TRAP GUARD: health.check is in isLegacyFallbackSafeAdminRpcMethod,
    //    so an unbounded probe call inherits the 30s legacy control-channel
    //    timeout (worst case ~60s per probe). The probe MUST bound itself.
    // ============================================================
    @Test
    fun probeTimeoutIsBoundedNotLegacyFallback() = runBlocking {
        val calls = CopyOnWriteArrayList<ProbeCall>()
        val dials = AtomicInteger(0)
        val transport = transportWith(calls, dials, hangHealthCheckOn = "session-1")
        val startedAt = System.currentTimeMillis()
        transport.connect("iroh://ticket", "", "device", "test")
        try {
            assertTrue(awaitTrue { dials.get() >= 2 }, "black-holed probe escalates")
            val elapsed = System.currentTimeMillis() - startedAt
            // 2 failures x (interval + timeout) + backoff ~= 1.7s here. Inheriting
            // the legacy fallback would make a SINGLE probe take 30-60s.
            assertTrue(
                elapsed < 10_000,
                "a hung health.check must resolve within the probe's own timeout, not the 30s " +
                    "legacy control-channel fallback; elapsed=${elapsed}ms",
            )
        } finally {
            transport.disconnect()
        }
    }

    // ============================================================
    // 7. LIVENESS, NOT HEALTH: any answer from the peer proves the
    //    path is alive. A payload fault (frame too large) or an
    //    application-level error means the round trip COMPLETED, and
    //    must never be escalated into a redial — the same rule
    //    adminRpc's request-isolation guard already applies.
    // ============================================================
    @Test
    fun probeAnswerFromPeerIsNeverEscalated() = runBlocking {
        val calls = CopyOnWriteArrayList<ProbeCall>()
        val dials = AtomicInteger(0)
        val transport = IrohChannelTransport(
            scope = scope,
            activeConfigProvider = { config },
            testDialer = { dialConfig ->
                val session = "session-${dials.incrementAndGet()}"
                IrohConnectionHandle(
                    config = dialConfig,
                    ticket = "ticket",
                    sessionId = session,
                    adminRpcCall = { method, _, _ ->
                        calls += ProbeCall(session, method, System.currentTimeMillis())
                        // The peer ANSWERED — with a frame the codec rejects.
                        throw IrohFrameCodec.FrameTooLargeException(
                            "Iroh frame too large: 2000000 bytes > max 1048576",
                        )
                    },
                    connectionAlive = { true },
                    close = {},
                )
            },
            livenessProbeIntervalMs = probeIntervalMs,
            livenessProbeTimeoutMs = probeTimeoutMs,
            livenessProbeFailuresToDeclareDead = 2,
        )
        transport.connect("iroh://ticket", "", "device", "test")
        try {
            assertTrue(
                awaitTrue { calls.count { it.method == "health.check" } >= 3 },
                "the probe keeps running against a reachable peer; calls=${calls.toList()}",
            )
            assertEquals(
                1, dials.get(),
                "a payload/application error proves the peer answered — escalating it would " +
                    "redial forever against a perfectly reachable connection",
            )
        } finally {
            transport.disconnect()
        }
    }

    // ---- fanned-out stream frame helpers (shape per IrohObserverIngestionTest) ----

    private fun streamDelta(
        agentId: String,
        conversationId: String,
        seq: Long,
        delta: String,
    ): AppServerReceivedFrame {
        val body = """
            {
              "type": "stream_delta",
              "runtime": {"agent_id": "$agentId", "conversation_id": "$conversationId"},
              "event_seq": $seq,
              "emitted_at": "2026-07-31T00:00:00Z",
              "idempotency_key": "live-$conversationId-$seq",
              "delta": $delta
            }
        """.trimIndent()
        return AppServerProtocol.decodeFrame(body, AppServerChannel.Stream)
    }

    private fun assistantDelta(id: String, content: String) =
        """{"message_type": "assistant_message", "id": "$id", "content": "$content"}"""
}
