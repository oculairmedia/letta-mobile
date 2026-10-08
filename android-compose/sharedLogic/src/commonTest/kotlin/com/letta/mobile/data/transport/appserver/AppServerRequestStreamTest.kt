package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** letta-mobile-bzvro.24: multi-frame responses (`list_memory` pages) through the request registry. */
class AppServerRequestStreamTest {
    private val controlChannel = Channel<AppServerReceivedFrame>(Channel.UNLIMITED)

    private fun TestScope.registry(timeoutMs: Long = 30_000L): AppServerRequestRegistry {
        val registry = AppServerRequestRegistry(controlFrames = controlChannel.receiveAsFlow(), timeoutMs = timeoutMs)
        registry.startRouting(backgroundScope)
        runCurrent()
        return registry
    }

    private fun page(requestId: String, index: Int, done: Boolean): AppServerReceivedFrame {
        val raw = JsonObject(
            mapOf(
                "type" to JsonPrimitive("list_memory_response"),
                "request_id" to JsonPrimitive(requestId),
                "page" to JsonPrimitive(index),
                "done" to JsonPrimitive(done),
            ),
        )
        return AppServerReceivedFrame(
            channel = AppServerChannel.Control,
            frame = AppServerInboundFrame.Unknown(type = "list_memory_response", raw = raw),
            raw = raw,
        )
    }

    private suspend fun AppServerRequestRegistry.listMemory(requestId: String, send: suspend () -> Unit) =
        requestStream(
            requestId = requestId,
            response = { it as? AppServerInboundFrame.Unknown },
            isFinal = { isFinalWorkspaceFrame(it.raw) },
            send = send,
        )

    @Test
    fun collectsEveryPageUpToTheFinalOne() = runTest {
        val registry = registry()
        val pages = registry.listMemory("list-1") {
            controlChannel.send(page("list-1", 0, done = false))
            controlChannel.send(page("list-1", 1, done = false))
            controlChannel.send(page("list-1", 2, done = true))
        }
        assertEquals(listOf(0, 1, 2), pages.map { (it.raw["page"] as JsonPrimitive).content.toInt() })
    }

    @Test
    fun framesForOtherRequestsAreNotCollected() = runTest {
        val registry = registry()
        val pages = registry.listMemory("mine") {
            controlChannel.send(page("other", 0, done = false))
            controlChannel.send(page("mine", 0, done = true))
        }
        assertEquals(1, pages.size)
    }

    @Test
    fun aStalledListingSurfacesATimeoutInsteadOfAPartialResult() = runTest {
        val registry = registry(timeoutMs = 1_000L)
        val result = async {
            runCatching { registry.listMemory("slow") { controlChannel.send(page("slow", 0, done = false)) } }
        }
        advanceTimeBy(1_500L)
        runCurrent()
        val error = result.await().exceptionOrNull()
        assertTrue(error is AppServerRequestTimeoutException, "expected a timeout, got $error")
    }

    @Test
    fun eachPageResetsTheWaitSoALongListingCompletes() = runTest {
        val registry = registry(timeoutMs = 1_000L)
        val result = async { registry.listMemory("long") {} }
        runCurrent()
        repeat(4) { index ->
            advanceTimeBy(800L)
            controlChannel.send(page("long", index, done = index == 3))
            runCurrent()
        }
        assertEquals(4, result.await().size)
    }

    @Test
    fun aFailedGenerationFailsTheOpenStream() = runTest {
        val registry = registry()
        val result = async {
            runCatching { registry.listMemory("doomed") { controlChannel.send(page("doomed", 0, done = false)) } }
        }
        runCurrent()
        registry.failAll(IllegalStateException("socket closed"))
        assertFailsWith<AppServerRequestFailedException> { result.await().getOrThrow() }
    }

    @Test
    fun aMissingDoneFieldEndsTheStream() {
        assertTrue(isFinalWorkspaceFrame(JsonObject(emptyMap())))
    }
}
