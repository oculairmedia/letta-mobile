package com.letta.mobile.data.workspace.directory

import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-zxk2n.6 (G32): the shared working-directory picker's state and gating. */
class WorkingDirectoryPickerControllerTest {
    private class FakeSource(private val tree: Map<String, List<String>>) : WorkspaceDirectorySource {
        val listed = mutableListOf<String>()
        override suspend fun list(path: String): DirectoryListing {
            listed += path
            val folders = tree[path] ?: throw DirectoryBrowseException(DirectoryBrowseErrors.NOT_FOUND)
            return DirectoryListing(path, folders)
        }
    }

    private class MapStore : SecureSettingsStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue
        override fun putString(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun clear() = values.clear()
    }

    private val tree = mapOf(
        "/" to listOf("work"),
        "/work" to listOf("repo"),
        "/work/repo" to listOf("app", "docs"),
        "/work/repo/.letta/worktrees" to listOf("fix-a", "feat-b"),
        "/work/repo/.letta/worktrees/fix-a" to listOf("app"),
        "/work/repo/app" to emptyList(),
    )

    private val chosen = mutableListOf<String>()

    private fun TestScope.controller(
        source: WorkspaceDirectorySource = FakeSource(tree),
        recents: RecentFolders? = null,
        creator: FolderCreator? = null,
    ) = WorkingDirectoryPickerController(source, backgroundScope, onChosen = { chosen += it }, recents = recents, folderCreator = creator)

    @Test
    fun openingBrowsesTheConversationsDirectoryAndListsItsWorktrees() = runTest {
        val picker = controller()
        picker.open("/work/repo/")
        runCurrent()

        val state = picker.state.value
        assertTrue(state.isOpen)
        assertEquals("/work/repo", state.folder)
        assertEquals(listOf("app", "docs"), state.folders)
        assertEquals(listOf("/work/repo/.letta/worktrees/fix-a", "/work/repo/.letta/worktrees/feat-b"), state.worktrees.paths)
        assertNull(state.worktree)
    }

    @Test
    fun insideAWorktreeTheBannerKnowsTheRepositoryAndItsSiblings() = runTest {
        val picker = controller()
        picker.open("/work/repo/.letta/worktrees/fix-a")
        runCurrent()

        val state = picker.state.value
        assertEquals("fix-a", state.worktree?.name)
        assertEquals("/work/repo", state.worktree?.root)
        assertEquals(2, state.worktrees.paths.size)

        picker.choose(state.worktree!!.root)
        assertEquals(listOf("/work/repo"), chosen)
    }

    @Test
    fun anUnknownCwdOpensWithNothingBrowsedAndAMissingWorktreesFolderIsNoWorktrees() = runTest {
        val source = FakeSource(tree)
        val picker = controller(source)
        picker.open(null)
        runCurrent()
        assertNull(picker.state.value.folder)
        assertTrue(source.listed.isEmpty())

        picker.open("/work/repo/app")
        runCurrent()
        assertEquals(emptyList(), picker.state.value.worktrees.paths)
        assertNull(picker.state.value.error)
    }

    @Test
    fun upAndTypedPathsBrowseAndARelativePathIsRefused() = runTest {
        val picker = controller()
        picker.open("/work/repo")
        runCurrent()
        picker.up()
        runCurrent()
        assertEquals("/work", picker.state.value.folder)
        assertEquals(listOf("repo"), picker.state.value.folders)

        picker.browse("relative/path")
        assertEquals(WorkingDirectoryPickerGates.NOT_ABSOLUTE, picker.state.value.error)
        assertEquals("/work", picker.state.value.folder)
    }

    @Test
    fun aMissingFolderShowsWhyAndIsForgottenFromRecents() = runTest {
        val recents = RecentFolders(MapStore())
        recents.record("/gone")
        val picker = controller(recents = recents)
        picker.open(null)
        assertEquals(listOf("/gone"), picker.state.value.recents)

        picker.browse("/gone")
        runCurrent()

        assertEquals(DirectoryBrowseErrors.NOT_FOUND, picker.state.value.error)
        assertEquals(emptyList(), picker.state.value.recents)
    }

    @Test
    fun choosingRecordsARecentClosesAndHandsThePathOver() = runTest {
        val recents = RecentFolders(MapStore())
        val picker = controller(recents = recents)
        picker.open("/work")
        runCurrent()

        picker.choose("/work/repo/")

        assertEquals(listOf("/work/repo"), chosen)
        assertEquals(listOf("/work/repo"), recents.recent.value)
        assertFalse(picker.state.value.isOpen)
    }

    @Test
    fun withoutAFolderCreatorCreatingIsGatedWithAReason() = runTest {
        val picker = controller()
        picker.open("/work")
        runCurrent()

        val state = picker.state.value
        assertEquals(WorkingDirectoryPickerGates.CREATE_FOLDER_UNAVAILABLE, state.createFolderUnavailableReason)
        assertFalse(state.canCreateFolder)
        picker.createFolder("new")
        runCurrent()
        assertEquals("/work", picker.state.value.folder)
    }

    @Test
    fun creatingAFolderMovesIntoIt() = runTest {
        val created = mutableListOf<Pair<String, String>>()
        val source = FakeSource(tree + ("/work/new" to emptyList()))
        val picker = controller(source, creator = FolderCreator { parent, name ->
            created += parent to name
            DirectoryPaths.child(parent, name)
        })
        picker.open("/work")
        runCurrent()
        assertTrue(picker.state.value.canCreateFolder)

        picker.createFolder(" new ")
        runCurrent()

        assertEquals(listOf("/work" to "new"), created)
        assertEquals("/work/new", picker.state.value.folder)
        assertFalse(picker.state.value.isCreatingFolder)
    }

    @Test
    fun anInvalidOrFailedNewFolderShowsWhy() = runTest {
        val picker = controller(creator = FolderCreator { _, _ -> throw DirectoryBrowseException("A folder with this name already exists.") })
        picker.open("/work")
        runCurrent()

        picker.createFolder("a/b")
        assertEquals(DirectoryBrowseErrors.INVALID_NAME, picker.state.value.error)

        picker.createFolder("repo")
        runCurrent()
        assertEquals("A folder with this name already exists.", picker.state.value.error)
        assertEquals("/work", picker.state.value.folder)
    }

    @Test
    fun aGatedSourceStillLetsATypedPathBeChosen() = runTest {
        val gated = WorkspaceDirectorySource { throw DirectoryBrowseException(DirectoryBrowseErrors.BROWSE_UNAVAILABLE) }
        val picker = controller(gated)
        picker.open("/work")
        runCurrent()
        assertEquals(DirectoryBrowseErrors.BROWSE_UNAVAILABLE, picker.state.value.error)

        picker.browse("/srv/app")
        runCurrent()
        picker.choose(picker.state.value.folder!!)
        assertEquals(listOf("/srv/app"), chosen)
    }
}
