package com.letta.mobile.data.memory.graph

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** bfooy.5: New block / Delete on the shared memory page. */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryBlockLifecycleTest {
    private val humanId = MemoryGraphFixtures.blockNodeId(MemoryGraphFixtures.human)
    private val humanRef = MemoryBlockRef(MemoryGraphFixtures.AGENT_ID, "human", "block-human")

    private class Harness(scope: TestScope, writable: Boolean = true) {
        val source = FakeMemoryParitySource(MemoryGraphFixtures.controllerState())
        val port = FakeMemoryBlockPort(initial = mapOf("human" to "Name: Emmanuel"), writable = writable)
        private val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        val controller = MemoryPageController(
            source = source,
            blocks = port,
            scope = TestScope(dispatcher),
            layoutDispatcher = dispatcher,
        ).also { it.start() }

        val state get() = controller.state.value
    }

    @Test
    fun createIsOfferedOnlyWhenTheBackendCanWrite() = runTest {
        assertTrue(Harness(this).state.canCreateBlock)

        val readOnly = Harness(this, writable = false)
        assertFalse(readOnly.state.canCreateBlock)
        readOnly.controller.beginCreate()
        assertNull(readOnly.state.creation)
    }

    @Test
    fun createCommitsTheTrimmedLabelClosesTheSheetAndReloads() = runTest {
        val harness = Harness(this)

        harness.controller.beginCreate()
        harness.controller.updateCreateLabel("  project_notes ")
        harness.controller.updateCreateValue("Ship the memory page")
        harness.controller.submitCreate()

        assertEquals(
            listOf(Triple(MemoryGraphFixtures.AGENT_ID, "project_notes", "Ship the memory page")),
            harness.port.creates,
        )
        assertNull(harness.state.creation)
        assertEquals(1, harness.source.reloads)
    }

    @Test
    fun invalidLabelsCannotBeSubmitted() = runTest {
        val harness = Harness(this)
        harness.controller.beginCreate()

        listOf("", "../escape", "a/b", ".hidden", "x".repeat(MemoryBlockLabels.MAX_LENGTH + 1)).forEach { label ->
            harness.controller.updateCreateLabel(label)
            assertNotNull(harness.state.creation?.labelError, "'$label' must be rejected")
            harness.controller.submitCreate()
        }

        assertTrue(harness.port.creates.isEmpty())
    }

    @Test
    fun createFailureKeepsTheSheetOpenWithTheError() = runTest {
        val harness = Harness(this)
        harness.port.failCreateWith = IllegalArgumentException("block notes already exists for agent agent-1")
        harness.controller.beginCreate()
        harness.controller.updateCreateLabel("notes")

        harness.controller.submitCreate()

        val draft = requireNotNull(harness.state.creation)
        assertEquals("notes", draft.label)
        assertFalse(draft.isSaving)
        assertEquals("block notes already exists for agent agent-1", draft.error)
        assertEquals(0, harness.source.reloads)
    }

    @Test
    fun deleteNeedsConfirmationThenCommitsClosesTheCardAndReloads() = runTest {
        val harness = Harness(this)
        harness.controller.selectNode(humanId)

        harness.controller.requestDelete()
        assertEquals(MemoryBlockDeletion(humanRef), harness.state.deletion)
        assertTrue(harness.port.deletes.isEmpty(), "requesting a delete must not delete yet")

        harness.controller.confirmDelete()

        assertEquals(listOf(humanRef), harness.port.deletes)
        assertNull(harness.state.deletion)
        assertNull(harness.state.selection)
        assertEquals(1, harness.source.reloads)
    }

    @Test
    fun cancelledDeleteLeavesTheBlockAlone() = runTest {
        val harness = Harness(this)
        harness.controller.selectNode(humanId)

        harness.controller.requestDelete()
        harness.controller.cancelDelete()

        assertNull(harness.state.deletion)
        assertTrue(harness.port.deletes.isEmpty())
        assertNotNull(harness.state.selection)
    }

    @Test
    fun deleteFailureKeepsTheConfirmationWithTheError() = runTest {
        val harness = Harness(this)
        harness.port.failDeleteWith = IllegalStateException("delete_memory_file failed")
        harness.controller.selectNode(humanId)

        harness.controller.requestDelete()
        harness.controller.confirmDelete()

        val deletion = requireNotNull(harness.state.deletion)
        assertFalse(deletion.isDeleting)
        assertEquals("delete_memory_file failed", deletion.error)
        assertNotNull(harness.state.selection)
    }

    @Test
    fun deleteIsUnavailableOnAReadOnlyBackend() = runTest {
        val harness = Harness(this, writable = false)
        harness.controller.selectNode(humanId)

        harness.controller.requestDelete()

        assertNull(harness.state.deletion)
    }
}
