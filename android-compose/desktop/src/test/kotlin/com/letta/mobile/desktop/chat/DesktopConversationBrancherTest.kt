package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.branch.BranchSource
import com.letta.mobile.data.chat.conversations.OpenedBranch
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * letta-mobile-bzvro.15 on desktop: the branching itself is shared (ConversationBranchesTest); what the
 * desktop adds is the source's working directory carried to the fork.
 */
class DesktopConversationBrancherTest {
    private val fork = Conversation(id = ConversationId("conv-fork"), agentId = AgentId("agent-0"))

    @Test
    fun aForkWorksInTheFolderItsSourceDid() = runTest {
        val gateway = FolderGateway()

        inheritWorkingDirectory(gateway, OpenedBranch(fork, BranchSource("conv-1", "agent-0")))

        assertEquals(mapOf("conv-fork" to "/work/repo"), gateway.directories)
    }

    @Test
    fun aSourceWithNoKnownAgentKeepsTheDefaultFolder() = runTest {
        val gateway = FolderGateway()

        inheritWorkingDirectory(gateway, OpenedBranch(fork, BranchSource("conv-1", agentId = null)))

        assertTrue(gateway.directories.isEmpty())
    }

    private class FolderGateway : FakeDesktopChatGateway(), DesktopWorkingDirectoryController {
        val directories = mutableMapOf<String, String>()

        override suspend fun currentWorkingDirectory(agentId: String, conversationId: String): String? =
            "/work/repo".takeIf { conversationId == "conv-1" }

        override suspend fun setWorkingDirectory(agentId: String, conversationId: String, path: String): Boolean {
            directories[conversationId] = path
            return true
        }
    }
}
