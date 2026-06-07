package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.desktop.defaultDesktopBootstrapState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopChatModelsTest {
    @Test
    fun defaultStateBuildsRowsFromSharedChatProjection() {
        val state = defaultDesktopChatSurfaceState(defaultDesktopBootstrapState())

        assertEquals(DesktopChatConnectionState.Demo, state.connectionState)
        assertEquals("Demo preview", state.statusMessage)
        assertTrue(state.renderItems.any { it is ChatRenderItem.RunBlock })
        assertEquals(state.conversations.first().id, state.selectedConversationId)
        assertTrue(state.renderItems.all { it.key.isNotBlank() })
    }

    @Test
    fun selectingConversationRebuildsVisibleProjection() {
        val initial = defaultDesktopChatSurfaceState(defaultDesktopBootstrapState())
        val nextConversation = initial.conversations[1]

        val selected = initial.selectConversation(nextConversation.id)

        assertEquals(nextConversation.id, selected.selectedConversationId)
        assertEquals("", selected.composerText)
        assertNotEquals(
            initial.renderItems.map { it.key },
            selected.renderItems.map { it.key },
        )
    }

    @Test
    fun sendingLocalMessageQueuesPendingUserMessage() {
        val initial = defaultDesktopChatSurfaceState(defaultDesktopBootstrapState())
            .withComposerText("Ship the Windows preview")

        val updated = initial.sendLocalMessage()
        val localMessage = updated.selectedMessages.last()

        assertEquals("", updated.composerText)
        assertEquals("user", localMessage.role)
        assertEquals("Ship the Windows preview", localMessage.content)
        assertTrue(localMessage.isPending)
        assertTrue(updated.renderItems.any { it.containsMessageId(localMessage.id) })
        assertEquals(
            "Ship the Windows preview",
            updated.conversations.first { it.id == updated.selectedConversationId }.lastMessagePreview,
        )
    }

    @Test
    fun initialLiveStateStartsEmptyAndLoading() {
        val state = initialLiveDesktopChatSurfaceState(defaultDesktopBootstrapState())

        assertEquals(DesktopChatConnectionState.Loading, state.connectionState)
        assertTrue(state.conversations.isEmpty())
        assertEquals(null, state.selectedConversationId)
        assertTrue(state.isLoading)
        assertTrue(state.isRemoteBacked)
        assertTrue(state.shouldShowStatePanel)
    }
}
