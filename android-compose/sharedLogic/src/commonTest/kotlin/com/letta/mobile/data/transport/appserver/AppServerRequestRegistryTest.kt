package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.JsonObject

class AppServerRequestRegistryTest {

    private val registryScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val controlChannel = Channel<AppServerReceivedFrame>(Channel.UNLIMITED)
    private val controlFlow = controlChannel.receiveAsFlow()

    private fun registry(timeoutMs: Long = 30_000L): AppServerRequestRegistry {
        val r = AppServerRequestRegistry(controlFrames = controlFlow, timeoutMs = timeoutMs)
        r.startRouting(registryScope)
        return r
    }

    @Test
    fun basicRequestResponseCorrelation() = runTest {
        val registry = registry()
        val response = registry.request(
            requestId = "req-1",
            response = { it as? AppServerInboundFrame.RuntimeStartResponse },
            send = { emitResponse("req-1", runtimeStartResponse("req-1")) },
        )
        assertIs<AppServerInboundFrame.RuntimeStartResponse>(response)
        assertEquals("req-1", response.requestId)
    }

    @Test
    fun outOfOrderConcurrentResponsesCorrelateCorrectly() = runTest {
        val registry = registry()
        var respA: AppServerInboundFrame.RuntimeStartResponse? = null
        var respB: AppServerInboundFrame.SyncResponse? = null
        val barrier = CompletableDeferred<Unit>()

        coroutineScope {
            launch {
                respA = registry.request(
                    requestId = "a",
                    response = { it as? AppServerInboundFrame.RuntimeStartResponse },
                    send = { barrier.await() },
                )
            }
            launch {
                respB = registry.request(
                    requestId = "b",
                    response = { it as? AppServerInboundFrame.SyncResponse },
                    send = { barrier.await() },
                )
            }
            emitResponse("b", syncResponse("b"))
            emitResponse("a", runtimeStartResponse("a"))
            barrier.complete(Unit)
        }
        assertEquals("b", respB?.requestId)
        assertEquals("a", respA?.requestId)
    }

    @Test
    fun duplicateRequestIdFailsClosed() = runTest {
        val registry = registry()
        // Register first request — send blocks until barrier completes.
        val barrier = CompletableDeferred<Unit>()
        val deferred = CompletableDeferred<AppServerInboundFrame.RuntimeStartResponse>()
        val job = launch {
            deferred.complete(
                registry.request(
                    requestId = "dup",
                    response = { it as? AppServerInboundFrame.RuntimeStartResponse },
                    send = { barrier.await() },
                ),
            )
        }
        runCurrent() // advance so the launch enters registry.request and registers
        val ex = assertFailsWith<IllegalStateException> {
            registry.request<AppServerInboundFrame.SyncResponse>(
                requestId = "dup",
                response = { it as? AppServerInboundFrame.SyncResponse },
                send = { },
            )
        }
        assertTrue(ex.message!!.contains("duplicate"))
        barrier.complete(Unit)
    }

    @Test
    fun wrongResponseTypeDoesNotResolveCall() = runTest {
        val registry = registry()
        val barrier = CompletableDeferred<Unit>()
        var gotCorrectType = false
        val job = launch {
            try {
                registry.request<AppServerInboundFrame.SyncResponse>(
                    requestId = "wrong-type",
                    response = { it as? AppServerInboundFrame.SyncResponse },
                    send = { barrier.await() },
                )
                gotCorrectType = true
            } catch (_: kotlinx.coroutines.CancellationException) {
                // not expected
            }
        }
        emitResponse("wrong-type", runtimeStartResponse("wrong-type"))
        emitResponse("wrong-type", syncResponse("wrong-type"))
        barrier.complete(Unit)
        job.join()
        assertTrue(gotCorrectType, "wrong type should not resolve, correct type should")
    }

    @Test
    fun timeoutCleansEntry() = runTest {
        val registry = registry(timeoutMs = 100)
        assertFailsWith<AppServerRequestTimeoutException> {
            registry.request<AppServerInboundFrame.SyncResponse>(
                requestId = "timeout-req",
                response = { it as? AppServerInboundFrame.SyncResponse },
                send = { /* never completes */ },
            )
        }
        val response = registry.request(
            requestId = "timeout-req",
            response = { it as? AppServerInboundFrame.SyncResponse },
            send = { emitResponse("timeout-req", syncResponse("timeout-req")) },
        )
        assertEquals("timeout-req", response.requestId)
    }

