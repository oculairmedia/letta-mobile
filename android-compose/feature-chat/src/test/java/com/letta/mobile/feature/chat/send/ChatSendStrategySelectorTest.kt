package com.letta.mobile.feature.chat.send

import com.letta.mobile.data.model.MessageContentPart
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChatSendStrategySelectorTest {
    @Test
    fun `selects client mode strategy when client mode is enabled`() {
        val timeline = RecordingStrategy()
        val clientMode = RecordingStrategy()
        val ws = RecordingStrategy()
        val selector = ChatSendStrategySelector(timeline, clientMode, ws)

        val selected = selector.select(ChatSendContext(isClientModeEnabled = true, explicitConversationId = "conv-1"))

        assertSame(clientMode, selected)
    }

    @Test
    fun `selects timeline strategy when client mode is disabled`() {
        val timeline = RecordingStrategy()
        val clientMode = RecordingStrategy()
        val ws = RecordingStrategy()
        val selector = ChatSendStrategySelector(timeline, clientMode, ws)

        val selected = selector.select(ChatSendContext(isClientModeEnabled = false, explicitConversationId = null))

        assertSame(timeline, selected)
    }

    @Test
    fun `selects ws strategy when shim backend is active and client mode is disabled`() {
        val timeline = RecordingStrategy()
        val clientMode = RecordingStrategy()
        val ws = RecordingStrategy()
        val selector = ChatSendStrategySelector(timeline, clientMode, ws)

        val selected = selector.select(
            ChatSendContext(
                isClientModeEnabled = false,
                explicitConversationId = null,
                isShimBackend = true,
            )
        )

        assertSame(ws, selected)
    }

    @Test
    fun `client mode takes precedence over shim backend`() {
        val timeline = RecordingStrategy()
        val clientMode = RecordingStrategy()
        val ws = RecordingStrategy()
        val selector = ChatSendStrategySelector(timeline, clientMode, ws)

        val selected = selector.select(
            ChatSendContext(
                isClientModeEnabled = true,
                explicitConversationId = null,
                isShimBackend = true,
            )
        )

        assertSame(clientMode, selected)
    }

    @Test
    fun `send delegates payload and context to selected strategy`() {
        val timeline = RecordingStrategy()
        val clientMode = RecordingStrategy()
        val ws = RecordingStrategy()
        val selector = ChatSendStrategySelector(timeline, clientMode, ws)
        val image = MessageContentPart.Image(base64 = "abc", mediaType = "image/png")
        val context = ChatSendContext(isClientModeEnabled = true, explicitConversationId = "conv-1")

        selector.send("hello", listOf(image), context)

        assertEquals(0, timeline.sent.size)
        assertEquals(listOf(RecordedSend("hello", listOf(image), context)), clientMode.sent)
    }

    private class RecordingStrategy : ChatSendStrategy {
        val sent = mutableListOf<RecordedSend>()
        override fun send(
            text: String,
            attachments: List<MessageContentPart.Image>,
            context: ChatSendContext,
        ): Job {
            sent += RecordedSend(text, attachments, context)
            return Job()
        }

        override fun cancel() = Unit
    }

    private data class RecordedSend(
        val text: String,
        val attachments: List<MessageContentPart.Image>,
        val context: ChatSendContext,
    )
}
