package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ConversationSummaryGateway
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopConversationTitlePersistenceTest {
    @Test
    fun firstSubstantiveSendPersistsStableConversationTitle() = runTest {
        val gateway = SummaryGatewayFixture()
        val controller = DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = this,
            gatewayFactory = { gateway },
            agentNamesByIdProvider = { emptyMap() },
        )

        controller.start()
        runCurrent()
        controller.updateComposerText("Plan the Windows release\nwith a checklist")
        controller.send()
        runCurrent()

        assertEquals("Plan the Windows release", gateway.lastUpdate?.summary?.value)
        assertEquals("Plan the Windows release", controller.state.value.conversations.single().title)
        controller.close()
    }
}

private class SummaryGatewayFixture : FakeDesktopChatGateway(), ConversationSummaryGateway {
    var lastUpdate: ConversationSummaryUpdate? = null

    override suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> =
        super.listConversations(limit, archiveStatus).map { it.copy(summary = "") }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = emptyList()

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> = emptyList()

    override suspend fun setConversationSummary(update: ConversationSummaryUpdate): Conversation {
        lastUpdate = update
        return getConversation(update.conversationId.value).copy(summary = update.summary.value)
    }
}
