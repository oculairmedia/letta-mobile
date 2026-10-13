@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.agenteditor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.agenteditor.AgentCreated
import com.letta.mobile.data.agenteditor.AgentEditorBackend
import com.letta.mobile.data.agenteditor.AgentEditorController
import com.letta.mobile.data.agenteditor.AgentEditorLinks
import com.letta.mobile.data.agenteditor.AgentEditorMode
import com.letta.mobile.data.agenteditor.AgentEditorSectionKeys
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaPaneTags
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.settings.SettingsSectionTags
import com.letta.mobile.ui.shell.header.ShellHeaderTags
import com.letta.mobile.ui.theme.SharedMaterialTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** letta-mobile-ckmub.3.24: the shared agent editor page over the real controller and a recording backend. */
class AgentEditorPageTest {
    private val search = Tool(id = ToolId("t-search"), name = "web_search")
    private val linear = Tool(id = ToolId("t-linear"), name = "linear_issues", toolType = "external_mcp")
    private val ada = Agent(id = AgentId("a-1"), name = "Ada", model = "openai/gpt-5", system = "You are Ada.", tools = persistentListOf(search))

    private class RecordingBackend(agent: Agent, toolList: List<Tool>) : AgentEditorBackend {
        override val agents = MutableStateFlow(listOf(agent))
        override val models = MutableStateFlow(listOf(LlmModel(id = "m", handle = "openai/gpt-5", displayNameOverride = "GPT-5")))
        override val tools = MutableStateFlow(toolList)
        private val byId = mapOf(agent.id.value to agent)
        val updates = mutableListOf<AgentUpdateParams>()
        val created = mutableListOf<AgentCreateParams>()
        val attached = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        override suspend fun load(agentId: String): Agent = byId.getValue(agentId)
        override suspend fun refreshCatalogs() = Unit
        override suspend fun create(params: AgentCreateParams): AgentCreated {
            created += params
            return AgentCreated("a-new")
        }
        override suspend fun update(agentId: String, params: AgentUpdateParams) { updates += params }
        override suspend fun attachTool(agentId: String, toolId: String) { attached += toolId }
        override suspend fun detachTool(agentId: String, toolId: String) = Unit
        override suspend fun delete(agentId: String) { deleted += agentId }
    }

    private class Outcome {
        var saved: AgentCreated? = null
        var deleted = false
        var openedMemory = false
    }

    private fun ComposeUiTest.editor(backend: RecordingBackend, mode: AgentEditorMode, outcome: Outcome = Outcome()) {
        setContent {
            ProvideLettaHost(LettaHost.Pointer) {
                SharedMaterialTheme {
                    Box(Modifier.size(720.dp, 1400.dp)) { Editor(backend, mode, outcome) }
                }
            }
        }
        waitForIdle()
    }

    @Composable
    private fun Editor(backend: RecordingBackend, mode: AgentEditorMode, outcome: Outcome) {
        val scope = rememberCoroutineScope()
        val controller = remember { AgentEditorController(backend, scope, mode) }
        val state by controller.state.collectAsState()
        AgentEditorPage(
            state = state,
            actions = controller,
            options = AgentEditorPageOptions(
                onSaved = { outcome.saved = it },
                onDeleted = { outcome.deleted = true },
                links = AgentEditorLinks(memory = { outcome.openedMemory = true }),
            ),
        )
    }

    private fun ComposeUiTest.section(key: String) = onNodeWithTag(SettingsSectionTags.SECTION_PREFIX + key)
    private fun ComposeUiTest.row(key: String) = onNodeWithTag(SettingsSectionTags.ROW_PREFIX + key, useUnmergedTree = true)

    @Test
    fun anAgentsEditorDrawsEverySectionAndNothingIsSavedUntilItChanges() = runComposeUiTest {
        editor(RecordingBackend(ada, listOf(search, linear)), AgentEditorMode.Edit("a-1"))

        onNodeWithTag(ShellHeaderTags.TITLE).assertTextContains("Edit agent")
        listOf(
            AgentEditorSectionKeys.IDENTITY,
            AgentEditorSectionKeys.MODEL,
            AgentEditorSectionKeys.PROMPT,
            AgentEditorSectionKeys.TOOLS,
            AgentEditorSectionKeys.MORE,
            AgentEditorSectionKeys.DANGER,
        ).forEach { section(it).assertExists() }
        section(AgentEditorSectionKeys.STARTER).assertDoesNotExist()
        row("name").assertTextContains("Ada")
        row("tool-t-search").assertExists()
        onNodeWithTag(AgentEditorPageTags.SAVE).assertIsNotEnabled()
    }

