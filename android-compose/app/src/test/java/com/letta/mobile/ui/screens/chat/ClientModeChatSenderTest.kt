package com.letta.mobile.ui.screens.chat

import com.letta.mobile.bot.protocol.BotChatRequest
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.bot.protocol.InternalBotClient
import com.letta.mobile.clientmode.ClientModeController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class ClientModeChatSenderTest {
    @Test
    fun `fresh client mode send uses null conversation id plus force new`() = runTest {
        val internalBotClient = mockk<InternalBotClient>()
        val controller = mockk<ClientModeController>()
        val requestSlot = slot<BotChatRequest>()
        coEvery { controller.ensureReady() } returns Unit
        every { internalBotClient.streamMessage(capture(requestSlot)) } returns flowOf(
            BotStreamChunk(conversationId = "conv-new", done = true),
        )

        ClientModeChatSender(internalBotClient, controller)
            .streamMessage(
                screenAgentId = "agent-1",
                text = "hello",
                conversationId = null,
            )
            .toList()

        coVerify(exactly = 1) { controller.ensureReady() }
        verify(exactly = 1) { internalBotClient.streamMessage(any()) }
        assertNull(requestSlot.captured.conversationId)
        assertTrue(requestSlot.captured.forceNew)
    }

    @Test
    fun `existing client mode send does not force new conversation`() = runTest {
        val internalBotClient = mockk<InternalBotClient>()
        val controller = mockk<ClientModeController>()
        val requestSlot = slot<BotChatRequest>()
        coEvery { controller.ensureReady() } returns Unit
        every { internalBotClient.streamMessage(capture(requestSlot)) } returns flowOf(
            BotStreamChunk(conversationId = "conv-existing", done = true),
        )

        ClientModeChatSender(internalBotClient, controller)
            .streamMessage(
                screenAgentId = "agent-1",
                text = "hello",
                conversationId = "conv-existing",
            )
            .toList()

        coVerify(exactly = 1) { controller.ensureReady() }
        verify(exactly = 1) { internalBotClient.streamMessage(any()) }
        assertFalse(requestSlot.captured.forceNew)
    }
}
