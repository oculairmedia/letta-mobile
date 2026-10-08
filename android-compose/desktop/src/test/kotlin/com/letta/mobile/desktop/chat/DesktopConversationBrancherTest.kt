package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.branch.ConversationForkGateway
import com.letta.mobile.data.chat.branch.ConversationForkRequest
import com.letta.mobile.data.chat.runtime.PinnedConversations
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.desktop.data.DesktopInMemorySecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * letta-mobile-bzvro.15 / .16 (F15, F16) on desktop: the fork opens in place with the source's
 * working directory and pin, an edit refills the composer, and the original is never written.
 */
class DesktopConversationBrancherTest {
    private val timeline = listOf(
        UiMessage(id = "m1", role = "user", content = "hello", timestamp = "t"),
        UiMessage(id = "m2", role = "assistant", content = "hi", timestamp = "t"),
        UiMessage(id = "m3", role = "user", content = "typo hree", timestamp = "t"),
    )

    @Test
    fun aForkOpensWithTheSourcesFolderAndPin() = runTest(UnconfinedTestDispatcher()) {
        val gateway = ForkingGateway()
        val host = RecordingHost(gateway)
        host.pins.setPinned("conv-1", true)

        DesktopConversationBrancher(this, host).forkFrom(timeline[0], DesktopBranchView(timeline, hasOlderMessages = false))

        assertEquals(listOf(ConversationForkRequest("conv-1", "agent-0", "m2")), gateway.forks)
        assertEquals(listOf<Pair<String, String?>>("conv-fork" to null), host.opened)
        assertEquals(mapOf("conv-fork" to "/work/repo"), gateway.directories)
        assertTrue(host.pins.isPinned("conv-fork"))
        assertEquals(emptyList(), host.errors)
    }

    @Test
    fun anEditForksBeforeThePromptAndRefillsTheComposer() = runTest(UnconfinedTestDispatcher()) {
        val gateway = ForkingGateway()
        val host = RecordingHost(gateway)

        DesktopConversationBrancher(this, host).editAndResend(timeline[2], DesktopBranchView(timeline, hasOlderMessages = false))

        assertEquals(listOf(ConversationForkRequest("conv-1", "agent-0", "m2")), gateway.forks)
        assertEquals(listOf<Pair<String, String?>>("conv-fork" to "typo hree"), host.opened)
        assertFalse(host.pins.isPinned("conv-fork"))
    }

    @Test
    fun aBackendThatCannotForkSaysSoAndOffersNoActions() = runTest(UnconfinedTestDispatcher()) {
        val host = RecordingHost(FakeDesktopChatGateway())
        val brancher = DesktopConversationBrancher(this, host)

        assertFalse(brancher.supported)
        brancher.forkFrom(timeline[0], DesktopBranchView(timeline, hasOlderMessages = false))

        assertEquals(listOf("This backend can't fork conversations."), host.errors)
        assertEquals(emptyList(), host.opened)
    }

    private class ForkingGateway : FakeDesktopChatGateway(), ConversationForkGateway, DesktopWorkingDirectoryController {
        val forks = mutableListOf<ConversationForkRequest>()
        val directories = mutableMapOf<String, String>()

        override suspend fun forkConversation(request: ConversationForkRequest): Conversation {
            forks += request
            return Conversation(id = ConversationId("conv-fork"), agentId = AgentId(request.agentId.orEmpty()))
        }

        override suspend fun currentWorkingDirectory(agentId: String, conversationId: String): String? =
            "/work/repo".takeIf { conversationId == "conv-1" }

        override suspend fun setWorkingDirectory(agentId: String, conversationId: String, path: String): Boolean {
            directories[conversationId] = path
            return true
        }
    }

    private class RecordingHost(override val gateway: DesktopChatGateway) : DesktopBranchHost {
        override val pins = PinnedConversations(DesktopInMemorySecureSettingsStore())
        val opened = mutableListOf<Pair<String, String?>>()
        val errors = mutableListOf<String>()

        override fun origin() = DesktopBranchOrigin("conv-1", "agent-0")

        override suspend fun openBranch(branch: DesktopOpenedBranch) {
            opened += branch.conversation.id.value to branch.draft
        }

        override fun showError(message: String) {
            errors += message
        }
    }
}
