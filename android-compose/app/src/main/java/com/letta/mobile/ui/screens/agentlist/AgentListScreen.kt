package com.letta.mobile.ui.screens.agentlist

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import kotlinx.coroutines.launch

/**
 * Public entry-point parameters bundled for the [AgentListScreen] composable.
 *
 * Bundling reduces the argument surface at every call-site and keeps CodeScene
 * "Missing Arguments Abstractions" advisories quiet — navigation callbacks and
 * one-shot boot flags travel together as one navigation contract.
 */
data class AgentListScreenNavigation(
    val onNavigateBack: () -> Unit,
    val onNavigateToAgent: (String, String?, String?) -> Unit,
    val onNavigateToSettings: () -> Unit = {},
    val onNavigateToEditAgent: (String) -> Unit,
    val shareContentPreview: String? = null,
    val openCreateOnStart: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentListScreen(
    navigation: AgentListScreenNavigation,
    viewModel: AgentListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isShareMode = navigation.shareContentPreview != null
    var shareNavigationConsumed by rememberSaveable(navigation.shareContentPreview) {
        mutableStateOf(false)
    }

    LaunchedEffect(navigation.openCreateOnStart, isShareMode) {
        if (navigation.openCreateOnStart && !isShareMode) {
            viewModel.showCreateDialog()
        }
    }

    fun selectAgent(agentId: String, agentName: String?) {
        if (isShareMode) {
            if (shareNavigationConsumed) return
            shareNavigationConsumed = true
        }
        navigation.onNavigateToAgent(agentId, agentName, null)
    }

    val importLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("json", "txt")),
        mode = FileKitMode.Single,
    ) { file: PlatformFile? ->
        if (file == null) return@rememberFilePickerLauncher
        scope.launch {
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null) {
                snackbar.dispatch(context.getString(R.string.screen_agents_import_read_failed))
            } else {
                val fileName = file.name
                viewModel.importAgent(
                    fileName = fileName,
                    fileBytes = bytes,
                    overrideName = uiState.pendingImportName,
                    overrideExistingTools = uiState.pendingImportOverrideTools,
                    stripMessages = uiState.pendingImportStripMessages,
                ) { response ->
                    val importedId = response.agentIds.firstOrNull()
                    snackbar.dispatch(
                        context.resources.getQuantityString(
                            R.plurals.screen_agents_import_success,
                            response.agentIds.size,
                            response.agentIds.size,
                        ),
                    )
                    importedId?.let {
                        navigation.onNavigateToAgent(it, uiState.pendingImportName, null)
                    }
                }
            }
        }
    }

    val filteredAgents = remember(uiState.agents, uiState.searchQuery, uiState.selectedTags) {
        viewModel.getFilteredAgents()
    }
    val favoriteAgent = uiState.favoriteAgentId?.let { favId ->
        uiState.agents.find { it.id == favId }
    }
    val displayAgents = remember(filteredAgents, favoriteAgent, uiState.pinnedAgentIds) {
        resolveAgentListDisplayAgents(
            filteredAgents = filteredAgents,
            favoriteAgent = favoriteAgent,
            pinnedAgentIds = uiState.pinnedAgentIds,
        )
    }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state = topAppBarState)

    val topBarActions = remember(navigation.onNavigateBack, viewModel) {
        AgentListTopBarActions(
            onNavigateBack = navigation.onNavigateBack,
            onShowImportDialog = viewModel::showImportDialog,
            onUpdateSearchQuery = viewModel::updateSearchQuery,
            onSetSearchExpanded = viewModel::setSearchExpanded,
            onSetShowGrid = viewModel::setShowGrid,
            onClearTags = viewModel::clearTags,
            onToggleTag = viewModel::toggleTag,
            getAllTags = viewModel::getAllTags,
        )
    }
    val contentState = remember(uiState, isShareMode, filteredAgents, displayAgents) {
        AgentListContentState(
            uiState = uiState,
            isShareMode = isShareMode,
            filteredAgents = filteredAgents,
            visibleFavoriteAgent = displayAgents.visibleFavoriteAgent,
            gridAgents = displayAgents.listAgents,
        )
    }
    val contentActions = AgentListContentActions(
        onSelectAgent = { agentId, agentName -> selectAgent(agentId, agentName) },
        onNavigateToEditAgent = navigation.onNavigateToEditAgent,
        onDeleteAgent = viewModel::deleteAgent,
        onToggleFavorite = viewModel::toggleFavorite,
        onTogglePinned = viewModel::togglePinned,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::loadAgents,
        onCreateAgent = viewModel::showCreateDialog,
    )

    val topBarParams = AgentListTopBarParams(
        uiState = uiState,
        isShareMode = isShareMode,
        shareContentPreview = navigation.shareContentPreview,
        scrollBehavior = scrollBehavior,
        actions = topBarActions,
        haptic = haptic,
    )
    val contentParams = AgentListContentParams(
        state = contentState,
        actions = contentActions,
        listState = listState,
        gridState = gridState,
        haptic = haptic,
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = { AgentListTopBar(params = topBarParams) },
        floatingActionButton = {
            if (!isShareMode) {
                FloatingActionButton(onClick = { viewModel.showCreateDialog() }) {
                    Icon(LettaIcons.Add, "Create Agent")
                }
            }
        },
    ) { paddingValues ->
        AgentListContent(params = contentParams, paddingValues = paddingValues)
    }

    AgentListDialogHost(
        params = AgentListDialogHostParams(
            uiState = uiState,
            viewModel = viewModel,
            onNavigateToSettings = navigation.onNavigateToSettings,
            onNavigateToAgent = navigation.onNavigateToAgent,
            onImport = { _, _, _ -> importLauncher.launch() },
        ),
    )
}
