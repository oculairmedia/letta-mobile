package com.letta.mobile.data.memory.graph

import com.letta.mobile.data.memory.MemoryGraphNodeKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryPageControllerTest {
    private val personaId = MemoryGraphFixtures.blockNodeId(MemoryGraphFixtures.persona)
    private val humanId = MemoryGraphFixtures.blockNodeId(MemoryGraphFixtures.human)

    private class Harness(scope: TestScope, writable: Boolean = true) {
        val source = FakeMemoryParitySource(MemoryGraphFixtures.controllerState())
        val port = FakeMemoryBlockPort(
            initial = mapOf("persona" to "I am a helpful agent.\nFull value.", "human" to "Name: Emmanuel"),
            writable = writable,
        )
        val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        val controller = MemoryPageController(
            source = source,
            blocks = port,
            scope = TestScope(dispatcher),
            layoutDispatcher = dispatcher,
        ).also { it.start() }

        val state get() = controller.state.value
    }

    @Test
    fun startProjectsTheGraphWithALayout() = runTest {
        val harness = Harness(this)

        assertEquals(5, harness.state.view.nodes.size)
        assertEquals(harness.state.view.nodes.map { it.id }.toSet(), harness.state.layout.positions.keys)
    }

    @Test
    fun selectingABlockLoadsItsFullValue() = runTest {
        val harness = Harness(this)

        harness.controller.selectNode(personaId)

        val selection = requireNotNull(harness.state.selection)
        assertEquals(MemoryNodeContent.Loaded("I am a helpful agent.\nFull value."), selection.content)
        assertTrue(selection.canEdit)
    }

    @Test
    fun selectingASkillShowsStaticContent() = runTest {
        val harness = Harness(this)

        harness.controller.selectNode(MemoryGraphFixtures.skillNodeId(MemoryGraphFixtures.search))

        val selection = requireNotNull(harness.state.selection)
        assertIs<MemoryNodeContent.Static>(selection.content)
        assertFalse(selection.canEdit)
        assertEquals("Search the web", selection.displayText)
    }

    @Test
    fun loadFailureSurfacesAndRetryRecovers() = runTest {
        val harness = Harness(this)
        harness.port.failLoadWith = IllegalStateException("admin_rpc timed out")

        harness.controller.selectNode(personaId)
        assertEquals(MemoryNodeContent.Failed("admin_rpc timed out"), harness.state.selection?.content)

        harness.port.failLoadWith = null
        harness.controller.retryContent()
        assertIs<MemoryNodeContent.Loaded>(harness.state.selection?.content)
    }

    @Test
    fun editAndSaveCommitsThroughThePortAndReloads() = runTest {
        val harness = Harness(this)
        harness.controller.selectNode(humanId)

        harness.controller.beginEdit()
        harness.controller.updateDraft("Name: Emmanuel\nTimezone: EST")
        harness.controller.saveEdit()

        val expectedRef = MemoryBlockRef(MemoryGraphFixtures.AGENT_ID, "human", "block-human")
        assertEquals(listOf(expectedRef to "Name: Emmanuel\nTimezone: EST"), harness.port.saves)
        val selection = requireNotNull(harness.state.selection)
        assertNull(selection.editor)
        assertEquals(MemoryNodeContent.Loaded("Name: Emmanuel\nTimezone: EST"), selection.content)
        assertEquals(1, harness.source.reloads)
    }

    @Test
    fun saveFailureKeepsTheDraftWithAnError() = runTest {
        val harness = Harness(this)
        harness.port.failSaveWith = IllegalStateException("write_memory_file failed")
        harness.controller.selectNode(personaId)
        harness.controller.beginEdit()
        harness.controller.updateDraft("changed")

        harness.controller.saveEdit()

        val editor = requireNotNull(harness.state.selection?.editor)
        assertEquals("changed", editor.draft)
        assertEquals("write_memory_file failed", editor.error)
        assertFalse(editor.isSaving)
        assertEquals(0, harness.source.reloads)
    }

    @Test
    fun draftOverTheBlockLimitCannotBeSaved() = runTest {
        val harness = Harness(this)
        harness.controller.selectNode(humanId)
        harness.controller.beginEdit()

        harness.controller.updateDraft("x".repeat(41))
        harness.controller.saveEdit()

        assertFalse(requireNotNull(harness.state.selection).canSave)
        assertTrue(harness.port.saves.isEmpty())
    }

    @Test
    fun unchangedDraftCannotBeSaved() = runTest {
        val harness = Harness(this)
        harness.controller.selectNode(personaId)
        harness.controller.beginEdit()

        harness.controller.saveEdit()

        assertTrue(harness.port.saves.isEmpty())
    }

    @Test
    fun readOnlyBlockAndReadOnlyBackendRefuseEditing() = runTest {
        val harness = Harness(this)
        harness.controller.selectNode(MemoryGraphFixtures.blockNodeId(MemoryGraphFixtures.locked))
        harness.controller.beginEdit()
        assertNull(harness.state.selection?.editor)

        val readOnlyBackend = Harness(this, writable = false)
        readOnlyBackend.controller.selectNode(personaId)
        readOnlyBackend.controller.beginEdit()
        assertNull(readOnlyBackend.state.selection?.editor)
    }

    @Test
    fun selectionClosesWhenItsNodeDisappears() = runTest {
        val harness = Harness(this)
        harness.controller.selectNode(personaId)

        val withoutPersona = MemoryGraphFixtures.memory(
            blocks = listOf(MemoryGraphFixtures.human, MemoryGraphFixtures.locked),
        )
        harness.source.emit(MemoryGraphFixtures.controllerState(withoutPersona))

        assertNull(harness.state.selection)
        assertEquals(4, harness.state.view.nodes.size)
    }

    @Test
    fun reloadKeepsAnOpenEditorOnASurvivingNode() = runTest {
        val harness = Harness(this)
        harness.controller.selectNode(humanId)
        harness.controller.beginEdit()
        harness.controller.updateDraft("draft in progress")

        harness.source.emit(MemoryGraphFixtures.controllerState())

        assertEquals("draft in progress", harness.state.selection?.editor?.draft)
    }

    @Test
    fun hidingAKindRelaysOutAndClosesACardOfThatKind() = runTest {
        val harness = Harness(this)
        harness.controller.selectNode(MemoryGraphFixtures.skillNodeId(MemoryGraphFixtures.search))

        harness.controller.toggleKind(MemoryGraphNodeKind.Skill)

        assertNull(harness.state.selection)
        assertEquals(4, harness.state.layout.positions.size)
        assertFalse(harness.state.view.isKindEnabled(MemoryGraphNodeKind.Skill))
    }

    @Test
    fun selectingAnAgentClearsTheCardAndForwards() = runTest {
        val harness = Harness(this)
        harness.controller.selectNode(personaId)

        harness.controller.selectAgent("agent-2")

        assertNull(harness.state.selection)
        assertEquals("agent-2", harness.source.selectedAgent)
    }
}
