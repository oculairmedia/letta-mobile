package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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

    private val controlChannel = Channel<AppServerReceivedFrame>(Channel.UNLIMITED)
    private val controlFlow = controlChannel.receiveAsFlow()

    // Run the registry collector on backgroundScope, which shares the test's
    // scheduler/virtual clock, so correlation is deterministic. runCurrent()
    // after startRouting ensures the collector has subscribed to the channel
    // before any emit; tests advance the scheduler to drain responses.
    private fun TestScope.registry(timeoutMs: Long = 30_000L): AppServerRequestRegistry {
        val r = AppServerRequestRegistry(controlFrames = controlFlow, timeoutMs = timeoutMs)
        r.startRouting(backgroundScope)
        runCurrent()
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

        val jobA = launch {
            respA = registry.request(
                requestId = "a",
                response = { it as? AppServerInboundFrame.RuntimeStartResponse },
                send = { barrier.await() },
            )
        }
        val jobB = launch {
            respB = registry.request(
                requestId = "b",
                response = { it as? AppServerInboundFrame.SyncResponse },
                send = { barrier.await() },
            )
        }
        runCurrent() // both requests register their pending entries

        // Deliver responses out of order: B before A.
        controlChannel.send(received(syncResponse("b")))
        controlChannel.send(received(runtimeStartResponse("a")))
        barrier.complete(Unit)
        advanceUntilIdle() // sends complete, collector correlates each by request_id
        jobA.join()
        jobB.join()

        assertEquals("b", respB?.requestId)
        assertEquals("a", respA?.requestId)
    }

    @Test
    fun duplicateRequestIdFailsClosed() = runTest {
        val registry = registry()
        // First request registers, then blocks in its send on the barrier.
        val barrier = CompletableDeferred<Unit>()
        val job = launch {
            registry.request(
                requestId = "dup",
                response = { it as? AppServerInboundFrame.RuntimeStartResponse },
                send = { barrier.await() },
            )
        }
        runCurrent() // advance so the launch enters registry.request and registers

        // A second request with the same id must fail closed.
        val ex = assertFailsWith<IllegalStateException> {
            registry.request<AppServerInboundFrame.SyncResponse>(
                requestId = "dup",
                response = { it as? AppServerInboundFrame.SyncResponse },
                send = { },
            )
        }
        assertTrue(ex.message!!.contains("duplicate"))

        // Unblock and resolve the first request so no coroutine leaks.
        barrier.complete(Unit)
        controlChannel.send(received(runtimeStartResponse("dup")))
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun wrongResponseTypeDoesNotResolveCall() = runTest {
        val registry = registry()
        val barrier = CompletableDeferred<Unit>()
        var result: AppServerInboundFrame.SyncResponse? = null
        val job = launch {
            result = registry.request(
                requestId = "wrong-type",
                response = { it as? AppServerInboundFrame.SyncResponse },
                send = { barrier.await() },
            )
        }
        runCurrent() // request registers its pending entry

        // A matching-id but wrong-type response must NOT resolve the entry.
        controlChannel.send(received(runtimeStartResponse("wrong-type")))
        runCurrent()
        assertTrue(job.isActive, "wrong-type response must leave the request pending")

        // The correct-type response resolves it.
        controlChannel.send(received(syncResponse("wrong-type")))
        barrier.complete(Unit)
        advanceUntilIdle()
        job.join()
        assertEquals("wrong-type", result?.requestId)
    }

    @Test
    fun timeoutCleansEntry() = runTest {
        val registry = registry(timeoutMs = 100)
        var timedOut = false
        val job = launch {
            try {
                registry.request<AppServerInboundFrame.SyncResponse>(
                    requestId = "timeout-req",
                    response = { it as? AppServerInboundFrame.SyncResponse },
                    send = { /* completes; no response ever arrives */ },
                )
            } catch (_: AppServerRequestTimeoutException) {
                timedOut = true
            }
        }
        advanceUntilIdle() // virtual time passes the 100ms timeout
        job.join()
        assertTrue(timedOut, "request must time out when no response arrives")

        // The id is freed for reuse after timeout.
        val response = registry.request(
            requestId = "timeout-req",
            response = { it as? AppServerInboundFrame.SyncResponse },
            send = { controlChannel.send(received(syncResponse("timeout-req"))) },
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
        runCurrent() // both requests register their pending entries
        registry.failAll(RuntimeException("connection lost"))
        barrier.complete(Unit)
        advanceUntilIdle()
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

    private fun received(frame: AppServerInboundFrame) =
        AppServerReceivedFrame(
            channel = AppServerChannel.Control,
            frame = frame,
            raw = JsonObject(emptyMap()),
        )

    private suspend fun emitResponse(requestId: String, frame: AppServerInboundFrame) {
        controlChannel.send(received(frame))
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

    @Test
    fun parentCancellationPropagatesInsteadOfMislabelingAsThisRequestTimeout() = runTest {
        // This request's OWN timeout is long; an OUTER withTimeout fires first
        // (as the native-admin breaker's short bound does). Without the
        // ensureActive() guard, request() catches the outer's
        // TimeoutCancellationException and rethrows AppServerRequestTimeoutException
        // — mislabeled with THIS request's 120s timeoutMs and converting a
        // cancellation into a plain Exception. The outer cancellation must instead
        // propagate as a TimeoutCancellationException.
        val registry = registry(timeoutMs = 120_000L)
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(1_000L) {
                registry.request(
                    requestId = "parent-cancel",
                    response = { it as? AppServerInboundFrame.RuntimeStartResponse },
                    // Never emit a response — force the OUTER 1s timeout to fire
                    // before this request's own 120s bound.
                    send = { },
                )
            }
        }
    }
}
