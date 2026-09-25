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
    // WS strategy. Routing now keys on BackendKind (and since g70jb.4 there is
    // no shim backend kind at all).
    // ---------------------------------------------------------------------

    @Test
    fun `iroh backend selects the iroh strategy whatever the client mode flag`() {
        for (clientMode in listOf(false, true)) {
            val f = Fixture()
            val selected = f.selectFor(BackendKind.IROH, clientMode = clientMode)
            assertSame("clientMode=$clientMode", f.iroh, selected)
            assertNotSame(f.timeline, selected)
        }
    }

    @Test
    fun `only an iroh context uses the channel transport`() {
        for (kind in BackendKind.entries) {
            val context = ChatSendContext(
                isClientModeEnabled = false,
                explicitConversationId = null,
                backendKind = kind,
            )
            assertEquals("kind=$kind", kind == BackendKind.IROH, context.usesChannelTransport)
        }
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

        assertSame(f.local, f.selectFor(BackendKind.LOCAL_RUNTIME))
    }

    @Test
    fun `send delegates payload and context to selected strategy`() {
        val f = Fixture()
        val image = MessageContentPart.Image(base64 = "abc", mediaType = "image/png")
        val context = ChatSendContext(isClientModeEnabled = true, explicitConversationId = "conv-1")

        f.selector.send("hello", listOf(image), context)

        assertEquals(listOf(RecordedSend("hello", listOf(image), context)), f.timeline.sent)
        assertEquals(0, f.local.sent.size)
        assertEquals(0, f.iroh.sent.size)
    }

    @Test
    fun `send and cancel over an iroh backend never reach the timeline strategy`() {
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
        assertEquals(0, f.timeline.sent.size)
        assertEquals(0, f.timeline.cancels)
    }

    private class Fixture {
        val timeline = RecordingStrategy()
        val local = RecordingStrategy()
        val iroh = RecordingStrategy()
        val selector = ChatSendStrategySelector(
            timelineStrategy = timeline,
            localStrategy = local,
            irohStrategy = iroh,
        )

        fun selectFor(kind: BackendKind, clientMode: Boolean = false): ChatSendStrategy = selector.select(
            ChatSendContext(isClientModeEnabled = clientMode, explicitConversationId = null, backendKind = kind),
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
