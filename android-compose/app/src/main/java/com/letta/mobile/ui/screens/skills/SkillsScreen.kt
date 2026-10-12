package com.letta.mobile.ui.screens.skills

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
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
import com.letta.mobile.ui.theme.LettaTopBarDefaults

/**
 * Android host for the shared [SkillsPage] (letta-mobile-zxk2n.32): top bar, back and the create
 * tool dialog only. The Skills route opens it on Skills; the standalone Tools route on Tools.
 * Scaffold's padding carries the system bars, so the page sits inside them edge to edge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToToolDetail: (String) -> Unit = {},
    initialSection: SkillsPageSection = SkillsPageSection.Skills,
    skillsViewModel: SkillsViewModel = hiltViewModel(),
    toolsViewModel: AllToolsViewModel = hiltViewModel(),
) {
    val catalog by skillsViewModel.state.collectAsStateWithLifecycle()
    val tools by toolsViewModel.toolState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    val actions = remember(skillsViewModel, toolsViewModel, onNavigateToToolDetail) {
        SkillsPageActions(
            onRefreshSkills = skillsViewModel::refresh,
            onInstallSkill = skillsViewModel::install,
            onUninstallSkill = skillsViewModel::uninstall,
            onRefreshTools = toolsViewModel::loadTools,
            onToolTagToggled = toolsViewModel::toggleTag,
            onClearToolTags = toolsViewModel::clearTags,
            onLoadMoreTools = toolsViewModel::loadMoreTools,
            onToolClick = { tool -> onNavigateToToolDetail(tool.id.value) },
            onAddTool = { showCreateDialog = true },
        )
    }
    Scaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_skills_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
            modifier = Modifier.padding(paddingValues).consumeWindowInsets(paddingValues),
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

internal fun skillsPageState(catalog: SkillsCatalogState, tools: ToolLibraryState): SkillsPageState = SkillsPageState(
    skills = catalog.skills,
    installedSkillNames = catalog.installedSkillNames,
    skillsLoading = catalog.isLoading,
    skillsError = catalog.errorMessage,
    canManageSkills = catalog.canManage,
    tools = tools,
)
