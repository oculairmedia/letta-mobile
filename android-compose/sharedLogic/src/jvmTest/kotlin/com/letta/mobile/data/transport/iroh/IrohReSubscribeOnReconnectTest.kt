package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
/**
 * letta-mobile-r3i1z (A) MOBILE RE-SUBSCRIBE ON RECONNECT.
 *
 * Device-proven gap (2026-07-09): after a long-lived app's QUIC connection
 * times out and redials, the NEW connection is invisible to the server fanout —
 * server-side viewer registration only fires on runtime_start (send) or
 * admin_rpc message.list (hydrate). A passive observer that just sits viewing a
 * conversation, then silently redials, is never re-registered as a viewer, so
 * viewerCount drops to 1 (only the initiator) and the redialed app renders
 * nothing when the other client sends.
 *
 * FIX under test: on EVERY fresh Ready (a new connection generation — the same
 * signal the observer collector re-arms on), if a conversation is currently
 * being viewed, the transport RE-ISSUES an admin_rpc message.list for it with NO
 * user action. That single call re-registers the connection server-side (the
 * eaczz.3 onMethodObserved viewer hook) AND reconciles any frames missed during
 * the dead window (two birds — mirrors what a reconnecting observer should do
 * per eaczz.6).
 *
 * The "currently viewed conversation" is learned from the transport's OWN
 * message.list admin_rpc traffic (path /v1/conversations/<id>/messages): the
 * hydrate that first registered the viewer is exactly the call to replay on
 * reconnect. No new wiring / provider is required.
 */
class IrohReSubscribeOnReconnectTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val config = IrohConnectConfig(
        baseShimUrl = "iroh://ticket",
        token = "",
        deviceId = "device",
        clientVersion = "test",
    )

    @AfterTest
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private data class AdminRpcCall(val session: String, val method: String, val path: String)

    /**
     * A message.list path exactly as [IrohAdminRpcTimelineTransport] builds it
     * for the currently-viewed conversation hydrate.
     */
    private fun messageListPath(conversationId: String): String =
        "/v1/conversations/$conversationId/messages?limit=50&order=desc"

    // ============================================================
    // (A) fresh Ready after a redial re-issues message.list for the
    //     viewed conversation with NO user action.
    // ============================================================
    @Test
    fun freshReadyAfterRedialReSubscribesViewedConversation() = kotlinx.coroutines.runBlocking {
        val calls = CopyOnWriteArrayList<AdminRpcCall>()
        var dials = 0
        // health.check fails twice on session-1 so the transport escalates to a
        // redial (session-2). Crucially health.check is NOT message.list, so any
        // message.list observed on session-2 can ONLY come from auto-re-subscribe
        // — never from a replayed user hydrate.
        val healthFailures = java.util.concurrent.atomic.AtomicInteger(0)
        val transport = IrohChannelTransport(
            scope = scope,
            activeConfigProvider = { config },
            testDialer = { dialConfig ->
                dials += 1
                val session = "session-$dials"
                IrohConnectionHandle(
                    config = dialConfig,
                    ticket = "ticket",
                    sessionId = session,
                    adminRpcCall = { method, path, _ ->
                        calls += AdminRpcCall(session, method, path)
                        if (session == "session-1" && method == "health.check") {
                            // Fail on session-1 (original + same-connection retry)
                            // so the transport invalidates and redials.
                            healthFailures.incrementAndGet()
                            error("connection closed")
                        }
                        AppServerInboundFrame.AdminRpcResponse(
                            requestId = method,
                            success = true,
                            result = JsonPrimitive(session),
                        )
                    },
                    close = {},
                )
            },
        )
        transport.connect("iroh://ticket", "", "device", "test")

        try {
            // The user opens conversation conv-A: the timeline layer hydrates via
            // message.list on session-1. This is the ONLY user-driven message.list.
            val viewedConversation = "conv-A"
            val hydrate = withTimeout(5.seconds) {
                transport.adminRpc("message.list", messageListPath(viewedConversation), null)
            }
            assertTrue(hydrate.success, "initial hydrate on session-1 succeeded")

            // Now a background/read (health.check) hits a dead connection and the
            // transport silently redials to session-2 — NO user action toward the
            // conversation. Mirrors the device-observed "QUIC timed out, app
            // redialed" scenario.
            val health = withTimeout(5.seconds) {
                transport.adminRpc("health.check", "/v1/health", null)
            }
            assertTrue(health.success, "health.check resolved after redial")
            assertEquals(2, dials, "connection-lost read forced exactly one redial")

            // KEY assertion: without ANY further user action toward conv-A, the
            // fresh Ready on session-2 must auto-re-issue message.list for the
            // viewed conversation so the redialed connection re-registers as a
            // viewer server-side (the eaczz.3 onMethodObserved hook).
            val reSubscribeOnSession2 = withTimeoutOrNull(5.seconds) {
                while (true) {
                    val hit = calls.any { call ->
                        call.session == "session-2" &&
                            call.method == "message.list" &&
                            call.path.contains(viewedConversation)
                    }
                    if (hit) return@withTimeoutOrNull true
                    delay(20.milliseconds)
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            assertTrue(
                reSubscribeOnSession2 == true,
                "fresh Ready after redial must auto-re-issue message.list for the viewed " +
                    "conversation ($viewedConversation) on session-2 with no user action; " +
                    "observed calls=${calls.toList()}",
            )
        } finally {
            transport.disconnect()
        }
    }

    // ============================================================
    // (A) idempotent: with NO conversation ever viewed, a fresh Ready
    //     must NOT invent a message.list (nothing to re-subscribe to).
    // ============================================================
    @Test
    fun freshReadyWithNoViewedConversationDoesNotReSubscribe() = kotlinx.coroutines.runBlocking {
        val calls = CopyOnWriteArrayList<AdminRpcCall>()
        val transport = IrohChannelTransport(
            scope = scope,
            activeConfigProvider = { config },
            testDialer = { dialConfig ->
                IrohConnectionHandle(
                    config = dialConfig,
                    ticket = "ticket",
                    sessionId = "session",
                    adminRpcCall = { method, path, _ ->
                        calls += AdminRpcCall("session", method, path)
                        AppServerInboundFrame.AdminRpcResponse(
                            requestId = method, success = true, result = JsonPrimitive("ok"),
                        )
                    },
                    close = {},
                )
            },
        )
        // A plain connect (fresh Ready) with no conversation ever opened.
        transport.connect("iroh://ticket", "", "device", "test")
        try {
            withTimeout(2.seconds) {
                while (transport.state.value !is com.letta.mobile.data.transport.ChannelTransportState.Connected) delay(10.milliseconds)
            }
            // Give any (erroneous) auto re-subscribe a chance to fire.
            delay(500.milliseconds)
            assertTrue(
                calls.none { it.method == "message.list" },
                "no conversation viewed yet -> a fresh Ready must not synthesize a message.list; " +
                    "observed=${calls.toList()}",
            )
        } finally {
            transport.disconnect()
        }
    }
}
