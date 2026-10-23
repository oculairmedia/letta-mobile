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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp

import com.letta.mobile.data.workspace.WorkspaceFileContent
import com.letta.mobile.data.workspace.WorkspaceFileOpener
import com.letta.mobile.data.workspace.WorkspaceFileRequest
import com.letta.mobile.data.workspace.WorkspaceFileViewerActions
import com.letta.mobile.data.workspace.WorkspaceFileViewerState
import kotlinx.coroutines.flow.MutableStateFlow
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
        onNodeWithContentDescription("Close").performClick()
        assertEquals(listOf("retry", "close"), actions.calls)
    }

    @Test
    fun aClosedViewerDrawsNothing() = runComposeUiTest {
        show(WorkspaceFileViewerState(), RecordingActions())
        onNodeWithTag(WorkspaceFileViewerTags.VIEWER).assertDoesNotExist()
    }

    @Test
    fun aChipOpensItsFileAtItsLinesOnlyWhenTheHostReadsFiles() = runComposeUiTest {
        val opened = mutableListOf<WorkspaceFileRequest>()
        val files = WorkspaceFiles(WorkspaceFileOpener { opened += it }, MutableStateFlow(WorkspaceFileViewerState()), RecordingActions())
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalWorkspaceFiles provides files) {
                    WorkspaceFileChip(WorkspaceFileRequest("/repo/a.kt", lines = 3..4))
                }
            }
        }
        onNodeWithText("a.kt #L3-4").performClick()
        assertEquals(listOf(WorkspaceFileRequest("/repo/a.kt", lines = 3..4)), opened)
    }

    @Test
    fun withoutAHostThatReadsFilesThereIsNoChip() = runComposeUiTest {
        setContent { MaterialTheme { WorkspaceFileChip(WorkspaceFileRequest("/repo/a.kt")) } }
        onNodeWithTag(WorkspaceFileLinkTags.LINK).assertDoesNotExist()
    }

    @Test
    fun theLinesAFileWasOpenedAtAreFoundInItsText() {
        val text = "one\ntwo\nthree\nfour"
        assertEquals(4 to 14, lineSpan(text, 2..3))
        assertEquals(14 to 18, lineSpan(text, 4..4))
        assertEquals(null, lineSpan(text, 9..9))
    }

    private class RecordingActions : WorkspaceFileViewerActions {
        val calls = mutableListOf<String>()
        override fun close() { calls += "close" }
        override fun retry() { calls += "retry" }
    }
}
