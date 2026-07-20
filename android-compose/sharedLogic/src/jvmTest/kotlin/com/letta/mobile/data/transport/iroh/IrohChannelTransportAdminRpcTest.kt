package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
@OptIn(ExperimentalCoroutinesApi::class)
class IrohChannelTransportAdminRpcTest {
    private val config = IrohConnectConfig("iroh://ticket", "token", "device", "client")

    @Test
    fun cancellationDuringAdminRpcRethrowsWithoutFallback() = runTest {
        var dials = 0
        var calls = 0
        val cancellation = CancellationException("cancelled while opening per-request stream")
        val transport = transportWithHandles {
            dials += 1
            fakeHandle("dial-$dials") { _, _, _ ->
                calls += 1
                throw cancellation
            }
        }

        val thrown = assertFailsWith<CancellationException> {
            transport.adminRpc("message.list", "/messages", null)
        }

        assertSame(cancellation, thrown)
        assertEquals(1, dials)
        assertEquals(1, calls)
    }

    @Test
    fun mutatingAdminRpcFailureAfterWriteIsNotReplayed() = runTest {
        var dials = 0
        var calls = 0
        val transport = transportWithHandles {
            dials += 1
            fakeHandle("dial-$dials") { _, _, _ ->
                calls += 1
                throw IllegalStateException("stream timed out after request write")
            }
        }

        val thrown = assertFailsWith<IllegalStateException> {
            transport.adminRpc("goal.command", "/goals/goal-1/command", """{"command":"archive"}""")
        }

        assertTrue(thrown.message.orEmpty().contains("timed out"))
        assertEquals(1, dials)
        assertEquals(1, calls)
    }

    @Test
    fun readOnlyAdminRpcMethodsFallbackToLegacyControlChannel() = runTest {
        val methods = listOf(
            "message.list",
            "message.get",
            "conversation.list",
            "goal.get",
            "health.check",
        )

        methods.forEach { method ->
            var dials = 0
            var calls = 0
            val transport = transportWithHandles {
                dials += 1
                fakeHandle("dial-$dials") { calledMethod, _, _ ->
                    calls += 1
                    assertEquals(method, calledMethod)
                    // letta-mobile-34xoj: first call fails, retry on SAME connection succeeds
                    if (calls == 1) throw IllegalStateException("stream closed after old node rejected admin_rpc stream")
                    response("retry-$method", success = true, result = JsonPrimitive("ok:$method"))
                }
            }

            val pending = async { transport.adminRpc(method, "/admin/$method", null) }
            runCurrent()
            advanceTimeBy(1_000)
            advanceUntilIdle()
            val result = pending.await()

            assertEquals(true, result.success, method)
            assertEquals(JsonPrimitive("ok:$method"), result.result, method)
            // letta-mobile-34xoj: only 1 dial (retry on same connection, no reconnect)
            assertEquals(1, dials, method)
            assertEquals(2, calls, method) // first attempt + retry on same connection
        }
    }

