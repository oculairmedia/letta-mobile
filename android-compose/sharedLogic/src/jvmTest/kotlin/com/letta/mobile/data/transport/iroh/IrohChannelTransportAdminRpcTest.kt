package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrohChannelTransportAdminRpcTest {
    private val config = IrohConnectConfig("iroh://ticket", "", "device", "test")

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
                        if (method == "slow.method") delay(500)
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

            val fast = withTimeout(250) { calls.drop(1).awaitAll() }
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
            val response = withTimeout(3_000) { transport.adminRpc("retry.method", "/v1/retry", null) }
            assertTrue(response.success)
            assertEquals(JsonPrimitive("session-2"), response.result)
            assertEquals(2, dials)
            assertTrue(closed.any { it.startsWith("session-1:admin_rpc_failed") })
        } finally {
            transport.disconnect()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
