@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.skills

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.tools.ToolLibraryState
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaPaneDefaults
import com.letta.mobile.ui.host.LocalLettaHost
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared Skills page with its Tools section, on desktop and a phone (letta-mobile-zxk2n.32). */
class SkillsPageTest {
    private val github = Skill(name = "github", description = "Work with GitHub issues and PRs")
    private val notion = Skill(name = "notion", description = "Read and write Notion pages")
    private val gitStatus = Tool(id = ToolId("tool-git"), name = "git_status", description = "Show the working tree status", toolType = "custom")
    private val sendEmail = Tool(id = ToolId("tool-mail"), name = "send_email", description = "Send an email")

    private fun state(skills: List<Skill> = listOf(github, notion), installed: Set<String> = setOf("notion")) = SkillsPageState(
        skills = skills,
        installedSkillNames = installed,
        canManageSkills = true,
        tools = ToolLibraryState(tools = listOf(gitStatus, sendEmail), isLoading = false, hasMorePages = false),
    )

    private fun ComposeUiTest.show(
        state: SkillsPageState,
        actions: SkillsPageActions = SkillsPageActions(),
        width: Dp = WIDE,
        options: SkillsPageOptions = SkillsPageOptions(),
        pointer: Boolean = false,
    ) {
        setContent {
            MaterialTheme {
                // Pointer containers (dialogs) when the test opens a pane: the test draws no window insets.
                val host = if (pointer) LettaHost.Pointer.copy(sheet = LettaPaneDefaults.Dialog) else LocalLettaHost.current
                CompositionLocalProvider(LocalLettaHost provides host) {
                    Box(Modifier.width(width).height(PAGE_HEIGHT)) {
                        SkillsPage(state = state, actions = actions, options = options)
                    }
                }
            }
        }
    }

    @Test
    fun skillsAndToolsBothRender() = runComposeUiTest {
        show(state())
        onNodeWithTag(SkillsPageTags.section(SkillsPageSection.Skills)).assertExists()
        onNodeWithTag(SkillsPageTags.section(SkillsPageSection.Tools)).assertExists()
        onNodeWithTag(SkillsPageTags.skill("github")).assertExists()
        onNodeWithTag(SkillsPageTags.skill("notion")).assertExists()
        onNodeWithTag(SkillsPageTags.tool("tool-git")).assertExists()
        onNodeWithTag(SkillsPageTags.tool("tool-mail")).assertExists()
    }

    @Test
    fun oneSearchNarrowsSkillsAndTools() = runComposeUiTest {
        show(state(), width = COMPACT)
        onNodeWithTag(SkillsPageTags.SEARCH).performTextInput("git")
        onNodeWithTag(SkillsPageTags.skill("github")).assertExists()
        onNodeWithTag(SkillsPageTags.skill("notion")).assertDoesNotExist()
        onNodeWithTag(SkillsPageTags.tool("tool-git")).assertExists()
        onNodeWithTag(SkillsPageTags.tool("tool-mail")).assertDoesNotExist()
    }

    @Test
    fun aSearchThatMatchesNothingSaysSoInBothSections() = runComposeUiTest {
        show(state())
        onNodeWithTag(SkillsPageTags.SEARCH).performTextInput("zzz")
        onNodeWithTag("skills-empty").assertExists()
        onNodeWithTag("tools-empty").assertExists()
    }

    @Test
    fun theAssignedFilterKeepsOnlyTheAgentsSkills() = runComposeUiTest {
        show(state())
        onNodeWithTag(SkillsPageTags.ASSIGNED_ONLY).performClick()
        onNodeWithTag(SkillsPageTags.skill("notion")).assertExists()
        onNodeWithTag(SkillsPageTags.skill("github")).assertDoesNotExist()
        onNodeWithTag(SkillsPageTags.tool("tool-git")).assertExists()
    }

