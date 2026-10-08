@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.memfs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.memory.memfs.MemfsCommit
import com.letta.mobile.data.memory.memfs.MemfsCommitDiff
import com.letta.mobile.data.memory.memfs.MemfsEditor
import com.letta.mobile.data.memory.memfs.MemfsFile
import com.letta.mobile.data.memory.memfs.MemfsFileKind
import com.letta.mobile.data.memory.memfs.MemfsHistory
import com.letta.mobile.data.memory.memfs.MemfsLoad
import com.letta.mobile.data.memory.memfs.MemfsNavigation
import com.letta.mobile.data.memory.memfs.MemfsPageActions
import com.letta.mobile.data.memory.memfs.MemfsPageState
import com.letta.mobile.data.memory.memfs.MemfsTab
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared MemFS page on a wide desktop window and a phone (letta-mobile-bzvro.24). */
class MemfsPageTest {
    private val files = listOf(
        MemfsFile("system/persona.md", isSystem = true, description = "Who I am", sizeBytes = 2048, kind = MemfsFileKind.Markdown),
        MemfsFile("notes/today.md", isSystem = false, description = null, sizeBytes = 12, kind = MemfsFileKind.Markdown),
    )
    private val loaded = MemfsPageState(agentId = "agent-1", load = MemfsLoad.Loaded, files = files)

    private fun ComposeUiTest.show(state: MemfsPageState, actions: MemfsPageActions, width: Dp) {
        setContent {
            MaterialTheme {
                Box(Modifier.width(width).height(PAGE_HEIGHT)) { MemfsPage(state = state, actions = actions) }
            }
        }
    }

    @Test
    fun systemAndExternalFilesAreListedSeparately() = runComposeUiTest {
        show(loaded, RecordingActions(), WIDE)
        onNodeWithText("SYSTEM").assertExists()
        onNodeWithText("EXTERNAL").assertExists()
        onNodeWithTag(MemfsPageTags.file("system/persona.md")).assertExists()
        onNodeWithText("persona.md").assertExists()
        onNodeWithText("2.0 KB").assertExists()
        onNodeWithText("notes/today.md").assertExists()
    }

    @Test
    fun tappingAFileOpensIt() = runComposeUiTest {
        val actions = RecordingActions()
        show(loaded, actions, COMPACT)
        onNodeWithTag(MemfsPageTags.file("notes/today.md")).performClick()
        assertEquals(listOf("open:notes/today.md"), actions.calls)
    }

    @Test
    fun typingFiltersThroughTheController() = runComposeUiTest {
        val actions = RecordingActions()
        show(loaded, actions, WIDE)
        onNodeWithTag(MemfsPageTags.SEARCH).performTextInput("persona")
        assertEquals(listOf("query:persona"), actions.calls)
    }

    @Test
    fun aDisabledAgentOffersToEnableMemfs() = runComposeUiTest {
        val actions = RecordingActions()
        show(MemfsPageState(agentId = "agent-1", load = MemfsLoad.Loaded, memfsEnabled = false), actions, COMPACT)
        onNodeWithText("Memory files are off for this agent.").assertExists()
        onNodeWithTag(MemfsPageTags.ENABLE).performClick()
        assertEquals(listOf("enable"), actions.calls)
    }

    @Test
    fun aFailedListingShowsItsReasonAndRetries() = runComposeUiTest {
        val actions = RecordingActions()
        show(MemfsPageState(agentId = "agent-1", load = MemfsLoad.Failed("The App Server did not answer in time.")), actions, WIDE)
        onNodeWithText("The App Server did not answer in time.").assertExists()
        onNodeWithText("Try again").performClick()
        assertEquals(listOf("refresh"), actions.calls)
    }

    @Test
    fun aDirtyEditorEnablesSaveAndShowsUnsaved() = runComposeUiTest {
        val actions = RecordingActions()
        val editor = MemfsEditor("system/persona.md", original = "a", draft = "b", loading = false)
        show(loaded.copy(editor = editor), actions, WIDE)
        onNodeWithText("Unsaved changes").assertExists()
        onNodeWithTag(MemfsPageTags.SAVE).assertIsEnabled().performClick()
        assertEquals(listOf("save"), actions.calls)
    }

