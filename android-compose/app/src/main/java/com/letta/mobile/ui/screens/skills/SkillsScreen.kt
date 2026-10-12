package com.letta.mobile.ui.screens.skills

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.R
import com.letta.mobile.data.skills.SkillsCatalogState
import com.letta.mobile.data.tools.ToolLibraryState
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.screens.tools.AllToolsViewModel
import com.letta.mobile.ui.screens.tools.CreateToolDialog
import com.letta.mobile.ui.shell.pages.skills.SkillsPage
import com.letta.mobile.ui.shell.pages.skills.SkillsPageActions
import com.letta.mobile.ui.shell.pages.skills.SkillsPageOptions
import com.letta.mobile.ui.shell.pages.skills.SkillsPageSection
import com.letta.mobile.ui.shell.pages.skills.SkillsPageState
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.theme.LettaTopBarDefaults

/**
 * Android host for the shared [SkillsPage] (letta-mobile-zxk2n.32): top bar, back and the create
 * tool dialog only. The Skills route opens it on Skills; the standalone Tools route on Tools.
 * The shared page frame owns the system bars and keyboard, so the page sits inside them edge to edge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    navigation: SkillsScreenNavigation,
    initialSection: SkillsPageSection = SkillsPageSection.Skills,
    skillsViewModel: SkillsViewModel = hiltViewModel(),
    toolsViewModel: AllToolsViewModel = hiltViewModel(),
) {
    val catalog by skillsViewModel.state.collectAsStateWithLifecycle()
    val tools by toolsViewModel.toolState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    val actions = remember(skillsViewModel, toolsViewModel, navigation) {
        skillsPageActions(skillsViewModel, toolsViewModel, navigation) { showCreateDialog = true }
    }
    LettaPageScaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        header = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_skills_title)) },
                navigationIcon = {
                    IconButton(onClick = navigation.onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = LettaTopBarDefaults.topAppBarColors(),
            )
        },
    ) { paddingValues ->
        SkillsPage(
            state = skillsPageState(catalog, tools),
            actions = actions,
            options = SkillsPageOptions(showTitle = false, touch = true, initialSection = initialSection),
            modifier = Modifier.padding(paddingValues),
        )
    }
    if (showCreateDialog) {
        CreateToolDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { sourceCode ->
                toolsViewModel.createTool(sourceCode)
                showCreateDialog = false
            },
        )
    }
}

/** Where the Skills screen leaves to: back, and a tool's detail page. */
data class SkillsScreenNavigation(
    val onNavigateBack: () -> Unit,
    val onNavigateToToolDetail: (toolId: String) -> Unit = {},
)

private fun skillsPageActions(
    skills: SkillsViewModel,
    tools: AllToolsViewModel,
    navigation: SkillsScreenNavigation,
    onAddTool: () -> Unit,
): SkillsPageActions = SkillsPageActions(
    onRefreshSkills = skills::refresh,
    onInstallSkill = skills::install,
    onUninstallSkill = skills::uninstall,
    onRefreshTools = tools::loadTools,
    onToolTagToggled = tools::toggleTag,
    onClearToolTags = tools::clearTags,
    onLoadMoreTools = tools::loadMoreTools,
    onToolClick = { tool -> navigation.onNavigateToToolDetail(tool.id.value) },
    onAddTool = onAddTool,
)

internal fun skillsPageState(catalog: SkillsCatalogState, tools: ToolLibraryState): SkillsPageState = SkillsPageState(
    skills = catalog.skills,
    installedSkillNames = catalog.installedSkillNames,
    skillsLoading = catalog.isLoading,
    skillsError = catalog.errorMessage,
    canManageSkills = catalog.canManage,
    tools = tools,
)