    @Test
    fun addAndToggleReachTheActions() = runComposeUiTest {
        val calls = mutableListOf<String>()
        val actions = SkillsPageActions(
            onInstallSkill = { calls += "install:$it" },
            onUninstallSkill = { calls += "uninstall:$it" },
            onToolClick = { calls += "tool:${it.id.value}" },
            onAddTool = { calls += "add-tool" },
        )
        show(state(), actions, pointer = true)
        onNodeWithTag(SkillsPageTags.skillToggle("github")).performClick()
        onNodeWithTag(SkillsPageTags.skillToggle("notion")).performClick()
        onNodeWithTag(SkillsPageTags.ADD_TOOL).performClick()
        onNodeWithTag(SkillsPageTags.tool("tool-git")).performClick()
        assertEquals(listOf("install:github", "uninstall:notion", "add-tool", "tool:tool-git"), calls)
    }

    @Test
    fun aToolOpensItsReadOnlyDetail() = runComposeUiTest {
        val withSource = gitStatus.copy(sourceCode = "def git_status():\n    return run('git status')", tags = listOf("git"))
        show(state().copy(tools = ToolLibraryState(tools = listOf(withSource), isLoading = false, hasMorePages = false)), pointer = true)
        onNodeWithTag(SkillsPageTags.TOOL_DETAIL).assertDoesNotExist()
        onNodeWithTag(SkillsPageTags.tool("tool-git")).performClick()
        onNodeWithTag(SkillsPageTags.TOOL_DETAIL).assertExists()
        onNodeWithText("Source").assertExists()
        onNodeWithText("Tools are part of letta-code; they are read-only here.").assertExists()
    }

    @Test
    fun aToolWithoutSourceShowsItsSchema() {
        val schema = buildJsonObject { put("name", "git_status") }
        assertEquals("Schema", toolSource(gitStatus.copy(jsonSchema = schema))?.first)
        assertEquals(null, toolSource(gitStatus))
    }

    @Test
    fun noCreateButtonWhenTheHostCannotCreateTools() = runComposeUiTest {
        show(state())
        onNodeWithTag(SkillsPageTags.ADD_TOOL).assertDoesNotExist()
    }

    @Test
    fun aTappedSkillOpensItsDetail() = runComposeUiTest {
        show(state())
        onNodeWithTag(SkillsPageTags.skill("github")).performClick()
        onNodeWithTag(SkillsPageTags.DETAIL).assertExists()
        onNodeWithTag(SkillsPageTags.DETAIL_CLOSE).performClick()
        onNodeWithTag(SkillsPageTags.DETAIL).assertDoesNotExist()
    }

    @Test
    fun theToolsRouteOpensScrolledToTools() = runComposeUiTest {
        val many = (1..30).map { Skill(name = "skill-$it", description = "Skill number $it") }
        show(state(skills = many, installed = emptySet()), width = COMPACT, options = SkillsPageOptions(initialSection = SkillsPageSection.Tools))
        waitForIdle()
        onNodeWithTag(SkillsPageTags.section(SkillsPageSection.Tools)).assertIsDisplayed()
        onNodeWithTag(SkillsPageTags.section(SkillsPageSection.Skills)).assertDoesNotExist()
    }

    @Test
    fun rowsKeepSectionKeysApart() {
        val rows = SkillsPageRows.build(state(), SkillsPageQuery(), columns = 2)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertEquals(0, SkillsPageRows.indexOf(rows, SkillsPageSection.Skills))
        assertEquals(true, SkillsPageRows.indexOf(rows, SkillsPageSection.Tools) > 0)
    }

    @Test
    fun pagingStaysOfferedWhileSearching() {
        val paged = state().copy(tools = state().tools.copy(hasMorePages = true))
        val rows = SkillsPageRows.build(paged, SkillsPageQuery(query = "nothing loaded matches"), columns = 1)
        assertEquals(true, rows.any { it is SkillsPageRow.LoadMoreTools })
    }

    private companion object {
        val WIDE = 1000.dp
        val COMPACT = 400.dp
        val PAGE_HEIGHT = 900.dp
    }
}