    @Test
    fun aCleanEditorCannotSave() = runComposeUiTest {
        val editor = MemfsEditor("system/persona.md", original = "a", draft = "a", loading = false)
        show(loaded.copy(editor = editor), RecordingActions(), WIDE)
        onNodeWithTag(MemfsPageTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun aConflictOffersBothVersions() = runComposeUiTest {
        val actions = RecordingActions()
        val editor = MemfsEditor("system/persona.md", original = "a", draft = "b", loading = false, conflict = true)
        show(loaded.copy(editor = editor), actions, WIDE)
        onNodeWithTag(MemfsPageTags.CONFLICT).assertExists()
        onNodeWithTag(MemfsPageTags.CONFLICT_RELOAD).performClick()
        onNodeWithTag(MemfsPageTags.CONFLICT_KEEP).performClick()
        assertEquals(listOf("reload", "keep"), actions.calls)
    }

    @Test
    fun theUnsavedChangesGuardAsksBeforeDiscarding() = runComposeUiTest {
        val actions = RecordingActions()
        val editor = MemfsEditor("system/persona.md", original = "a", draft = "b", loading = false)
        show(loaded.copy(editor = editor, pendingNavigation = MemfsNavigation.Open("notes/today.md")), actions, WIDE)
        onNodeWithText("Discard unsaved changes?").assertExists()
        onNodeWithTag(MemfsPageTags.DISCARD_CONFIRM).performClick()
        assertEquals(listOf("discard"), actions.calls)
    }

    @Test
    fun onAPhoneTheOpenFileReplacesTheList() = runComposeUiTest {
        val editor = MemfsEditor("notes/today.md", original = "x", draft = "x", loading = false)
        show(loaded.copy(editor = editor), RecordingActions(), COMPACT)
        onNodeWithTag(MemfsPageTags.EDITOR).assertExists()
        onNodeWithTag(MemfsPageTags.LIST).assertDoesNotExist()
    }

    @Test
    fun historyShowsCommitsAndTheSelectedDiff() = runComposeUiTest {
        val actions = RecordingActions()
        val patch = "diff --git a/system/persona.md b/system/persona.md\n--- a/system/persona.md\n+++ b/system/persona.md\n@@ -1 +1 @@\n-I like tea.\n+I like coffee."
        val history = MemfsHistory(
            commits = listOf(MemfsCommit("abcdef1234", "Update persona", "2026-10-01T10:00:00Z", "Letta")),
            selectedSha = "abcdef1234",
            diff = MemfsCommitDiff.parse(patch),
        )
        show(loaded.copy(tab = MemfsTab.History, history = history), actions, WIDE)
        onNodeWithTag(MemfsPageTags.commit("abcdef1234")).assertExists()
        onNodeWithText("abcdef1 · Letta · 2026-10-01T10:00").assertExists()
        onNodeWithTag(MemfsPageTags.diffFile("system/persona.md")).assertExists()
        onNodeWithText("+I like coffee.").assertExists()
        onNodeWithText("-I like tea.").assertExists()
        onNodeWithText("+1 -1").assertExists()
        onNodeWithTag(MemfsPageTags.tab(MemfsTab.Files)).performClick()
        assertEquals(listOf("tab:Files"), actions.calls)
    }

    private class RecordingActions : MemfsPageActions {
        val calls = mutableListOf<String>()

        override fun refresh() { calls += "refresh" }
        override fun updateQuery(query: String) { calls += "query:$query" }
        override fun selectTab(tab: MemfsTab) { calls += "tab:${tab.name}" }
        override fun openFile(path: String) { calls += "open:$path" }
        override fun closeFile() { calls += "close" }
        override fun editDraft(text: String) { calls += "edit" }
        override fun save() { calls += "save" }
        override fun revertDraft() { calls += "revert" }
        override fun confirmDiscard() { calls += "discard" }
        override fun cancelDiscard() { calls += "cancel" }
        override fun reloadFromServer() { calls += "reload" }
        override fun keepDraft() { calls += "keep" }
        override fun showHistory(path: String?) { calls += "history:$path" }
        override fun selectCommit(sha: String) { calls += "commit:$sha" }
        override fun clearCommit() { calls += "clearCommit" }
        override fun enableMemfs() { calls += "enable" }
    }

    private companion object {
        val WIDE = 1100.dp
        val COMPACT = 380.dp
        val PAGE_HEIGHT = 800.dp
    }
}
