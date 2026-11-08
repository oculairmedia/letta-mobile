package com.letta.mobile.data.repository

import com.letta.mobile.data.objectverbs.binding.withDevicePins
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.storage.MutableSettingsPreferencesEditor
import com.letta.mobile.data.storage.SettingsPreferencesSnapshot
import com.letta.mobile.data.storage.SettingsPreferencesStore
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsSettledSelectionTest {
    private val storage = InMemorySecureSettingsStore().withDevicePins()
    private val preferences = object : SettingsPreferencesStore {
        override val snapshots = emptyFlow<SettingsPreferencesSnapshot>()
        override suspend fun edit(block: suspend (MutableSettingsPreferencesEditor) -> Unit) =
            error("Selection persistence must not edit presentation preferences")
        override suspend fun clearAll() = Unit
    }

    private fun repository(): ISettingsRepository = CachedSettingsRepository(preferences, storage)

    @Test
    fun atomicSelectionSurvivesRecreationAndLegacyWriterCannotReplaceIt() {
        val selection = LastChatSelection("agent-a", "Agent A", "conversation-a", "source-a")
        repository().setSettledChatSelection(selection)
        repository().setLastChatSelection("agent-b", "Agent B", "conversation-b")
        assertEquals(selection, repository().lastChatSeed("source-a"))
        assertNull(repository().lastChatSeed("source-b"))
    }

    @Test
    fun compatibleSinkCarriesNameOnlyForSameSourceAndAgent() {
        val repository = repository()
        repository.setSettledChatSelection("source-a", AgentId("agent-a"), ConversationId("one"), "Agent A")
        repository.setSettledChatSelection(LastChatSelection("agent-a", conversationId = "two", sourceKey = "source-a"))
        assertEquals(LastChatSelection("agent-a", "Agent A", "two", "source-a"), repository.lastChatSeed("source-a"))
        repository.setSettledChatSelection(LastChatSelection("agent-a", conversationId = "three", sourceKey = "source-b"))
        assertEquals(LastChatSelection("agent-a", null, "three", "source-b"), repository.lastChatSeed("source-b"))
        repository.setSettledChatSelection(LastChatSelection("agent-b", conversationId = "four", sourceKey = "source-b"))
        assertEquals(LastChatSelection("agent-b", null, "four", "source-b"), repository.lastChatSeed("source-b"))
    }

    @Test
    fun incompleteAtomicSelectionCannotOverwriteSettledSeed() {
        val selection = LastChatSelection("agent-a", "Agent A", "conversation-a", "source-a")
        val repository = repository()
        repository.setSettledChatSelection(selection)
        listOf(
            selection.copy(sourceKey = null), selection.copy(sourceKey = " "),
            selection.copy(agentId = " "), selection.copy(conversationId = null),
            selection.copy(conversationId = " "),
        ).forEach { repository.setSettledChatSelection(it) }
        assertEquals(selection, repository.lastChatSeed("source-a"))
    }
}
