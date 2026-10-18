@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.workspace.directory.DirectoryBrowseException
import com.letta.mobile.data.workspace.directory.DirectoryBrowseErrors
import com.letta.mobile.data.workspace.directory.DirectoryListing
import com.letta.mobile.data.workspace.directory.DevicePath
import com.letta.mobile.data.workspace.directory.FolderCreator
import com.letta.mobile.data.workspace.directory.FolderName
import com.letta.mobile.data.workspace.directory.WorkingDirectoryPickerController
import com.letta.mobile.data.workspace.directory.WorkingDirectoryPickerGates
import com.letta.mobile.data.workspace.directory.WorkspaceDirectorySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-zxk2n.6 (G32): the shared working-directory picker, driven through its controller. */
class WorkingDirectoryPickerUiTest {
    private val tree = mutableMapOf(
        "/work" to listOf("repo"),
        "/work/repo" to listOf("app", "docs"),
        "/work/repo/app" to emptyList(),
        "/work/repo/.letta/worktrees" to listOf("fix-login", "feat-search"),
        "/work/repo/.letta/worktrees/fix-login" to emptyList(),
        "/work/repo/.letta/worktrees/feat-search" to emptyList(),
    )
    private val source = WorkspaceDirectorySource { path ->
        DirectoryListing(path, tree[path] ?: throw DirectoryBrowseException(DirectoryBrowseErrors.NOT_FOUND))
    }
    private val chosen = mutableListOf<String>()

    private fun ComposeUiTest.show(
        cwd: String?,
        creator: FolderCreator? = null,
        host: WorkingDirectoryPickerHost = WorkingDirectoryPickerHost(),
        directorySource: WorkspaceDirectorySource = source,
        agentId: String? = null,
        onMakeDefault: (String) -> Unit = {},
    ): WorkingDirectoryPickerController {
        // The fake answers at once, so the controller's work runs inline.
        val controller = WorkingDirectoryPickerController(
            directorySource,
            CoroutineScope(Dispatchers.Unconfined),
            onChosen = { chosen += it },
            folderCreator = creator,
        ).also { it.onMakeDefault = onMakeDefault }
        setContent {
            MaterialTheme {
                val state by controller.state.collectAsState()
                Box(Modifier.width(1100.dp).height(900.dp)) { WorkingDirectoryPicker(state, controller, host = host) }
            }
        }
        runOnIdle { controller.open(cwd, agentId?.let(::AgentId)) }
        return controller
    }

    @Test
    fun browsingIntoAFolderAndUsingItAppliesItAndCloses() = runComposeUiTest {
        show("/work/repo")
        onNodeWithTag(WorkingDirectoryPickerTags.FOLDER_ROW + "app").performClick()
        onNodeWithTag(WorkingDirectoryPickerTags.USE).assertIsEnabled().performClick()
        runOnIdle { assertEquals(listOf("/work/repo/app"), chosen) }
        onNodeWithTag(WorkingDirectoryPickerTags.ROOT).assertDoesNotExist()
    }

    @Test
    fun makeDefaultSavesTheFolderForTheAgentWithoutClosing() = runComposeUiTest {
        val saved = mutableListOf<String>()
        show("/work/repo", agentId = "agent-1", onMakeDefault = { saved += it })
        onNodeWithTag(WorkingDirectoryPickerTags.MAKE_DEFAULT).performClick()
        runOnIdle {
            assertEquals(listOf("/work/repo"), saved)
            assertEquals(true, chosen.isEmpty())
        }
        onNodeWithTag(WorkingDirectoryPickerTags.ROOT).assertExists()
    }

    @Test
    fun withoutAnAgentMakeDefaultIsHidden() = runComposeUiTest {
        show("/work/repo")
        onNodeWithTag(WorkingDirectoryPickerTags.MAKE_DEFAULT).assertDoesNotExist()
    }

    @Test
    fun theCurrentDirectoryCannotBeReapplied() = runComposeUiTest {
        show("/work/repo")
        onNodeWithTag(WorkingDirectoryPickerTags.USE).assertIsNotEnabled()
    }

    @Test
    fun aTypedPathBrowsesThere() = runComposeUiTest {
        show("/work/repo")
        onNodeWithTag(WorkingDirectoryPickerTags.PATH).performTextReplacement("/work")
        onNodeWithTag(WorkingDirectoryPickerTags.GO).performClick()
        onNodeWithTag(WorkingDirectoryPickerTags.FOLDER_ROW + "repo").assertExists()
    }

