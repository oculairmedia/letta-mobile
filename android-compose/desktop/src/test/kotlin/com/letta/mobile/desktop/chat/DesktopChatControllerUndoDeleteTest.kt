package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-bzvro.31: a delete that does not destroy the chat offers an undo that puts it back
 * exactly as it was; a real delete offers none; an offer never outlives its backend.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopChatControllerUndoDeleteTest {

    private class ArchivingGateway(
        private val behavior: ConversationDeleteBehavior,
        archived: Set<String> = emptySet(),
    ) : FakeDesktopChatGateway(conversationIds = listOf("conv-1", "conv-2", "conv-3")) {
        val archived = archived.toMutableSet()
        val hidden = mutableSetOf<String>()

        /** (conversation id, wasArchived) of every restore. */
        val restored = mutableListOf<Pair<String, Boolean>>()

        override val deleteBehavior: ConversationDeleteBehavior get() = behavior

        override suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> =
            super.listConversations(limit, archiveStatus)
                .filterNot { it.id.value in hidden }
                .map { it.copy(archived = it.id.value in archived) }

        override suspend fun deleteConversation(conversationId: String) {
            when (behavior) {
                ConversationDeleteBehavior.RemovesFromLists -> { archived += conversationId; hidden += conversationId }
                ConversationDeleteBehavior.MovesToArchived -> archived += conversationId
                ConversationDeleteBehavior.Permanent -> hidden += conversationId
            }
        }

        override suspend fun restoreDeletedConversation(conversationId: String, wasArchived: Boolean) {
            restored += conversationId to wasArchived
            hidden -= conversationId
            if (wasArchived) archived += conversationId else archived -= conversationId
        }
    }

    private fun DesktopChatController.listed() = state.value.conversations.associate { it.id to it.archived }

    @Test
    fun anArchivingDeleteOffersAnUndoThatRestoresTheConversation() = runTest {
        val gateway = ArchivingGateway(ConversationDeleteBehavior.RemovesFromLists)
        val controller = testController { gateway }
        controller.start()
        runCurrent()
        assertEquals(ConversationDeleteBehavior.RemovesFromLists, controller.deleteBehavior.value)

        controller.deleteConversation("conv-2")
        runCurrent()
        val offer = assertNotNull(controller.deletionUndo.pending.value)
        assertEquals("conv-2", offer.conversationId)
        assertEquals(false, offer.wasArchived)
        assertEquals(setOf("conv-1", "conv-3"), controller.listed().keys)

        controller.undoDeleteConversation(offer)
        runCurrent()

        assertEquals(listOf("conv-2" to false), gateway.restored)
        assertNull(controller.deletionUndo.pending.value)
        assertEquals(mapOf("conv-1" to false, "conv-2" to false, "conv-3" to false), controller.listed())
        controller.close()
    }

    @Test
    fun undoingTheDeleteOfAnAlreadyArchivedChatLeavesItArchived() = runTest {
        // The Archived filter is open: deleting a chat that was already archived and pressing Undo
        // must not turn it into an active chat.
        val gateway = ArchivingGateway(ConversationDeleteBehavior.RemovesFromLists, archived = setOf("conv-2"))
        val controller = testController { gateway }
        controller.start()
        runCurrent()
        assertEquals(true, controller.listed()["conv-2"])

        controller.deleteConversation("conv-2")
        runCurrent()
        val offer = assertNotNull(controller.deletionUndo.pending.value)
        assertEquals(true, offer.wasArchived)

        controller.undoDeleteConversation(offer)
        runCurrent()

        assertEquals(listOf("conv-2" to true), gateway.restored)
        assertEquals(true, controller.listed()["conv-2"], "back, and still archived")
        assertTrue("conv-2" in gateway.archived)
        controller.close()
    }

    @Test
    fun anArchivedChatOnAMovesToArchivedBackendUndoesToArchivedToo() = runTest {
        val gateway = ArchivingGateway(ConversationDeleteBehavior.MovesToArchived, archived = setOf("conv-1"))
        val controller = testController { gateway }
        controller.start()
        runCurrent()

        controller.deleteConversation("conv-1")
        runCurrent()
        controller.undoDeleteConversation(assertNotNull(controller.deletionUndo.pending.value))
        runCurrent()

        assertEquals(listOf("conv-1" to true), gateway.restored)
        assertEquals(true, controller.listed()["conv-1"])
        controller.close()
    }

    @Test
    fun letTheSnackbarExpireAndTheDeletionStands() = runTest {
        val gateway = ArchivingGateway(ConversationDeleteBehavior.RemovesFromLists)
        val controller = testController { gateway }
        controller.start()
        runCurrent()

        controller.deleteConversation("conv-2")
        runCurrent()
        // The bar timing out is the shell clearing the spent offer; nothing else happens.
        controller.deletionUndo.clear("conv-2")
        runCurrent()

        assertNull(controller.deletionUndo.pending.value)
        assertTrue(gateway.restored.isEmpty(), "an expired offer restores nothing")
        assertTrue("conv-2" in gateway.hidden, "the chat is still removed")
        assertEquals(setOf("conv-1", "conv-3"), controller.listed().keys)
        controller.close()
    }

    @Test
    fun overlappingDeletesOnlyOfferTheLastOne() = runTest {
        val gateway = ArchivingGateway(ConversationDeleteBehavior.RemovesFromLists)
        val controller = testController { gateway }
        controller.start()
        runCurrent()

        controller.deleteConversation("conv-2")
        runCurrent()
        val first = assertNotNull(controller.deletionUndo.pending.value)
        controller.deleteConversation("conv-3")
        runCurrent()
        val second = assertNotNull(controller.deletionUndo.pending.value)
        assertEquals("conv-3", second.conversationId, "the newer delete replaces the offer")

        controller.undoDeleteConversation(second)
        runCurrent()
        assertEquals(listOf("conv-3" to false), gateway.restored)
        assertEquals(setOf("conv-1", "conv-3"), controller.listed().keys, "the first delete stands")
        assertEquals("conv-2", first.conversationId)
        controller.close()
    }

    @Test
    fun aPermanentDeleteOffersNoUndo() = runTest {
        val gateway = ArchivingGateway(ConversationDeleteBehavior.Permanent)
        val controller = testController { gateway }
        controller.start()
        runCurrent()
        assertEquals(ConversationDeleteBehavior.Permanent, controller.deleteBehavior.value)

        controller.deleteConversation("conv-2")
        runCurrent()

        assertNull(controller.deletionUndo.pending.value)
        controller.close()
    }

    @Test
    fun aNewBackendVoidsThePendingUndoAndAStaleOfferIsRefusedLoudly() = runTest {
        val first = ArchivingGateway(ConversationDeleteBehavior.RemovesFromLists)
        val second = ArchivingGateway(ConversationDeleteBehavior.MovesToArchived)
        val gateways = ArrayDeque(listOf(first, second))
        val controller = testController { gateways.removeFirst() }
        controller.start()
        runCurrent()

        controller.deleteConversation("conv-2")
        runCurrent()
        val offer = assertNotNull(controller.deletionUndo.pending.value)

        controller.retryConnection()
        runCurrent()
        assertNull(controller.deletionUndo.pending.value, "reconnecting to another backend clears the offer")
        assertEquals(ConversationDeleteBehavior.MovesToArchived, controller.deleteBehavior.value, "the wording follows the new backend")

        // A bar that was already showing still gets its click: it must not hit the new backend.
        controller.undoDeleteConversation(offer)
        runCurrent()
        assertTrue(first.restored.isEmpty() && second.restored.isEmpty())
        assertNotNull(controller.state.value.errorMessage, "and it says so instead of doing nothing")
        controller.close()
    }

    private fun TestScope.testController(factory: () -> DesktopChatGateway): DesktopChatController =
        DesktopChatController(
            bootstrapState = defaultDesktopBootstrapState(),
            scope = backgroundScope,
            gatewayFactory = factory,
            timelinePersistence = noOpDesktopTimelinePersistence,
        )
}
