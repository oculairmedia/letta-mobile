package com.letta.mobile.desktop.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopChatSendUiSinkTest {

    private class FakeSurface(var selected: String? = "conv-1") : DesktopChatSendSurface {
        @JvmField var error: String? = null
        @JvmField var streaming: String? = null
        @JvmField var thinking: String? = null
        override fun currentError() = error
        override fun setError(message: String?) { error = message }
        override fun streamingConversationId() = streaming
        override fun thinkingConversationId() = thinking
        override fun setStreaming(conversationId: String?) { streaming = conversationId }
        override fun setThinking(conversationId: String?) { thinking = conversationId }
        override fun selectedConversationId() = selected
    }

    private fun sink(surface: FakeSurface) = DesktopChatSendUiSink(surface)

    @Test fun aDispatchStartsTheTurnOnItsOwnConversationAndClearsTheError() {
        val surface = FakeSurface().apply { error = "stale" }
        sink(surface).onSendDispatched("conv-2")
        assertEquals("conv-2", surface.streaming)
        assertEquals("conv-2", surface.thinking)
        assertNull(surface.error)
    }

    /** A send into the conversation on screen carries no id; the indicator must still attach. */
    @Test fun aDispatchWithoutAConversationFallsBackToTheSelection() {
        val surface = FakeSurface(selected = "conv-9")
        sink(surface).onSendDispatched(null)
        assertEquals("conv-9", surface.streaming)
        assertEquals("conv-9", surface.thinking)
    }

    @Test fun aDispatchWithNoConversationAtAllStartsNothing() {
        val surface = FakeSurface(selected = null)
        sink(surface).onSendDispatched(null)
        assertNull(surface.streaming)
        assertNull(surface.thinking)
    }

    @Test fun aFailedSendStopsTheTurnAndSurfacesTheReason() {
        val surface = FakeSurface().apply { streaming = "conv-1"; thinking = "conv-1" }
        sink(surface).onSendFailed("nope")
        assertNull(surface.streaming)
        assertNull(surface.thinking)
        assertEquals("nope", surface.error)
    }

    /** An error can arrive while the turn continues, so it must not stop the indicator. */
    @Test fun aMidTurnErrorLeavesTheTurnRunning() {
        val surface = FakeSurface().apply { streaming = "conv-1"; thinking = "conv-1" }
        sink(surface).onError("tool failed")
        assertEquals("conv-1", surface.streaming)
        assertEquals("conv-1", surface.thinking)
        assertEquals("tool failed", surface.error)
    }

    @Test fun aTurnFinishingCarriesItsErrorThrough() {
        val surface = FakeSurface().apply { streaming = "conv-1"; thinking = "conv-1" }
        sink(surface).onTurnFinished("provider error")
        assertNull(surface.streaming)
        assertEquals("provider error", surface.error)
    }

    /** A stop reason must not erase a failure the turn already reported. */
    @Test fun visualCompletionLeavesAnExistingErrorAlone() {
        val surface = FakeSurface().apply { streaming = "conv-1"; thinking = "conv-1"; error = "already failed" }
        sink(surface).onTurnVisuallyComplete()
        assertNull(surface.streaming)
        assertNull(surface.thinking)
        assertEquals("already failed", surface.error)
    }

    /** A reconnect that will succeed is not the end of the turn; the reply is still coming. */
    @Test fun aTransientDisconnectHoldsTheIndicatorWhileASendIsInFlight() {
        val surface = FakeSurface().apply { streaming = "conv-1"; thinking = "conv-1"; error = "blip" }
        sink(surface).onTransientDisconnect(hasActiveSend = true)
        assertEquals("conv-1", surface.streaming)
        assertEquals("conv-1", surface.thinking)
        assertNull(surface.error)
    }

    @Test fun aTransientDisconnectWithNothingInFlightStopsTheIndicator() {
        val surface = FakeSurface().apply { streaming = "conv-1"; thinking = "conv-1" }
        sink(surface).onTransientDisconnect(hasActiveSend = false)
        assertNull(surface.streaming)
        assertNull(surface.thinking)
    }

    @Test fun aTerminalDisconnectFailsTheTurn() {
        val surface = FakeSurface().apply { streaming = "conv-1"; thinking = "conv-1" }
        sink(surface).onDisconnectFailure("connection lost")
        assertNull(surface.streaming)
        assertNull(surface.thinking)
        assertEquals("connection lost", surface.error)
    }

    /** A delta for another conversation moves the turn there rather than leaving it stranded. */
    @Test fun aDeltaForAnotherConversationMovesTheIndicator() {
        val surface = FakeSurface().apply { streaming = "conv-1"; thinking = "conv-1" }
        sink(surface).onMessageDelta("conv-2")
        assertEquals("conv-2", surface.streaming)
        assertEquals("conv-2", surface.thinking)
    }

    @Test fun readbackReportsWhatTheSurfaceHolds() {
        val surface = FakeSurface().apply { error = "e"; streaming = "conv-1" }
        val sink = sink(surface)
        assertEquals("e", sink.currentError())
        assertTrue(sink.isStreaming())
        assertFalse(sink.isAgentTyping())
    }
}
