package com.letta.mobile.data.memory.memfs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemfsPageControllerTest {
    private class FakeMemfsSource : MemfsSource {
        val contents = mutableMapOf(
            "system/persona.md" to "I am Letta.",
            "system/human.md" to "The user.",
            "notes/today.md" to "Notes.",
        )
        var enabled = true
        var listFailure: Exception? = null
        var writeGate: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<Pair<String, String>>()
        val reads = mutableListOf<String>()
        override val updates = MutableSharedFlow<MemfsUpdate>(extraBufferCapacity = 8)

        override suspend fun list(agentId: String): MemfsListing {
            listFailure?.let { throw it }
            return MemfsListing(
                enabled = enabled,
                files = if (!enabled) emptyList() else contents.keys.map { path ->
                    MemfsFile(path, isSystem = path.startsWith("system/"), description = null, sizeBytes = 1, kind = MemfsFileKind.Markdown)
                } + MemfsFile("profile.png", isSystem = false, description = null, sizeBytes = 9, kind = MemfsFileKind.Image),
            )
        }

        override suspend fun read(file: MemfsFileRef): String {
            reads += file.path
            return contents.getValue(file.path)
        }

        override suspend fun write(file: MemfsFileRef, content: String): String? {
            writeGate?.await()
            writes += file.path to content
            contents[file.path] = content
            return "sha"
        }

        override suspend fun history(scope: MemfsHistoryScope): List<MemfsCommit> =
            listOf(MemfsCommit("aaaaaaa1", "Update ${scope.path ?: "all"}", "2026-10-01T00:00:00Z", "Letta"))

        override suspend fun commitDiff(commit: MemfsCommitRef): String =
            "diff --git a/system/persona.md b/system/persona.md\n--- a/system/persona.md\n+++ b/system/persona.md\n@@ -1 +1 @@\n-old\n+new"

        override suspend fun fileAtRef(file: MemfsFileRef, ref: String): String = "old"

        override suspend fun enable(agentId: String) {
            enabled = true
        }
    }

    private fun TestScope.controller(source: FakeMemfsSource = FakeMemfsSource()): Pair<MemfsPageController, FakeMemfsSource> {
        val controller = MemfsPageController(source, backgroundScope)
        controller.start()
        controller.selectAgent("agent-1")
        runCurrent()
        return controller to source
    }

    @Test
    fun selectingAnAgentListsSystemAndExternalFilesSeparately() = runTest {
        val (controller, _) = controller()
        val state = controller.state.value
        assertEquals(MemfsLoad.Loaded, state.load)
        assertEquals(listOf("system/human.md", "system/persona.md"), state.systemFiles.map { it.path })
        assertEquals(listOf("notes/today.md", "profile.png"), state.externalFiles.map { it.path })
    }

    @Test
    fun theFilterNarrowsBothGroups() = runTest {
        val (controller, _) = controller()
        controller.updateQuery("persona")
        assertEquals(listOf("system/persona.md"), controller.state.value.systemFiles.map { it.path })
        assertTrue(controller.state.value.externalFiles.isEmpty())
        controller.updateQuery("nothing-matches")
        assertEquals(MemfsEmptyReason.NoMatches, controller.state.value.emptyReason)
    }

    @Test
    fun aFailedListingShowsItsReason() = runTest {
        val source = FakeMemfsSource().apply { listFailure = MemfsException("The App Server did not answer in time.") }
        val (controller, _) = controller(source)
        assertEquals(MemfsLoad.Failed("The App Server did not answer in time."), controller.state.value.load)
    }

    @Test
    fun aDisabledAgentOffersToEnableMemfs() = runTest {
        val source = FakeMemfsSource().apply { enabled = false }
        val (controller, _) = controller(source)
        assertEquals(MemfsEmptyReason.MemfsDisabled, controller.state.value.emptyReason)

        controller.enableMemfs()
        runCurrent()
        assertTrue(controller.state.value.memfsEnabled)
        assertFalse(controller.state.value.enabling)
        assertEquals(4, controller.state.value.files.size)
    }

    @Test
    fun editingAndSavingWritesTheDraft() = runTest {
        val (controller, source) = controller()
        controller.openFile("system/persona.md")
        runCurrent()
        assertEquals("I am Letta.", controller.state.value.editor?.original)

        controller.editDraft("I am Letta, revised.")
        assertTrue(controller.state.value.editor!!.dirty)
        controller.save()
        runCurrent()

        assertEquals(listOf("system/persona.md" to "I am Letta, revised."), source.writes)
        val editor = controller.state.value.editor!!
        assertFalse(editor.dirty)
        assertEquals("I am Letta, revised.", editor.original)
    }

    @Test
    fun aPushForTheOpenCleanFileReReadsIt() = runTest {
        val (controller, source) = controller()
        controller.openFile("system/persona.md")
        runCurrent()
        source.contents["system/persona.md"] = "Changed by the agent."

        source.updates.emit(MemfsUpdate(setOf("system/persona.md")))
        runCurrent()

        assertEquals("Changed by the agent.", controller.state.value.editor?.draft)
        assertFalse(controller.state.value.editor!!.conflict)
    }

    @Test
    fun aPushForTheOpenDirtyFilePromptsInsteadOfClobberingTheDraft() = runTest {
        val (controller, source) = controller()
        controller.openFile("system/persona.md")
        runCurrent()
        controller.editDraft("My unsaved words.")
        source.contents["system/persona.md"] = "Changed by the agent."

        source.updates.emit(MemfsUpdate(setOf("system/persona.md")))
        runCurrent()

        val editor = controller.state.value.editor!!
        assertTrue(editor.conflict)
        assertEquals("My unsaved words.", editor.draft)

        controller.reloadFromServer()
        runCurrent()
        val reloaded = controller.state.value.editor!!
        assertEquals("Changed by the agent.", reloaded.draft)
        assertFalse(reloaded.conflict)
    }

    @Test
    fun keepingTheDraftDismissesTheConflict() = runTest {
        val (controller, source) = controller()
        controller.openFile("system/persona.md")
        runCurrent()
        controller.editDraft("Mine.")
        source.updates.emit(MemfsUpdate(setOf(MemfsUpdate.WILDCARD)))
        runCurrent()
        controller.keepDraft()
        assertFalse(controller.state.value.editor!!.conflict)
        assertEquals("Mine.", controller.state.value.editor!!.draft)
    }

    @Test
    fun theEchoOfOurOwnSaveIsNotAConflict() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (controller, source) = controller(FakeMemfsSource().apply { writeGate = gate })
        controller.openFile("system/persona.md")
        runCurrent()
        controller.editDraft("Saved words.")
        controller.save()
        runCurrent()
        source.updates.emit(MemfsUpdate(setOf("system/persona.md")))
        runCurrent()
        assertFalse(controller.state.value.editor!!.conflict)
        gate.complete(Unit)
        runCurrent()
        assertFalse(controller.state.value.editor!!.dirty)
    }

    @Test
    fun leavingADirtyFileIsHeldUntilTheUserDiscards() = runTest {
        val (controller, source) = controller()
        controller.openFile("system/persona.md")
        runCurrent()
        controller.editDraft("Unsaved.")

        controller.openFile("system/human.md")
        assertEquals(MemfsNavigation.Open("system/human.md"), controller.state.value.pendingNavigation)
        assertEquals("system/persona.md", controller.state.value.editor?.path)

        controller.cancelDiscard()
        assertNull(controller.state.value.pendingNavigation)
        assertEquals("Unsaved.", controller.state.value.editor?.draft)

        controller.openFile("system/human.md")
        controller.confirmDiscard()
        runCurrent()
        assertEquals("system/human.md", controller.state.value.editor?.path)
        assertEquals("The user.", controller.state.value.editor?.draft)
        assertTrue(source.writes.isEmpty())
    }

    @Test
    fun switchingAgentsWithADirtyDraftIsGuardedToo() = runTest {
        val (controller, _) = controller()
        controller.openFile("system/persona.md")
        runCurrent()
        controller.editDraft("Unsaved.")

        controller.selectAgent("agent-2")
        assertEquals("agent-1", controller.state.value.agentId)
        assertEquals(MemfsNavigation.SwitchAgent("agent-2"), controller.state.value.pendingNavigation)

        controller.confirmDiscard()
        runCurrent()
        assertEquals("agent-2", controller.state.value.agentId)
        assertNull(controller.state.value.editor)
    }

    @Test
    fun imagesOpenAsAReadOnlyPlaceholder() = runTest {
        val (controller, source) = controller()
        controller.openFile("profile.png")
        runCurrent()
        val editor = controller.state.value.editor!!
        assertTrue(editor.isImage)
        assertFalse(editor.loading)
        assertTrue(source.reads.isEmpty())
        controller.editDraft("text")
        assertFalse(controller.state.value.editor!!.dirty)
    }

    @Test
    fun historyListsCommitsAndSelectingOneShowsItsDiff() = runTest {
        val (controller, _) = controller()
        controller.showHistory("system/persona.md")
        runCurrent()
        val history = controller.state.value.history
        assertEquals(MemfsTab.History, controller.state.value.tab)
        assertEquals("system/persona.md", history.path)
        assertEquals(listOf("aaaaaaa1"), history.commits.map { it.sha })

        controller.selectCommit("aaaaaaa1")
        runCurrent()
        val diff = controller.state.value.history.diff.single()
        assertEquals("system/persona.md", diff.path)
        assertEquals(1 to 1, diff.added to diff.removed)

        controller.clearCommit()
        assertNull(controller.state.value.history.selectedSha)
    }

    @Test
    fun theHistoryTabLoadsTheWholeRepositoryByDefault() = runTest {
        val (controller, _) = controller()
        controller.selectTab(MemfsTab.History)
        runCurrent()
        val history = controller.state.value.history
        assertNull(history.path)
        assertEquals("Update all", history.commits.single().message)
    }

    @Test
    fun aResultForAPreviousAgentIsDropped() {
        val state = MemfsPageState(agentId = "agent-2")
        val listing = MemfsListing(enabled = true, files = listOf(MemfsFile("x.md", false, null, 1, MemfsFileKind.Markdown)))
        assertEquals(state, MemfsPageReducer.listingLoaded(state, MemfsAgentListing("agent-1", listing)))
        assertIs<MemfsPageState>(MemfsPageReducer.fileRead(state, MemfsFileRef("agent-1", "x.md"), "y"))
        assertNull(MemfsPageReducer.fileRead(state, MemfsFileRef("agent-1", "x.md"), "y").editor)
    }
}
