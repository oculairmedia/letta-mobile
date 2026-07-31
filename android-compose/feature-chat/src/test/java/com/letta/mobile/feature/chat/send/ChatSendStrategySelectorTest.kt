package com.letta.mobile.feature.chat.send

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.MessageContentPart
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ChatSendStrategySelectorTest {
    @Test
    fun `selects timeline strategy for a plain REST backend`() {
        val f = Fixture()

        val selected = f.selector.select(
            ChatSendContext(isClientModeEnabled = true, explicitConversationId = "conv-1"),
        )

        assertSame(f.timeline, selected)
    }

    @Test
    fun `selects timeline strategy when client mode is disabled`() {
        val f = Fixture()

        val selected = f.selector.select(
            ChatSendContext(isClientModeEnabled = false, explicitConversationId = null),
        )

        assertSame(f.timeline, selected)
    }

    // ---------------------------------------------------------------------
    // letta-mobile-lgns8.10.4.1 — the routing inversion.
    //
    // Before this bead ShimBackendDetector reported `isShimBackend = true` for
    // Iroh backends, so the live production transport selected the shim-shaped
    // WS strategy. Routing now keys on BackendKind. These cases pin BOTH
    // directions: reverting the selector to the isShimBackend key fails them.
    // ---------------------------------------------------------------------

    @Test
    fun `iroh backend selects the iroh strategy and never the shim ws strategy`() {
        val f = Fixture()

        val selected = f.selector.select(
            ChatSendContext(
                isClientModeEnabled = false,
                explicitConversationId = null,
                backendKind = BackendKind.IROH,
            ),
        )

        assertSame(f.iroh, selected)
        assertNotSame(f.ws, selected)
    }

    @Test
    fun `iroh backend selects the iroh strategy even when client mode flag is set`() {
        val f = Fixture()

        val selected = f.selector.select(
            ChatSendContext(
                isClientModeEnabled = true,
                explicitConversationId = null,
                backendKind = BackendKind.IROH,
            ),
        )

        assertSame(f.iroh, selected)
        assertNotSame(f.ws, selected)
    }

    @Test
    fun `shim ws backend still selects the shim ws strategy`() {
        val f = Fixture()

        val selected = f.selector.select(
            ChatSendContext(
                isClientModeEnabled = false,
                explicitConversationId = null,
                backendKind = BackendKind.SHIM_WS,
            ),
        )

        assertSame(f.ws, selected)
        assertNotSame(f.iroh, selected)
    }

    @Test
    fun `an iroh context is never a shim context`() {
        val iroh = ChatSendContext(
            isClientModeEnabled = false,
            explicitConversationId = null,
            backendKind = BackendKind.IROH,
        )
        val shim = iroh.copy(backendKind = BackendKind.SHIM_WS)

        // Both stream frames...
        assertEquals(true, iroh.usesChannelTransport)
        assertEquals(true, shim.usesChannelTransport)
        // ...but only one of them is the shim.
        assertEquals(false, iroh.isShimBackend)
        assertEquals(true, shim.isShimBackend)
    }

    @Test
    fun `local runtime routing wins over every backend kind`() {
        for (kind in BackendKind.entries) {
            val f = Fixture()
            val selected = f.selector.select(
                ChatSendContext(
                    isClientModeEnabled = false,
                    explicitConversationId = null,
                    backendKind = kind,
                    isLocalRuntime = true,
                ),
            )
            assertSame("local runtime must win for kind=$kind", f.local, selected)
        }
    }

    @Test
    fun `local runtime backend kind selects the local strategy`() {
        val f = Fixture()

        val selected = f.selector.select(
            ChatSendContext(
                isClientModeEnabled = false,
                explicitConversationId = null,
                backendKind = BackendKind.LOCAL_RUNTIME,
            ),
        )

        assertSame(f.local, selected)
    }

    @Test
    fun `send delegates payload and context to selected strategy`() {
        val f = Fixture()
        val image = MessageContentPart.Image(base64 = "abc", mediaType = "image/png")
        val context = ChatSendContext(isClientModeEnabled = true, explicitConversationId = "conv-1")

        f.selector.send("hello", listOf(image), context)

        assertEquals(listOf(RecordedSend("hello", listOf(image), context)), f.timeline.sent)
        assertEquals(0, f.ws.sent.size)
        assertEquals(0, f.local.sent.size)
        assertEquals(0, f.iroh.sent.size)
    }

    @Test
    fun `send and cancel over an iroh backend never reach the shim ws strategy`() {
        val f = Fixture()
        val context = ChatSendContext(
            isClientModeEnabled = false,
            explicitConversationId = "conv-1",
            backendKind = BackendKind.IROH,
        )

        f.selector.send("hello", emptyList(), context)
        f.selector.cancel(context)

        assertEquals(1, f.iroh.sent.size)
        assertEquals(1, f.iroh.cancels)
        assertEquals(0, f.ws.sent.size)
        assertEquals(0, f.ws.cancels)
    }

    private class Fixture {
        val timeline = RecordingStrategy()
        val ws = RecordingStrategy()
        val local = RecordingStrategy()
        val iroh = RecordingStrategy()
        val selector = ChatSendStrategySelector(
            timelineStrategy = timeline,
            wsStrategy = ws,
            localStrategy = local,
            irohStrategy = iroh,
        )
    }

    private class RecordingStrategy : ChatSendStrategy {
        val sent = mutableListOf<RecordedSend>()
        var cancels = 0
            private set

        override fun send(
            text: String,
            attachments: List<MessageContentPart.Image>,
            context: ChatSendContext,
        ): Job {
            sent += RecordedSend(text, attachments, context)
            return Job()
        }

        override fun cancel() {
            cancels++
        }
    }

    private data class RecordedSend(
        val text: String,
        val attachments: List<MessageContentPart.Image>,
        val context: ChatSendContext,
    )
}