    @Test
    fun aBlankNameDisablesSaveAndAValidEditSavesOnlyItself() = runComposeUiTest {
        val backend = RecordingBackend(ada, listOf(search))
        val outcome = Outcome()
        editor(backend, AgentEditorMode.Edit("a-1"), outcome)

        row("name").performTextClearance()
        onNodeWithTag(AgentEditorPageTags.SAVE).assertIsNotEnabled()
        row("name").performTextInput("Ada Lovelace")
        row("context-limit").performScrollTo().performTextInput("lots")
        onNodeWithText("Enter the context limit as a whole number of tokens.").assertExists()
        onNodeWithTag(AgentEditorPageTags.SAVE).assertIsNotEnabled()
        row("context-limit").performTextClearance()
        onNodeWithTag(AgentEditorPageTags.SAVE).assertIsEnabled().performClick()
        waitForIdle()

        assertEquals(AgentCreated("a-1"), outcome.saved)
        assertEquals("Ada Lovelace", backend.updates.single().name)
        assertEquals(null, backend.updates.single().system)
    }

    @Test
    fun theNewAgentFlowStartsBlankAndCreatesOnceNamed() = runComposeUiTest {
        val backend = RecordingBackend(ada, listOf(search))
        val outcome = Outcome()
        editor(backend, AgentEditorMode.Create, outcome)

        onNodeWithTag(ShellHeaderTags.TITLE).assertTextContains("New agent")
        section(AgentEditorSectionKeys.STARTER).assertExists()
        section(AgentEditorSectionKeys.DANGER).assertDoesNotExist()
        section(AgentEditorSectionKeys.MORE).assertDoesNotExist()
        onNodeWithTag(AgentEditorPageTags.SAVE).assertTextContains("Create").assertIsNotEnabled()

        row("name").performTextInput("Grace")
        onNodeWithTag(AgentEditorPageTags.SAVE).assertIsEnabled().performClick()
        waitForIdle()

        assertEquals(AgentCreated("a-new"), outcome.saved)
        assertEquals("Grace", backend.created.single().name)
    }

    @Test
    fun deletingAsksFirstThenDeletes() = runComposeUiTest {
        val backend = RecordingBackend(ada, listOf(search))
        val outcome = Outcome()
        editor(backend, AgentEditorMode.Edit("a-1"), outcome)

        row("delete").performScrollTo().performClick()
        onNodeWithTag(LettaPaneTags.CONFIRM).assertExists()
        assertTrue(backend.deleted.isEmpty())
        onNodeWithText("Delete", substring = false).performClick()
        waitForIdle()

        assertEquals(listOf("a-1"), backend.deleted)
        assertTrue(outcome.deleted)
    }

    @Test
    fun theToolPickerAttachesAToolOnSave() = runComposeUiTest {
        val backend = RecordingBackend(ada, listOf(search, linear))
        editor(backend, AgentEditorMode.Edit("a-1"))

        row("manage-tools").performScrollTo().performClick()
        onNodeWithTag(AgentEditorPageTags.TOOLS_PANE).assertExists()
        onNodeWithText("MCP").assertExists()
        onNodeWithText("linear_issues").performClick()
        onNodeWithText("Done").performClick()
        onNodeWithTag(AgentEditorPageTags.SAVE).assertIsEnabled().performClick()
        waitForIdle()

        assertEquals(listOf("t-linear"), backend.attached)
    }

    @Test
    fun aLinkOpensTheAgentsOtherPage() = runComposeUiTest {
        val outcome = Outcome()
        editor(RecordingBackend(ada, listOf(search)), AgentEditorMode.Edit("a-1"), outcome)

        row("memory").performScrollTo().performClick()

        assertTrue(outcome.openedMemory)
    }
}
