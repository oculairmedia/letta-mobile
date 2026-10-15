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
    fun `selects timeline strategy for a direct ws backend`() {
        val f = Fixture()

        val selected = f.selector.select(
            ChatSendContext(explicitConversationId = "conv-1"),
        )

        assertSame(f.timeline.strategy, selected)
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
    fun `iroh backend selects the iroh strategy`() {
        val f = Fixture()
        val selected = f.selectFor(BackendKind.IROH)
        assertSame(f.iroh.strategy, selected)
        assertNotSame(f.timeline.strategy, selected)
    }

    @Test
    fun `only an iroh context uses the channel transport`() {
        for (kind in BackendKind.entries) {
            val context = ChatSendContext(
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
                    explicitConversationId = null,
                    backendKind = kind,
                    isLocalRuntime = true,
                ),
            )
            assertSame("local runtime must win for kind=$kind", f.local.strategy, selected)
        }
    }

    @Test
    fun `local runtime backend kind selects the local strategy`() {
        val f = Fixture()

        assertSame(f.local.strategy, f.selectFor(BackendKind.LOCAL_RUNTIME))
    }

    @Test
    fun `send delegates payload to selected strategy`() {
        val f = Fixture()
        val image = MessageContentPart.Image(base64 = "abc", mediaType = "image/png")
        val context = ChatSendContext(explicitConversationId = "conv-1")

        f.selector.send("hello", listOf(image), context)

        assertEquals(listOf(RecordedSend("hello", listOf(image))), f.timeline.sent)
        assertEquals(0, f.local.sent.size)
        assertEquals(0, f.iroh.sent.size)
    }

    @Test
    fun `send and cancel over an iroh backend never reach the timeline strategy`() {
        val f = Fixture()
        val context = ChatSendContext(
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
        val timeline = Recorder("timeline")
        val local = Recorder("local")
        val iroh = Recorder("iroh")
        val selector = ChatSendStrategySelector(
            timelineStrategy = timeline.strategy,
            localStrategy = local.strategy,
            irohStrategy = iroh.strategy,
        )

        fun selectFor(kind: BackendKind): ChatSendStrategy = selector.select(
            ChatSendContext(explicitConversationId = null, backendKind = kind),
        )
    }

    private class Recorder(routeName: String) {
        val sent = mutableListOf<RecordedSend>()
        var cancels = 0
            private set
        val strategy = ChatSendStrategy(
            routeName = routeName,
            sendVia = { text, attachments -> sent += RecordedSend(text, attachments); Job() },
            cancelVia = { cancels++ },
        )
    }

    private data class RecordedSend(
        val text: String,
        val attachments: List<MessageContentPart.Image>,
    )
}