    @Test
    fun payloadSizeErrorFailsOnlyTheRequestWithoutRedialOrClose() = runTest {
        // k7yyc: a frame-size (payload) rejection on a list-sized read must fail
        // ONLY that request — no supervisor invalidation, no redial, no close of
        // the shared connection. A single oversized list response cannot be
        // allowed to tear down streaming for every other request.
        var dials = 0
        var calls = 0
        val closed = mutableListOf<String>()
        val sizeError = IrohFrameCodec.FrameTooLargeException("Iroh frame too large: 2000000 bytes > max 1048576")
        val transport = transportWithHandles {
            dials += 1
            val sessionId = "dial-$dials"
            fakeHandle(sessionId, close = { reason -> closed += "$sessionId:$reason" }) { _, _, _ ->
                calls += 1
                throw sizeError
            }
        }

        val thrown = assertFailsWith<IrohFrameCodec.FrameTooLargeException> {
            transport.adminRpc("message.list", "/messages", null)
        }
        advanceUntilIdle()

        assertSame(sizeError, thrown)
        assertEquals(1, dials)
        assertEquals(1, calls)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun decodeErrorFailsOnlyTheRequestWithoutRedialOrClose() = runTest {
        // k7yyc: a decode (protocol) error is a per-request payload fault, not a
        // transport fault — fail only the request, never reconnect.
        var dials = 0
        var calls = 0
        val closed = mutableListOf<String>()
        val decodeError = IrohFrameCodec.ProtocolException("admin_rpc expected admin_rpc_response but received garbage")
        val transport = transportWithHandles {
            dials += 1
            val sessionId = "dial-$dials"
            fakeHandle(sessionId, close = { reason -> closed += "$sessionId:$reason" }) { _, _, _ ->
                calls += 1
                throw decodeError
            }
        }

        val thrown = assertFailsWith<IrohFrameCodec.ProtocolException> {
            transport.adminRpc("conversation.list", "/conversations", null)
        }
        advanceUntilIdle()

        assertSame(decodeError, thrown)
        assertEquals(1, dials)
        assertEquals(1, calls)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun unknownMethodReturnsFailureEnvelopeWithoutThrowing() = runTest {
        val transport = transportWithHandles {
            fakeHandle("dial-1") { method, _, _ ->
                response(
                    requestId = "unknown-$method",
                    success = false,
                    error = "Unknown method: $method",
                )
            }
        }

        val result = transport.adminRpc("unknown.method", "/admin/unknown", null)

        assertFalse(result.success)
        assertEquals("Unknown method: unknown.method", result.error)
    }

    @Test
    fun parallelReadOnlyAdminRpcCallsCompleteIndependently() = runTest {
        val delayedCallEntered = CompletableDeferred<Unit>()
        val transport = transportWithHandles {
            fakeHandle("dial-1") { _, path, _ ->
                if (path == "/messages/slow") {
                    delayedCallEntered.complete(Unit)
                    delay(1.seconds)
                }
                response(path, success = true, result = JsonPrimitive(path))
            }
        }

        val calls = (0 until 10).map { index ->
            val path = if (index == 7) "/messages/slow" else "/messages/$index"
            async { transport.adminRpc("message.get", path, null) }
        }
        delayedCallEntered.await()
        runCurrent()

        assertEquals(9, calls.count { it.isCompleted })
        assertFalse(calls[7].isCompleted)

        advanceTimeBy(1_000)
        advanceUntilIdle()
        assertEquals((0 until 10).map { index -> if (index == 7) "/messages/slow" else "/messages/$index" }, calls.map { it.await().result?.jsonPrimitiveContent() })
    }

    @Test
    fun connectionLostAdminRpcRetryInvalidatesSupervisorAndUsesNewHandle() = runTest {
        val sessions = mutableListOf<String>()
        val closed = mutableListOf<String>()
        var dials = 0
        val transport = transportWithHandles {
            dials += 1
            val sessionId = "dial-$dials"
            fakeHandle(sessionId, close = { reason -> closed += "$sessionId:$reason" }) { _, _, _ ->
                sessions += sessionId
                // letta-mobile-34xoj: both attempts on dial-1 fail, triggering escalation
                if (sessionId == "dial-1") throw IllegalStateException("connection reset by peer")
                response(sessionId, success = true, result = JsonPrimitive(sessionId))
            }
        }

        val pending = async { transport.adminRpc("message.list", "/messages", null) }
        runCurrent()
        advanceTimeBy(1_000)
        advanceUntilIdle()
        val result = pending.await()

        assertEquals(JsonPrimitive("dial-2"), result.result)
        // letta-mobile-34xoj: dial-1 tried twice (original + retry), then escalated to dial-2
        assertEquals(listOf("dial-1", "dial-1", "dial-2"), sessions)
        assertTrue(closed.any { it.startsWith("dial-1:admin_rpc_failed_after_retry") })
    }

    @Test
    fun parallelAdminRpcCallsCompleteIndependently() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = IrohChannelTransport(
            scope = scope,
            activeConfigProvider = { config },
            testDialer = { dialConfig ->
                IrohConnectionHandle(
                    config = dialConfig,
                    ticket = "ticket",
                    sessionId = "session",
                    adminRpcCall = { method, _, _ ->
                        if (method == "slow.method") delay(500.milliseconds)
                        AppServerInboundFrame.AdminRpcResponse(
                            requestId = method,
                            success = true,
                            result = JsonPrimitive(method),
                        )
                    },
                    close = {},
                )
            },
        )

        try {
            val calls = (0 until 10).map { index ->
                async {
                    val method = if (index == 0) "slow.method" else "fast.method.$index"
                    method to transport.adminRpc(method, "/v1/test", null)
                }
            }

            val fast = withTimeout(250.milliseconds) { calls.drop(1).awaitAll() }
            assertEquals(9, fast.size)
            assertTrue(fast.all { (_, response) -> response.success })
            assertTrue(calls.first().await().second.success)
        } finally {
            transport.disconnect()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun unknownMethodResponseReturnsEnvelope() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = IrohChannelTransport(
            scope = scope,
            activeConfigProvider = { config },
            testDialer = { dialConfig ->
                IrohConnectionHandle(
                    config = dialConfig,
                    ticket = "ticket",
                    sessionId = "session",
                    adminRpcCall = { _, _, _ ->
                        AppServerInboundFrame.AdminRpcResponse(
                            requestId = "unknown",
                            success = false,
                            error = "Unknown method: missing.method",
                        )
                    },
                    close = {},
                )
            },
        )

        try {
            val response = transport.adminRpc("missing.method", "/v1/missing", null)
            assertFalse(response.success)
            assertEquals("Unknown method: missing.method", response.error)
        } finally {
            transport.disconnect()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun connectionLostDuringAdminRpcInvalidatesSupervisorAndRetriesWithNewHandle() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var dials = 0
        val closed = mutableListOf<String>()
        val transport = IrohChannelTransport(
            scope = scope,
            activeConfigProvider = { config },
            testDialer = { dialConfig ->
                dials += 1
                val dialNumber = dials
                IrohConnectionHandle(
                    config = dialConfig,
                    ticket = "ticket",
                    sessionId = "session-$dialNumber",
                    adminRpcCall = { _, _, _ ->
                        if (dialNumber == 1) error("connection closed")
                        AppServerInboundFrame.AdminRpcResponse(
                            requestId = "retry",
                            success = true,
                            result = JsonPrimitive("session-$dialNumber"),
                        )
                    },
                    close = { reason -> closed += "session-$dialNumber:$reason" },
                )
            },
        )

        try {
            val response = withTimeout(3.seconds) { transport.adminRpc("message.list", "/v1/retry", null) }
            assertTrue(response.success)
            assertEquals(JsonPrimitive("session-2"), response.result)
            assertEquals(2, dials)
            assertTrue(closed.any { it.startsWith("session-1:admin_rpc_failed") })
        } finally {
            transport.disconnect()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    private fun TestScope.transportWithHandles(
        dialer: suspend (IrohConnectConfig) -> IrohConnectionHandle,
    ): IrohChannelTransport = IrohChannelTransport(
        scope = this,
        activeConfigProvider = { config },
        testDialer = dialer,
    )

    private fun fakeHandle(
        sessionId: String,
        close: suspend (String) -> Unit = {},
        adminRpc: suspend (method: String, path: String, body: String?) -> AppServerInboundFrame.AdminRpcResponse,
    ): IrohConnectionHandle = IrohConnectionHandle(
        config = config,
        ticket = "ticket",
        sessionId = sessionId,
        adminRpcCall = adminRpc,
        close = close,
    )

    private fun response(
        requestId: String,
        success: Boolean,
        result: kotlinx.serialization.json.JsonElement? = null,
        error: String? = null,
    ): AppServerInboundFrame.AdminRpcResponse = AppServerInboundFrame.AdminRpcResponse(
        requestId = requestId,
        success = success,
        result = result,
        error = error,
    )

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String =
        (this as JsonPrimitive).content

    // letta-mobile-34xoj: admin_rpc timeout during active turn must NOT cancel
    // the live event collector or emit transport reconnect
    @Test
    fun adminRpcTimeoutDuringActiveTurnDoesNotCancelLiveEventCollector() = runTest {
        var dials = 0
        var adminRpcCalls = 0
        val closed = mutableListOf<String>()

        val transport = transportWithHandles {
            dials += 1
            val sessionId = "dial-$dials"
            fakeHandle(sessionId, close = { reason -> closed += "$sessionId:$reason" }) { _, _, _ ->
                adminRpcCalls += 1
                // First admin_rpc call throws timeout-like error
                if (adminRpcCalls == 1) {
                    throw IllegalStateException("admin_rpc timed out for method=message.list")
                }
                // Retry succeeds on same connection
                response("retry-success", success = true, result = JsonPrimitive("ok"))
            }
        }

        // Call admin_rpc (simulating message.list during streaming turn)
        val result = transport.adminRpc("message.list", "/messages", null)

        // Verify retry succeeded on same connection
        assertTrue(result.success)
        assertEquals(JsonPrimitive("ok"), result.result)
        // Only 1 dial (no reconnect)
        assertEquals(1, dials)
        // 2 admin_rpc calls (original timeout + retry)
        assertEquals(2, adminRpcCalls)
        // No connection closed
        assertTrue(closed.isEmpty())
    }

    @Test
    fun adminRpcRetrySucceedsOnSecondAttemptOverSameConnection() = runTest {
        var calls = 0
        val transport = transportWithHandles {
            fakeHandle("dial-1") { _, _, _ ->
                calls += 1
                if (calls == 1) throw IllegalStateException("connection timed out")
                // Retry on same connection succeeds
                response("retry-$calls", success = true, result = JsonPrimitive("retry-ok"))
            }
        }

        val result = transport.adminRpc("message.list", "/messages", null)

        assertTrue(result.success)
        assertEquals(JsonPrimitive("retry-ok"), result.result)
        assertEquals(2, calls)
    }

    @Test
    fun adminRpcEscalatesReconnectOnlyAfterThresholdWithIdleStream() = runTest {
        var dials = 0
        var calls = 0
        val closed = mutableListOf<String>()

        val transport = transportWithHandles {
            dials += 1
            val sessionId = "dial-$dials"
            fakeHandle(sessionId, close = { reason -> closed += "$sessionId:$reason" }) { _, _, _ ->
                calls += 1
                // First connection: first call fails, retry succeeds
                if (calls == 1 && sessionId == "dial-1") {
                    throw IllegalStateException("timeout $calls")
                }
                response("ok-$calls", success = true, result = JsonPrimitive("ok"))
            }
        }

        // Make a call that fails then succeeds on retry (consecutive failures reset to 0)
        val result1 = transport.adminRpc("message.list", "/messages", null)
        assertTrue(result1.success, "call 1 should succeed after retry")

        // Still on dial-1 (no reconnect, retry succeeded)
        assertEquals(1, dials)
        assertEquals(2, calls) // fail + retry-success
        assertTrue(closed.isEmpty())
    }

    @Test
    fun adminRpcEscalatesAfterMultipleFailuresWhenStreamIdle() = runTest {
        var dials = 0
        var calls = 0
        val closed = mutableListOf<String>()
        val startTimeMs = System.currentTimeMillis()

        val transport = transportWithHandles {
            dials += 1
            val sessionId = "dial-$dials"
            fakeHandle(sessionId, close = { reason -> closed += "$sessionId:$reason" }) { _, _, _ ->
                calls += 1
                // First connection: all calls fail (simulating degraded connection)
                if (sessionId == "dial-1") {
                    throw IllegalStateException("connection degraded")
                }
                // Second connection: succeeds
                response("ok-$calls", success = true, result = JsonPrimitive("reconnected"))
            }
        }

        // First call: fails, retries on same connection, retry also fails -> escalates to reconnect
        val result1 = transport.adminRpc("message.list", "/messages", null)
        
        advanceUntilIdle()

        // After both attempts failed, should have escalated to reconnect
        assertTrue(result1.success, "should succeed after reconnect")
        assertEquals(2, dials, "should have escalated to reconnect")
        assertTrue(closed.any { it.contains("dial-1") }, "first connection should be closed")
        // 2 calls on dial-1 (original + retry), 1 call on dial-2
        assertEquals(3, calls)
    }

    @Test
    fun adminRpcDoesNotEscalateWhenStreamIsActive() = runTest {
        var dials = 0
        var calls = 0
        val closed = mutableListOf<String>()

        val transport = transportWithHandles {
            dials += 1
            val sessionId = "dial-$dials"
            fakeHandle(sessionId, close = { reason -> closed += "$sessionId:$reason" }) { _, _, _ ->
                calls += 1
                // Fail first attempt, succeed on retry
                if (calls % 2 == 1) throw IllegalStateException("timeout")
                response("ok-$calls", success = true, result = JsonPrimitive("ok"))
            }
        }

        // Simulate active stream by having successful calls in between
        // (In real code, emitBoth() records stream activity)
        repeat(5) { index ->
            val result = transport.adminRpc("message.list", "/messages", null)
            assertTrue(result.success, "call $index should succeed after retry")
            advanceTimeBy(100) // Small delay, stream still "active"
        }

        // Should still be on first connection (no escalation due to active stream)
        assertEquals(1, dials)
        assertEquals(10, calls) // 5 pairs of (fail, retry-success)
        assertTrue(closed.isEmpty())
    }
}
