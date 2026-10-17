package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArchivedConversationsTest {
    @Test
    fun archivedIdsSurviveARestartAndAnEmptySetClearsTheKey() {
        val settings = InMemorySecureSettingsStore()
        val ids = setOf(ConversationId("conv-b"), ConversationId("conv-a"))

        ArchivedConversations(settings).save(ids)
        assertEquals(ids, ArchivedConversations(settings).load())

        ArchivedConversations(settings).save(emptySet())
        assertEquals(emptySet(), ArchivedConversations(settings).load())
        assertNull(settings.getString(ArchivedConversations.DEFAULT_KEY))
    }
}
