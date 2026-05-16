package com.letta.mobile.feature.chat

import com.letta.mobile.bot.protocol.BotChatRequest
import com.letta.mobile.bot.protocol.BotStreamChunk
import com.letta.mobile.bot.protocol.InternalBotClient
import com.letta.mobile.bot.chat.ClientModeChatSender
import com.letta.mobile.bot.clientmode.ClientModeController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class ClientModeChatSenderTest {
    @Test
    fun `fresh client mode send uses null conversation id plus force new`() = runTest {
        val fixture = ClientModeSenderFixture()

        fixture.sender
            .streamMessage(
                screenAgentId = "agent-1",
                text = "hello",
                conversationId = null,
            )
            .toList()

        fixture.verifyReadyAndSent()
        assertNull(fixture.capturedRequest.conversationId)
        assertTrue(fixture.capturedRequest.forceNew)
    }

    @Test
    fun `existing client mode send does not force new conversation`() = runTest {
        val fixture = ClientModeSenderFixture()

        fixture.sender
            .streamMessage(
                screenAgentId = "agent-1",
                text = "hello",
                conversationId = "conv-existing",
            )
            .toList()

        fixture.verifyReadyAndSent()
        assertFalse(fixture.capturedRequest.forceNew)
    }

    @Test
    fun `existing conversation client mode request preserves route agent and conversation id`() = runTest {
        val fixture = ClientModeSenderFixture()

        fixture.sender
            .streamMessage(
                screenAgentId = "route-agent",
                text = "follow up",
                conversationId = "route-conv",
            )
            .toList()

        fixture.verifyReadyAndSent()
        assertEquals("route-agent", fixture.capturedRequest.agentId)
        assertEquals("route-conv", fixture.capturedRequest.conversationId)
        assertEquals("agent:route-agent", fixture.capturedRequest.chatId)
        assertFalse(fixture.capturedRequest.forceNew)
    }

    private class ClientModeSenderFixture {
        private val internalBotClient = mockk<InternalBotClient>()
        private val controller = mockk<ClientModeController>()
        private val requestSlot = slot<BotChatRequest>()

        val sender = ClientModeChatSender(internalBotClient, controller)
        val capturedRequest: BotChatRequest get() = requestSlot.captured

        init {
            coEvery { controller.ensureReady() } returns Unit
            every { internalBotClient.streamMessage(capture(requestSlot)) } returns flowOf(
                BotStreamChunk(conversationId = "conv-existing", done = true),
            )
        }

        fun verifyReadyAndSent() {
            coVerify(exactly = 1) { controller.ensureReady() }
            verify(exactly = 1) { internalBotClient.streamMessage(any()) }
        }
    }
}
