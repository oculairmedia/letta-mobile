package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-bzvro.31: a delete that only archives (no delete command on the backend) offers an
 * undo that brings the conversation back; a real delete offers none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatControllerUndoDeleteTest {

    private class ArchivingGateway(private val archives: Boolean) : FakeDesktopChatGateway(conversationIds = listOf("conv-1", "conv-2")) {
        val hidden = mutableSetOf<String>()
        val restored = mutableListOf<String>()

        override val deleteArchivesConversation: Boolean get() = archives

        override suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> =
            super.listConversations(limit, archiveStatus).filterNot { it.id.value in hidden }

        override suspend fun deleteConversation(conversationId: String) {
            hidden += conversationId
        }

        override suspend fun restoreDeletedConversation(conversationId: String) {
            restored += conversationId
            hidden -= conversationId
        }
    }

    @Test
    fun anArchivingDeleteOffersAnUndoThatRestoresTheConversation() = runTest {
        val gateway = ArchivingGateway(archives = true)
        val controller = testController(gateway)
        controller.start()
        runCurrent()
        assertTrue(controller.deleteArchivesConversation)

        controller.deleteConversation("conv-2")
        runCurrent()
        assertEquals("conv-2", controller.undoableDeletion.value)
        assertEquals(listOf("conv-1"), controller.state.value.conversations.map { it.id })

        controller.undoDeleteConversation("conv-2")
        runCurrent()

        assertEquals(listOf("conv-2"), gateway.restored)
        assertNull(controller.undoableDeletion.value)
        assertEquals(listOf("conv-1", "conv-2"), controller.state.value.conversations.map { it.id }.sorted())
        controller.close()
    }

    @Test
    fun letTheSnackbarExpireAndTheDeletionStands() = runTest {
        val gateway = ArchivingGateway(archives = true)
        val controller = testController(gateway)
        controller.start()
        runCurrent()

        controller.deleteConversation("conv-2")
        runCurrent()
        controller.clearUndoableDeletion("conv-2")

        assertNull(controller.undoableDeletion.value)
        assertTrue(gateway.restored.isEmpty())
        controller.close()
    }

    @Test
    fun aPermanentDeleteOffersNoUndo() = runTest {
        val gateway = ArchivingGateway(archives = false)
        val controller = testController(gateway)
        controller.start()
        runCurrent()
        assertFalse(controller.deleteArchivesConversation)

        controller.deleteConversation("conv-2")
        runCurrent()

        assertNull(controller.undoableDeletion.value)
        controller.close()
    }

    private fun TestScope.testController(gateway: DesktopChatGateway): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = backgroundScope,
            gatewayFactory = { gateway },
            timelinePersistence = noOpDesktopTimelinePersistence,
        )
}
