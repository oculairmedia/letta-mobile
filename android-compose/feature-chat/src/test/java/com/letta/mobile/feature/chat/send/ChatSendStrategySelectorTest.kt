package com.letta.mobile.feature.chat.send

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.MessageContentPart
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * letta-mobile-by4vm.110.6: every Android backend (Iroh, ws://, the on-device runtime) is served by
 * the session's App Server channel, so every send and every stop takes the one channel route.
 */
class ChatSendStrategySelectorTest {
    @Test
    fun `every backend kind sends and stops through the channel route`() {
        for (kind in BackendKind.entries) {
            val channel = Recorder()
            val selector = ChatSendStrategySelector(channel.strategy)
            val image = MessageContentPart.Image(base64 = "abc", mediaType = "image/png")

            selector.send("hello", listOf(image), ChatSendContext(explicitConversationId = "conv-1", backendKind = kind))
            selector.cancel()

            assertEquals("kind=$kind", listOf(RecordedSend("hello", listOf(image))), channel.sent)
            assertEquals("kind=$kind", 1, channel.cancels)
        }
    }

    private class Recorder {
        val sent = mutableListOf<RecordedSend>()
        var cancels = 0
            private set
        val strategy = ChatSendStrategy(
            routeName = "channel",
            sendVia = { text, attachments -> sent += RecordedSend(text, attachments); Job() },
            cancelVia = { cancels++ },
        )
    }

    private data class RecordedSend(
        val text: String,
        val attachments: List<MessageContentPart.Image>,
    )
}
