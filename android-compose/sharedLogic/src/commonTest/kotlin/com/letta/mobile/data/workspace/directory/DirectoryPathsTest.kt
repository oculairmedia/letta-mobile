package com.letta.mobile.data.workspace.directory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-zxk2n.6: folder arithmetic for device paths, and letta-code worktree locations. */
class DirectoryPathsTest {
    private fun parentOf(path: String): String? = DevicePath(path).parent?.value

    private fun childOf(parent: String, name: String): String = DevicePath(parent).child(FolderName(name)).value

    @Test
    fun parentWalksUpToTheRootOnPosixAndWindows() {
        assertEquals("/home", parentOf("/home/me/"))
        assertEquals("/", parentOf("/home"))
        assertNull(parentOf("/"))
        assertEquals("C:\\Users", parentOf("C:\\Users\\me"))
        assertEquals("C:\\", parentOf("C:\\Users"))
        assertNull(parentOf("C:\\"))
    }

    @Test
    fun childUsesTheParentsSeparator() {
        assertEquals("/home/me/src", childOf("/home/me/", "src"))
        assertEquals("/src", childOf("/", "src"))
        assertEquals("C:\\Users\\me", childOf("C:\\Users", "me"))
        assertEquals("C:\\me", childOf("C:\\", "me"))
    }

    @Test
    fun namesAndSameness() {
        assertEquals("me", DevicePath("/home/me/").name)
        assertEquals("/", DevicePath("/").name)
        assertTrue(DevicePath("/a/b/").sameAs(DevicePath("/a/b")))
        assertFalse(DevicePath("/a/b").sameAs(null))
        assertNull(devicePathOrNull(" "))
    }

    @Test
    fun onlyASingleSegmentIsAValidNewFolderName() {
        assertTrue(FolderName("new-folder").isValid)
        assertFalse(FolderName(" ").isValid)
        assertFalse(FolderName("..").isValid)
        assertFalse(FolderName("a/b").isValid)
        assertFalse(FolderName("a\\b").isValid)
    }

    @Test
    fun aCwdInsideALettaWorktreeIsLocatedWithItsRepository() {
        val location = WorktreePaths.locate(DevicePath("/work/repo/.letta/worktrees/fix-login/src"))
        assertEquals(WorktreeLocation(root = "/work/repo", name = "fix-login", path = "/work/repo/.letta/worktrees/fix-login"), location)
        assertEquals("/work/repo", WorktreePaths.repositoryOf(DevicePath("/work/repo/.letta/worktrees/fix-login")).value)
    }

    @Test
    fun windowsWorktreesAndRootRepositoriesAreLocatedToo() {
        assertEquals("C:\\code\\app", WorktreePaths.locate(DevicePath("C:\\code\\app\\.letta\\worktrees\\wt"))?.root)
        assertEquals("C:\\", WorktreePaths.locate(DevicePath("C:\\.letta\\worktrees\\wt"))?.root)
        assertEquals("/", WorktreePaths.locate(DevicePath("/.letta/worktrees/wt"))?.root)
    }

    @Test
    fun aCwdOutsideAnyWorktreeIsNotOne() {
        assertNull(WorktreePaths.locate(DevicePath("/work/repo")))
        assertNull(WorktreePaths.locate(DevicePath("/work/repo/.letta/worktrees")))
        assertNull(WorktreePaths.locate(null))
        assertEquals("/work/repo", WorktreePaths.repositoryOf(DevicePath("/work/repo/")).value)
    }

    @Test
    fun theWorktreesFolderIsUnderTheRepositorysLettaDirectory() {
        assertEquals("/work/repo/.letta/worktrees", WorktreePaths.worktreesFolder(DevicePath("/work/repo")).value)
    }
}
