@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.vault

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.secrets.AgentSecret
import com.letta.mobile.data.secrets.AgentSecretDraft
import com.letta.mobile.data.secrets.AgentVaultActions
import com.letta.mobile.data.secrets.AgentVaultLoad
import com.letta.mobile.data.secrets.AgentVaultState
import com.letta.mobile.data.secrets.SecretKey
import com.letta.mobile.data.secrets.SecretValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared secrets vault on a wide desktop window and a phone (letta-mobile-bzvro.25). */
class AgentVaultPageTest {
    private val secret = "sk-live-VALUE-123"
    private val loaded = AgentVaultState(
        agentId = "agent-1",
        load = AgentVaultLoad.Loaded,
        secrets = listOf(AgentSecret("OPENAI_API_KEY", SecretValue(secret))),
    )

    private fun ComposeUiTest.show(state: AgentVaultState, actions: AgentVaultActions, width: Dp) {
        setContent {
            MaterialTheme {
                Box(Modifier.width(width).height(PAGE_HEIGHT)) { AgentVaultPage(state = state, actions = actions) }
            }
        }
    }

    @Test
    fun valuesShowMaskedByDefault() = runComposeUiTest {
        show(loaded, RecordingActions(), WIDE)
        onNodeWithText("OPENAI_API_KEY").assertExists()
        onNodeWithTag(AgentVaultPageTags.value("OPENAI_API_KEY")).assertTextEquals(SecretValue.MASK)
        onAllNodesWithText(secret).fetchSemanticsNodes().let { assertTrue(it.isEmpty(), "the value must not render masked") }
    }

    @Test
    fun revealingShowsTheValueOnlyWhenAsked() = runComposeUiTest {
        var state by mutableStateOf(loaded)
        val actions = RecordingActions { key -> state = state.copy(revealed = state.revealed + key) }
        setContent {
            MaterialTheme {
                Box(Modifier.width(WIDE).height(PAGE_HEIGHT)) { AgentVaultPage(state = state, actions = actions) }
            }
        }
        onNodeWithTag(AgentVaultPageTags.reveal("OPENAI_API_KEY")).performClick()
        onNodeWithTag(AgentVaultPageTags.value("OPENAI_API_KEY")).assertTextEquals(secret)
    }

    @Test
    fun rowActionsReachTheController() = runComposeUiTest {
        val actions = RecordingActions()
        show(loaded, actions, COMPACT)
        onNodeWithTag(AgentVaultPageTags.edit("OPENAI_API_KEY")).performClick()
        onNodeWithTag(AgentVaultPageTags.delete("OPENAI_API_KEY")).performClick()
        onNodeWithTag(AgentVaultPageTags.ADD).performClick()
        assertEquals(listOf("edit:OPENAI_API_KEY", "delete:OPENAI_API_KEY", "add"), actions.calls)
    }

    @Test
    fun theEditorDocksBesideTheListOnWideWindows() = runComposeUiTest {
        show(loaded.copy(draft = AgentSecretDraft()), RecordingActions(), WIDE)
        onNodeWithTag(AgentVaultPageTags.EDITOR).assertExists()
        onNodeWithTag(AgentVaultPageTags.LIST).assertExists()
        onNodeWithTag(AgentVaultPageTags.EDITOR_SAVE).assertIsNotEnabled()
    }

    @Test
    fun aDuplicateKeyIsExplainedAndCannotSave() = runComposeUiTest {
        val draft = AgentSecretDraft(key = "openai_api_key", value = SecretValue("x"))
        show(loaded.copy(draft = draft), RecordingActions(), WIDE)
        onNodeWithText("OPENAI_API_KEY already exists; edit it instead.").assertExists()
        onNodeWithTag(AgentVaultPageTags.EDITOR_SAVE).assertIsNotEnabled()
    }

    @Test
    fun deletingAsksFirst() = runComposeUiTest {
        val actions = RecordingActions()
        show(loaded.copy(pendingDelete = "OPENAI_API_KEY"), actions, WIDE)
        onNodeWithText("Delete OPENAI_API_KEY?").assertExists()
        onNodeWithTag(AgentVaultPageTags.DELETE_CONFIRM).performClick()
        assertEquals(listOf("confirmDelete"), actions.calls)
    }

    @Test
    fun anEmptyVaultSaysSo() = runComposeUiTest {
        show(AgentVaultState(agentId = "agent-1", load = AgentVaultLoad.Loaded), RecordingActions(), COMPACT)
        onNodeWithText("This agent has no secrets yet.").assertExists()
    }

    @Test
    fun leavingThePageMasksEverythingAgain() = runComposeUiTest {
        var shown by mutableStateOf(true)
        val actions = RecordingActions()
        setContent {
            MaterialTheme {
                if (shown) Box(Modifier.width(WIDE).height(PAGE_HEIGHT)) { AgentVaultPage(state = loaded, actions = actions) }
            }
        }
        shown = false
        waitForIdle()
        assertEquals(listOf("hideAll"), actions.calls)
    }

    private class RecordingActions(private val onReveal: (String) -> Unit = {}) : AgentVaultActions {
        val calls = mutableListOf<String>()

        override fun refresh() { calls += "refresh" }
        override fun toggleReveal(key: SecretKey) { calls += "reveal:${key.name}"; onReveal(key.name) }
        override fun hideAll() { calls += "hideAll" }
        override fun startAdding() { calls += "add" }
        override fun startEditing(key: SecretKey) { calls += "edit:${key.name}" }
        override fun updateDraftKey(key: String) { calls += "key" }
        override fun updateDraftValue(value: String) { calls += "value" }
        override fun toggleDraftValueVisible() { calls += "showValue" }
        override fun cancelDraft() { calls += "cancel" }
        override fun saveDraft() { calls += "save" }
        override fun requestDelete(key: SecretKey) { calls += "delete:${key.name}" }
        override fun confirmDelete() { calls += "confirmDelete" }
        override fun cancelDelete() { calls += "cancelDelete" }
        override fun dismissError() { calls += "dismissError" }
    }

    private companion object {
        val WIDE = 1100.dp
        val COMPACT = 380.dp
        val PAGE_HEIGHT = 800.dp
    }
}
