package com.letta.mobile.data.shell.focus

import com.letta.mobile.data.serialization.LettaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class CurrentFocusContractTest {
    private val cases = LettaJson.wire.decodeFromString<List<FocusCase>>(currentFocusContractText)

    @Test
    fun sharedFixtureDefinesStartupPrecedenceAndSnapshotRoundTrips() {
        cases.forEach { case ->
            val restoredInput = LettaJson.wire.decodeFromString<CurrentFocusInputs>(LettaJson.wire.encodeToString(case.inputs))
            assertEquals(case.expected, initialCurrentFocus(restoredInput), case.name)
            val persisted = LettaJson.persisted.encodeToString(case.expected)
            assertEquals(case.expected, LettaJson.persisted.decodeFromString<CurrentFocusSnapshot>(persisted), case.name)
            assertTrue(persisted.contains("\"contractVersion\":1"), case.name)
        }
    }

    @Test
    fun rotationPreservesTheUnderlyingChatAlongsideTabsEditorAndRail() {
        val case = cases.single { it.name == "rotation with tabs and editor open" }
        val actual = initialCurrentFocus(case.inputs)
        assertEquals("conv-9", actual.focus.conversationId?.value)
        assertEquals("agent-editor?agent=agent-b", actual.editorRoute)
        assertEquals(listOf("conv-1", "conv-9"), actual.pinnedConversationIds.map { it.value })
        assertEquals("conv-3", actual.previewConversationId?.value)
        assertTrue(actual.railExpanded)
    }

    @Test
    fun switchingSourcesCannotRestoreOldIdentitiesOrTabsOrEditor() {
        val case = cases.single { it.name == "backend switch" }
        val actual = initialCurrentFocus(case.inputs)
        assertNull(actual.focus.agentId)
        assertNull(actual.focus.conversationId)
        assertTrue(actual.pinnedConversationIds.isEmpty())
        assertNull(actual.previewConversationId)
        assertNull(actual.editorRoute)
        assertTrue(actual.railExpanded)
    }

    @Test
    fun rosterAndConversationAvailabilityCannotChangeStartupPrecedence() {
        listOf("agent removed from roster", "deleted conversation").forEach { name ->
            val case = cases.single { it.name == name }
            val actual = initialCurrentFocus(case.inputs)
            assertEquals("agent-a", actual.focus.agentId?.value, name)
            assertEquals("conv-9", actual.focus.conversationId?.value, name)
            assertEquals(CurrentFocusChatMode.Exact, actual.focus.chatMode, name)
        }
    }

    @Serializable
    private data class FocusCase(
        val name: String,
        val inputs: CurrentFocusInputs,
        val expected: CurrentFocusSnapshot,
    )
}
