package com.letta.mobile.data.workspace.directory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-zxk2n.6: folder arithmetic for device paths, and letta-code worktree locations. */
class DirectoryPathsTest {
    @Test
    fun parentWalksUpToTheRootOnPosixAndWindows() {
        assertEquals("/home", DirectoryPaths.parent("/home/me/"))
        assertEquals("/", DirectoryPaths.parent("/home"))
        assertNull(DirectoryPaths.parent("/"))
        assertEquals("C:\\Users", DirectoryPaths.parent("C:\\Users\\me"))
        assertEquals("C:\\", DirectoryPaths.parent("C:\\Users"))
        assertNull(DirectoryPaths.parent("C:\\"))
    }

    @Test
    fun childUsesTheParentsSeparator() {
        assertEquals("/home/me/src", DirectoryPaths.child("/home/me/", "src"))
        assertEquals("/src", DirectoryPaths.child("/", "src"))
        assertEquals("C:\\Users\\me", DirectoryPaths.child("C:\\Users", "me"))
        assertEquals("C:\\me", DirectoryPaths.child("C:\\", "me"))
    }

    @Test
    fun namesAndSameness() {
        assertEquals("me", DirectoryPaths.name("/home/me/"))
        assertEquals("/", DirectoryPaths.name("/"))
        assertTrue(DirectoryPaths.same("/a/b/", "/a/b"))
        assertFalse(DirectoryPaths.same("/a/b", null))
    }

    @Test
    fun onlyASingleSegmentIsAValidNewFolderName() {
        assertTrue(DirectoryPaths.isValidFolderName("new-folder"))
        assertFalse(DirectoryPaths.isValidFolderName(" "))
        assertFalse(DirectoryPaths.isValidFolderName(".."))
        assertFalse(DirectoryPaths.isValidFolderName("a/b"))
        assertFalse(DirectoryPaths.isValidFolderName("a\\b"))
    }

    @Test
    fun aCwdInsideALettaWorktreeIsLocatedWithItsRepository() {
        val location = WorktreePaths.locate("/work/repo/.letta/worktrees/fix-login/src")
        assertEquals(WorktreeLocation(root = "/work/repo", name = "fix-login", path = "/work/repo/.letta/worktrees/fix-login"), location)
        assertEquals("/work/repo", WorktreePaths.repositoryOf("/work/repo/.letta/worktrees/fix-login"))
    }

    @Test
    fun windowsWorktreesAndRootRepositoriesAreLocatedToo() {
        assertEquals("C:\\code\\app", WorktreePaths.locate("C:\\code\\app\\.letta\\worktrees\\wt")?.root)
        assertEquals("C:\\", WorktreePaths.locate("C:\\.letta\\worktrees\\wt")?.root)
        assertEquals("/", WorktreePaths.locate("/.letta/worktrees/wt")?.root)
    }

    @Test
    fun aCwdOutsideAnyWorktreeIsNotOne() {
        assertNull(WorktreePaths.locate("/work/repo"))
        assertNull(WorktreePaths.locate("/work/repo/.letta/worktrees"))
        assertNull(WorktreePaths.locate(null))
        assertEquals("/work/repo", WorktreePaths.repositoryOf("/work/repo/"))
    }

    @Test
    fun theWorktreesFolderIsUnderTheRepositorysLettaDirectory() {
        assertEquals("/work/repo/.letta/worktrees", WorktreePaths.worktreesFolder("/work/repo"))
    }
}
