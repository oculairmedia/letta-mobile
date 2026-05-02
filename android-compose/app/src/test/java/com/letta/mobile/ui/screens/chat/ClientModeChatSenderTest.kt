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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClientModeChatSenderTest {

    private val internalBotClient: InternalBotClient = mockk(relaxed = true)
    private val clientModeController: ClientModeController = mockk(relaxed = true)
    private val sender = ClientModeChatSender(internalBotClient, clientModeController)

    @Test
    fun `existing conversation keeps route agent bound to gateway session`() = runTest {
        val requestSlot = slot<BotChatRequest>()
        coEvery { clientModeController.ensureReady("agent-route") } returns "agent-route"
        every { internalBotClient.streamMessage(capture(requestSlot)) } returns flowOf(BotStreamChunk(done = true))

        sender.streamMessage(
            screenAgentId = "agent-route",
            text = "hello",
            existingConversationId = "conv-1",
            isFreshRoute = false,
        ).toList()

        coVerify(exactly = 1) { clientModeController.ensureReady("agent-route") }
        coVerify(exactly = 0) { clientModeController.restartSession(any()) }
        assertEquals("agent-route", requestSlot.captured.agentId)
        assertEquals("conv-1", requestSlot.captured.conversationId)
        assertEquals("agent:agent-route", requestSlot.captured.chatId)
    }

    @Test
    fun `fresh route restarts gateway session for route agent`() = runTest {
        val requestSlot = slot<BotChatRequest>()
        coEvery { clientModeController.restartSession("agent-route") } returns "agent-route"
        every { internalBotClient.streamMessage(capture(requestSlot)) } returns flowOf(BotStreamChunk(done = true))

        sender.streamMessage(
            screenAgentId = "agent-route",
            text = "hello",
            existingConversationId = null,
            isFreshRoute = true,
        ).toList()

        coVerify(exactly = 1) { clientModeController.restartSession("agent-route") }
        coVerify(exactly = 0) { clientModeController.ensureReady(any()) }
        assertEquals("agent-route", requestSlot.captured.agentId)
        assertEquals(null, requestSlot.captured.conversationId)
        assertEquals("agent:agent-route", requestSlot.captured.chatId)
    }
}