    @Test
    fun callerCancellationCleansEntry() = runTest {
        val registry = registry()
        val barrier = CompletableDeferred<Unit>()
        val job = launch {
            registry.request<AppServerInboundFrame.SyncResponse>(
                requestId = "cancel-me",
                response = { it as? AppServerInboundFrame.SyncResponse },
                send = { barrier.await() },
            )
        }
        job.cancelAndJoin()
        val response = registry.request(
            requestId = "cancel-me",
            response = { it as? AppServerInboundFrame.SyncResponse },
            send = { emitResponse("cancel-me", syncResponse("cancel-me")) },
        )
        assertEquals("cancel-me", response.requestId)
    }

    @Test
    fun sendFailureCleansEntry() = runTest {
        val registry = registry()
        assertFailsWith<RuntimeException> {
            registry.request<AppServerInboundFrame.SyncResponse>(
                requestId = "send-fail",
                response = { it as? AppServerInboundFrame.SyncResponse },
                send = { throw RuntimeException("send boom") },
            )
        }
        val response = registry.request(
            requestId = "send-fail",
            response = { it as? AppServerInboundFrame.SyncResponse },
            send = { emitResponse("send-fail", syncResponse("send-fail")) },
        )
        assertEquals("send-fail", response.requestId)
    }

    @Test
    fun disconnectFailsAllPendingImmediately() = runTest {
        val registry = registry()
        val barrier = CompletableDeferred<Unit>()
        var aFailed = false
        var bFailed = false
        val jobA = launch {
            try {
                registry.request<AppServerInboundFrame.RuntimeStartResponse>(
                    requestId = "a",
                    response = { it as? AppServerInboundFrame.RuntimeStartResponse },
                    send = { barrier.await() },
                )
                fail("expected AppServerRequestFailedException")
            } catch (_: AppServerRequestFailedException) {
                aFailed = true
            }
        }
        val jobB = launch {
            try {
                registry.request<AppServerInboundFrame.SyncResponse>(
                    requestId = "b",
                    response = { it as? AppServerInboundFrame.SyncResponse },
                    send = { barrier.await() },
                )
                fail("expected AppServerRequestFailedException")
            } catch (_: AppServerRequestFailedException) {
                bFailed = true
            }
        }
        delay(10)
        registry.failAll(RuntimeException("connection lost"))
        barrier.complete(Unit)
        jobA.join()
        jobB.join()
        assertTrue(aFailed)
        assertTrue(bFailed)
    }

    @Test
    fun disconnectPreventsNewRequests() = runTest {
        val registry = registry()
        registry.failAll(RuntimeException("connection lost"))
        assertFailsWith<IllegalStateException> {
            registry.request<AppServerInboundFrame.SyncResponse>(
                requestId = "after-disconnect",
                response = { it as? AppServerInboundFrame.SyncResponse },
                send = { },
            )
        }
    }

    @Test
    fun failAllIsIdempotent() = runTest {
        val registry = registry()
        registry.failAll(RuntimeException("first"))
        registry.failAll(RuntimeException("second"))
    }

    @Test
    fun replayedOutOfOrderResponsesDoNotResolveLate() = runTest {
        val registry = registry(timeoutMs = 30_000)
        val barrier = CompletableDeferred<Unit>()
        val job = launch {
            registry.request<AppServerInboundFrame.SyncResponse>(
                requestId = "late",
                response = { it as? AppServerInboundFrame.SyncResponse },
                send = { barrier.await() },
            )
        }
        job.cancelAndJoin()
        emitResponse("late", syncResponse("late"))
        val response = registry.request(
            requestId = "other",
            response = { it as? AppServerInboundFrame.SyncResponse },
            send = { emitResponse("other", syncResponse("other")) },
        )
        assertEquals("other", response.requestId)
    }

    private suspend fun emitResponse(requestId: String, frame: AppServerInboundFrame) {
        controlChannel.send(
            AppServerReceivedFrame(
                channel = AppServerChannel.Control,
                frame = frame,
                raw = JsonObject(emptyMap()),
            ),
        )
    }

    private fun runtimeStartResponse(requestId: String) =
        AppServerInboundFrame.RuntimeStartResponse(
            requestId = requestId,
            success = true,
            runtime = AppServerRuntimeScope(agentId = "agent-test", conversationId = "conv-test"),
        )

    private fun syncResponse(requestId: String) =
        AppServerInboundFrame.SyncResponse(
            requestId = requestId,
            runtime = AppServerRuntimeScope(agentId = "agent-test", conversationId = "conv-test"),
            success = true,
        )
}
