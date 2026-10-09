@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.workspace.WorkspaceFileContent
import com.letta.mobile.data.workspace.WorkspaceFileOpener
import com.letta.mobile.data.workspace.WorkspaceFileViewerActions
import com.letta.mobile.data.workspace.WorkspaceFileViewerState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared read-only file viewer and the tool card's file link (letta-mobile-bzvro.26). */
class WorkspaceFileViewerTest {
    private fun ComposeUiTest.show(state: WorkspaceFileViewerState, actions: WorkspaceFileViewerActions) {
        setContent {
            MaterialTheme {
                Box(Modifier.width(1100.dp).height(800.dp)) { WorkspaceFileViewer(state, actions) }
            }
        }
    }

    @Test
    fun aTextFileShowsItsLines() = runComposeUiTest {
        val path = "/repo/src/Main.kt"
        show(WorkspaceFileViewerState(path = path, content = WorkspaceFileContent.Text(path, "fun main() {\n}")), RecordingActions())
        onNodeWithText("Main.kt").assertExists()
        onNodeWithTag(WorkspaceFileViewerTags.TEXT).assertExists()
        onNodeWithText("1\n2").assertExists()
    }

    @Test
    fun binaryAndOversizedFilesShowAPlaceholder() = runComposeUiTest {
        show(WorkspaceFileViewerState(path = "/a.png", content = WorkspaceFileContent.Binary("/a.png")), RecordingActions())
        onNodeWithText("This file is not text, so it can't be shown here.").assertExists()
    }

    @Test
    fun anOversizedFileSaysHowLarge() = runComposeUiTest {
        show(WorkspaceFileViewerState(path = "/big.log", content = WorkspaceFileContent.TooLarge("/big.log", 900000)), RecordingActions())
        onNodeWithText("This file is too large to show here (900000 characters).").assertExists()
    }

    @Test
    fun aFailedReadOffersARetryAndCloses() = runComposeUiTest {
        val actions = RecordingActions()
        show(WorkspaceFileViewerState(path = "/gone.kt", error = "ENOENT"), actions)
        onNodeWithTag(WorkspaceFileViewerTags.RETRY).performClick()
        onNodeWithTag(WorkspaceFileViewerTags.CLOSE).performClick()
        assertEquals(listOf("retry", "close"), actions.calls)
    }

    @Test
    fun aClosedViewerDrawsNothing() = runComposeUiTest {
        show(WorkspaceFileViewerState(), RecordingActions())
        onNodeWithTag(WorkspaceFileViewerTags.VIEWER).assertDoesNotExist()
    }

    @Test
    fun aToolCardLinkOpensTheFileOnlyWhenTheHostCanReadFiles() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalWorkspaceFileOpener provides WorkspaceFileOpener { path, _ -> opened += path }) {
                    ToolCallFileLink("""{"file_path":"/repo/a.kt"}""")
                }
            }
        }
        onNodeWithText("a.kt").performClick()
        assertEquals(listOf("/repo/a.kt"), opened)
    }

    @Test
    fun withoutAnOpenerThereIsNoLink() = runComposeUiTest {
        setContent { MaterialTheme { ToolCallFileLink("""{"file_path":"/repo/a.kt"}""") } }
        onNodeWithTag(WorkspaceFileLinkTags.LINK).assertDoesNotExist()
    }

    private class RecordingActions : WorkspaceFileViewerActions {
        val calls = mutableListOf<String>()
        override fun close() { calls += "close" }
        override fun retry() { calls += "retry" }
    }
}
