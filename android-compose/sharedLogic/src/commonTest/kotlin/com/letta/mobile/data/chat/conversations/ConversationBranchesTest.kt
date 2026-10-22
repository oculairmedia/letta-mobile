package com.letta.mobile.data.chat.conversations

import com.letta.mobile.data.chat.runtime.AgentConversationPage
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.data.chat.runtime.ConversationSummary
import com.letta.mobile.data.chat.runtime.MapSettingsStore
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.ui.chat.session.ChatBranchView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork, edit-and-resend and rerun as every host runs them (desktop's chat controller and the web
 * bind the same [ConversationBranches]): the fork opens with the source's pin, an edit refills the
 * composer, a rerun sends the prompt again in the fork, and the original is never written.
 */
class ConversationBranchesTest {
    private val timeline = listOf(
        UiMessage(id = "m1", role = "user", content = "hello", timestamp = "t"),
        UiMessage(id = "m2", role = "assistant", content = "hi", timestamp = "t"),
        UiMessage(id = "m3", role = "user", content = "typo hree", timestamp = "t"),
    )
    private val view = ChatBranchView(timeline, hasOlderMessages = false)

    @Test
    fun aForkFromAMessageOpensWithTheSourcesPin() = runTest(UnconfinedTestDispatcher()) {
        val iroh = IrohHost(HostConversation("conv-1", "agent-0"))
        val host = RecordingHost(iroh.chatGateway(backgroundScope))
        host.prefs.pins?.setPinned("conv-1", true)

        ConversationManagement(this, host, host.prefs).branches.forkFrom(timeline[0], view)

        assertEquals(listOf(Triple("conv-1", "agent-0", "m2")), iroh.forks())
        assertEquals(listOf("fork-1"), host.opened.map { it.conversation.id.value })
        assertEquals("conv-1", host.opened.single().source.conversationId)
        assertTrue(host.prefs.pins?.isPinned("fork-1") == true)
        assertEquals(emptyList(), host.errors)
        assertEquals("agent-0", iroh.stored("conv-1")?.agent, "the original is untouched")
    }

    @Test
    fun anEditForksBeforeThePromptAndRefillsTheComposer() = runTest(UnconfinedTestDispatcher()) {
        val iroh = IrohHost(HostConversation("conv-1", "agent-0"))
        val host = RecordingHost(iroh.chatGateway(backgroundScope))

        ConversationManagement(this, host, host.prefs).branches.editAndResend(timeline[2], view)

        assertEquals(listOf(Triple("conv-1", "agent-0", "m2")), iroh.forks())
        val opened = host.opened.single()
        assertEquals("typo hree", opened.draft?.draft?.text)
        assertFalse(opened.draft?.sendNow == true)
    }

    @Test
    fun aRerunForksBeforeThePromptAndSendsItThere() = runTest(UnconfinedTestDispatcher()) {
        val iroh = IrohHost(HostConversation("conv-1", "agent-0"))
        val host = RecordingHost(iroh.chatGateway(backgroundScope))

        ConversationManagement(this, host, host.prefs).branches.rerun(timeline[2], view)

        assertEquals(listOf(Triple("conv-1", "agent-0", "m2")), iroh.forks())
        val opened = host.opened.single()
        assertEquals("typo hree", opened.draft?.draft?.text)
        assertTrue(opened.draft?.sendNow == true, "a rerun sends the prompt into the fork")
    }

    @Test
    fun aBackendThatCannotForkSaysSoAndOffersNoActions() = runTest(UnconfinedTestDispatcher()) {
        val host = RecordingHost(TimelineOnlyGateway())
        val management = ConversationManagement(this, host, host.prefs)

        assertFalse(management.supportsBranching)
        assertFalse(management.branches.support.value.fork)
        assertFalse(management.branches.runBranchCommand("/fork"))
        management.branches.forkFrom(timeline[0], view)

        assertEquals(listOf("This backend can't fork conversations."), host.errors)
        assertEquals(emptyList(), host.opened)
    }

    /** Each `conversation.fork` as (source, agent, through message). */
    private fun IrohHost.forks() = calls.filter { it.first == "conversation.fork" }
        .map { (_, params) -> Triple(params.text("conversation_id"), params.text("agent_id"), params.text("message_id")) }

    private class RecordingHost(override val gateway: ChatGateway?) : ConversationListHost {
        val prefs = ConversationManagementPrefs.from(MapSettingsStore())
        val opened = mutableListOf<OpenedBranch>()
        val errors = mutableListOf<String>()

        override val isClosed: Boolean = false
        override val gatewayChanges: Flow<Unit> = flowOf(Unit)

        override fun origin() = ConversationOrigin(ConversationId("conv-1"), AgentId("agent-0"))

        override fun listedTitle(conversation: ConversationId): String? = null

        override fun showTitle(conversation: ConversationId, title: ConversationSummary, onlyIf: ConversationSummary?) = Unit

        override fun showArchived(conversation: ConversationId, archived: Boolean) = Unit

        override fun beginDelete(conversation: ConversationId): Boolean = true

        override suspend fun endDelete(conversation: ConversationId, behavior: ConversationDeleteBehavior?) = Unit

        override suspend fun openBranch(branch: OpenedBranch) {
            opened += branch
        }

        override fun showError(message: String) {
            errors += message
        }
    }

    /** A gateway with a timeline and no conversation capabilities. */
    private class TimelineOnlyGateway : ChatGateway {
        override suspend fun listAgentConversations(page: AgentConversationPage): List<Conversation> = emptyList()

        override suspend fun getConversation(conversationId: String): Conversation = error("unused")

        override suspend fun sendConversationMessage(conversationId: ConversationId, request: MessageCreateRequest): Flow<LettaMessage> =
            emptyFlow()

        override suspend fun streamConversation(conversationId: ConversationId): Flow<TimelineStreamFrame> = emptyFlow()

        override suspend fun listConversationMessages(conversationId: ConversationId, limit: Int?, after: String?, order: String?) =
            emptyList<LettaMessage>()

        override suspend fun listAgentMessages(agentId: AgentId, limit: Int?, order: String?, conversationId: ConversationId?) =
            emptyList<LettaMessage>()
    }
}
