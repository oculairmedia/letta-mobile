package com.letta.mobile.data.chat.recompile

import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** letta-mobile-zxk2n.5 (G92): `/recompile` shows queued, then its answer; a gateway without a route says why. */
class ConversationRecompileControllerTest {
    private val request = ConversationRecompileRequest("conv-1", "agent-1")

    @Test
    fun queuedThenSucceeded() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val gateway = FakeGateway { gate.await() }
        val controller = ConversationRecompileController(this, { gateway })

        controller.recompile(request)
        assertEquals(RecompileStatus.Queued, controller.status.value)
        controller.recompile(request) // a second Enter while queued sends nothing
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(RecompileStatus.Succeeded, controller.status.value)
        assertEquals(1, gateway.calls)
        assertEquals("System prompt recompiled", controller.status.value.message())
        controller.dismiss()
        assertNull(controller.status.value.message())
    }

    @Test
    fun aRefusalCarriesTheServersReason() = runTest(StandardTestDispatcher()) {
        val controller = ConversationRecompileController(this, { FakeGateway { error("no memory blocks") } })
        controller.recompile(request)
        advanceUntilIdle()
        assertEquals(RecompileStatus.Failed("no memory blocks"), controller.status.value)
    }

    @Test
    fun aTimeoutMayStillFinish() = runTest(StandardTestDispatcher()) {
        val controller = ConversationRecompileController(this, { FakeGateway { throw AppServerRequestTimeoutException("rc", TIMEOUT_MS, IllegalStateException("slow")) } })
        controller.recompile(request)
        advanceUntilIdle()
        assertEquals(RecompileStatus.StillRunning, controller.status.value)
    }

    @Test
    fun noRouteIsUnavailableWithTheHostsReason() = runTest(StandardTestDispatcher()) {
        val noRoute = ConversationRecompileController(this, { null }, { ConversationRecompileController.UNAVAILABLE_OVER_IROH })
        noRoute.recompile(request)
        assertEquals(RecompileStatus.Unavailable(ConversationRecompileController.UNAVAILABLE_OVER_IROH), noRoute.status.value)

        val wrapped = ConversationRecompileController(this, { FakeGateway { throw UnsupportedOperationException("x") } }, { "use REST" })
        wrapped.recompile(request)
        advanceUntilIdle()
        assertEquals(RecompileStatus.Unavailable("use REST"), wrapped.status.value)
    }

    private class FakeGateway(private val answer: suspend () -> Unit) : ConversationRecompileGateway {
        var calls = 0
        override suspend fun recompileConversation(request: ConversationRecompileRequest) {
            calls++
            answer()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