    @Test
    fun withoutAFolderCreatorNewFolderIsDisabledWithTheReason() = runComposeUiTest {
        show("/work/repo")
        onNodeWithTag(WorkingDirectoryPickerTags.NEW_FOLDER).assertIsNotEnabled()
        onNodeWithText(WorkingDirectoryPickerGates.CREATE_FOLDER_UNAVAILABLE).assertExists()
        onNodeWithTag(WorkingDirectoryPickerTags.FILE_MANAGER).assertDoesNotExist()
        onNodeWithTag(WorkingDirectoryPickerTags.SYSTEM_DIALOG).assertDoesNotExist()
    }

    @Test
    fun creatingAFolderMovesIntoIt() = runComposeUiTest {
        val creator = FolderCreator { parent, name ->
            DevicePath(parent).child(FolderName(name)).value.also { tree[it] = emptyList() }
        }
        show("/work/repo", creator = creator)
        onNodeWithTag(WorkingDirectoryPickerTags.NEW_FOLDER).assertIsEnabled().performClick()
        onNodeWithTag(WorkingDirectoryPickerTags.NEW_FOLDER_NAME).performTextInput("notes")
        onNodeWithTag(WorkingDirectoryPickerTags.CREATE).performClick()
        onNodeWithTag(WorkingDirectoryPickerTags.USE).performClick()
        runOnIdle { assertEquals(listOf("/work/repo/notes"), chosen) }
    }

    @Test
    fun hostExtrasAppearWhenTheHostHasThem() = runComposeUiTest {
        val opened = mutableListOf<String>()
        var systemDialogs = 0
        show("/work/repo", host = WorkingDirectoryPickerHost(openInFileManager = { opened += it }, pickWithSystemDialog = { systemDialogs++ }))
        onNodeWithTag(WorkingDirectoryPickerTags.FILE_MANAGER).performClick()
        onNodeWithTag(WorkingDirectoryPickerTags.SYSTEM_DIALOG).performClick()
        runOnIdle {
            assertEquals(listOf("/work/repo"), opened)
            assertEquals(1, systemDialogs)
        }
    }

    @Test
    fun worktreesAreListedSearchableAndSwitchable() = runComposeUiTest {
        show("/work/repo")
        onNodeWithTag(WorkingDirectoryPickerTags.WORKTREE_SEARCH).performTextInput("search")
        onNodeWithTag(WorkingDirectoryPickerTags.WORKTREE_ROW + "fix-login").assertDoesNotExist()
        onNodeWithTag(WorkingDirectoryPickerTags.WORKTREE_ROW + "feat-search").performClick()
        onNodeWithTag(WorkingDirectoryPickerTags.USE).performClick()
        runOnIdle { assertEquals(listOf("/work/repo/.letta/worktrees/feat-search"), chosen) }
    }

    @Test
    fun insideAWorktreeTheBannerSwapsBackToTheRepository() = runComposeUiTest {
        show("/work/repo/.letta/worktrees/fix-login")
        onNodeWithTag(WorkingDirectoryPickerTags.BANNER).assertExists()
        onNodeWithTag(WorkingDirectoryPickerTags.BACK_TO_REPOSITORY).performClick()
        runOnIdle { assertEquals(listOf("/work/repo"), chosen) }
    }

    @Test
    fun whenBrowsingIsGatedTheReasonShowsAndATypedPathCanStillBeUsed() = runComposeUiTest {
        val gated = WorkspaceDirectorySource { throw DirectoryBrowseException(DirectoryBrowseErrors.BROWSE_UNAVAILABLE) }
        show("/work/repo", directorySource = gated)
        onNodeWithText(DirectoryBrowseErrors.BROWSE_UNAVAILABLE).assertExists()
        onNodeWithTag(WorkingDirectoryPickerTags.PATH).performTextReplacement("/srv/app")
        onNodeWithTag(WorkingDirectoryPickerTags.GO).performClick()
        onNodeWithTag(WorkingDirectoryPickerTags.USE).performClick()
        runOnIdle { assertEquals(listOf("/srv/app"), chosen) }
    }
}
