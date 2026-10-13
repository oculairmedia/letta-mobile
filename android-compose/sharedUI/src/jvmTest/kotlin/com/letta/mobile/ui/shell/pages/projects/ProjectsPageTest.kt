@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.projects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.BeadsRemoteStatus
import com.letta.mobile.data.model.ProjectSummary
import com.letta.mobile.data.projects.ProjectDraft
import com.letta.mobile.data.projects.ProjectEditor
import com.letta.mobile.data.projects.ProjectsPageState
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaPaneDefaults
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.settings.SettingsSectionTags
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-ckmub.2.5 (S17): the shared Projects page - loading, empty, error, list, detail, form. */
class ProjectsPageTest {
    private val alpha = ProjectSummary(identifier = "alpha", name = "Alpha", filesystemPath = "/src/alpha", lettaAgentId = AgentId("agent-a"))
    private val beta = ProjectSummary(identifier = "beta", name = "Beta", filesystemPath = "/src/beta")

    private fun ComposeUiTest.show(state: ProjectsPageState, actions: ProjectsPageActions = ProjectsPageActions()) {
        val host = LettaHost.Pointer.copy(sheet = LettaPaneDefaults.Dialog)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLettaHost provides host) {
                    Box(Modifier.width(900.dp).height(900.dp)) { ProjectsPage(state, actions) }
                }
            }
        }
    }

    private fun loaded(vararg projects: ProjectSummary) = ProjectsPageState(projects = projects.toList(), loading = false)

    @Test
    fun aFirstLoadShowsProgressAndAnEmptyHostSaysSo() = runComposeUiTest {
        show(ProjectsPageState(loading = true))
        onNodeWithTag(ProjectsPageTags.LIST.loading).assertExists()
    }

    @Test
    fun noProjectsSaysSo() = runComposeUiTest {
        show(loaded())
        onNodeWithText("No projects yet").assertExists()
    }

    @Test
    fun aCatalogThatCannotLoadOffersRetry() = runComposeUiTest {
        val calls = mutableListOf<String>()
        show(ProjectsPageState(loading = false, error = "no relay"), ProjectsPageActions(list = ProjectsListActions(onRefresh = { calls += "refresh" })))
        onNodeWithTag(ProjectsPageTags.ERROR).assertExists()
        onNodeWithTag(ProjectsPageTags.RETRY).performClick()
        assertEquals(listOf("refresh"), calls)
    }

    @Test
    fun pinnedProjectsComeFirstUnderTheirHeader() = runComposeUiTest {
        val calls = mutableListOf<String>()
        show(
            loaded(alpha, beta).copy(pinnedIds = setOf("beta")),
            ProjectsPageActions(list = ProjectsListActions(onSelect = { calls += "select:$it" }, onQueryChange = { calls += "query:$it" }, onStartCreate = { calls += "create" })),
        )
        onNodeWithTag(ProjectsPageTags.LIST.header("projects-pinned")).assertExists()
        onNodeWithTag(ProjectsPageTags.LIST.row("projects-pinned:beta").value).performClick()
        onNodeWithTag(ProjectsPageTags.SEARCH).performTextInput("a")
        onNodeWithTag(ProjectsPageTags.CREATE).performClick()
        assertEquals(listOf("select:beta", "query:a"), calls.take(2))
        assertEquals("create", calls.last())
    }

    @Test
    fun theDetailOffersChatSyncAndAConfirmedDelete() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val actions = ProjectsPageActions(
            list = ProjectsListActions(onOpenChat = { project, intent -> calls += "${project.identifier}:$intent" }),
            detail = ProjectDetailActions(onSync = { calls += "sync:$it" }, onDelete = { calls += "delete:$it" }),
        )
        show(loaded(alpha).copy(selectedId = "alpha", beadsStatus = mapOf("alpha" to BeadsRemoteStatus(status = "provisioned"))), actions)
        onNodeWithTag(ProjectsPageTags.DETAIL).assertExists()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + ProjectDetailKeys.PROVISION).assertDoesNotExist()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + ProjectDetailKeys.BUG_REPORT).performScrollTo().performClick()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + ProjectDetailKeys.SYNC).performScrollTo().performClick()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + ProjectDetailKeys.DELETE).performScrollTo().performClick()
        assertEquals(listOf("alpha:BugReport", "sync:alpha"), calls, "delete asks first")
        onNodeWithText("Delete project?").assertExists()
    }

    @Test
    fun aProjectWithoutAnAgentCannotOpenAChat() = runComposeUiTest {
        show(loaded(beta).copy(selectedId = "beta"), ProjectsPageActions(list = ProjectsListActions(onOpenChat = { _, _ -> })))
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + ProjectDetailKeys.OPEN_CHAT).assertIsNotEnabled()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + ProjectDetailKeys.BUG_REPORT).assertDoesNotExist()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + ProjectDetailKeys.PROVISION).assertExists()
    }

    @Test
    fun theNewProjectFormWaitsForAValidPath() = runComposeUiTest {
        val drafts = mutableListOf<ProjectDraft>()
        val editor = ProjectEditor(draft = ProjectDraft(name = "Delta", filesystemPath = "relative"))
        show(loaded(alpha).copy(editor = editor), ProjectsPageActions(editor = ProjectEditorActions(onDraftChange = { drafts += it })))
        onNodeWithTag(ProjectsPageTags.EDITOR).assertExists()
        onNodeWithTag(SettingsSectionTags.ACTION_PREFIX + ProjectDetailKeys.SAVE).assertIsNotEnabled()
        onNodeWithText("Use an absolute path (starting with /).").assertExists()
        onNodeWithTag(SettingsSectionTags.ROW_PREFIX + ProjectDetailKeys.FIELD_GIT).performTextInput("g")
        assertEquals("g", drafts.last().gitUrl)
    }

    @Test
    fun factsAndActionsFollowTheHost() {
        val state = loaded(alpha).copy(syncingId = "alpha")
        val facts = projectFacts(alpha, state).map { it.key }
        assertEquals(listOf("project-id", "project-path"), facts)
        val sync = projectActions(alpha, state, ProjectsPageActions()).first { it.key == ProjectDetailKeys.SYNC }
        assertEquals(false, sync.enabled)
    }
}
