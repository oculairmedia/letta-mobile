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
import androidx.compose.ui.hapticfeedback.HapticFeedback
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
import com.letta.mobile.data.model.Agent
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
    val isShareMode = shareContentPreview != null
    var shareNavigationConsumed by rememberSaveable(shareContentPreview) { mutableStateOf(false) }

    LaunchedEffect(navigation.openCreateOnStart, isShareMode) {
        if (navigation.openCreateOnStart && !isShareMode) {
            viewModel.showCreateDialog()
        }
    }

    val selectAgent: (String, String?) -> Unit = { agentId, agentName ->
        if (!(isShareMode && shareNavigationConsumed)) {
            if (isShareMode) shareNavigationConsumed = true
            onNavigateToAgent(agentId, agentName, null)
        }
    }
    val onImportLaunch = rememberAgentImportLauncher(
        viewModel = viewModel,
        uiState = uiState,
        onNavigateToAgent = onNavigateToAgent,
    )
    val display = rememberAgentListScreenDisplay(viewModel = viewModel, uiState = uiState)

    AgentListScreenBody(
        uiState = uiState,
        viewModel = viewModel,
        isShareMode = isShareMode,
        shareContentPreview = shareContentPreview,
        display = display,
        onNavigateBack = onNavigateBack,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToAgent = onNavigateToAgent,
        onNavigateToEditAgent = onNavigateToEditAgent,
        onSelectAgent = selectAgent,
        onImportLaunch = onImportLaunch,
    )
}

@Composable
private fun rememberAgentImportLauncher(
    viewModel: AgentListViewModel,
    uiState: AgentListUiState,
    onNavigateToAgent: (String, String?, String?) -> Unit,
): () -> Unit {
    val snackbar = LocalSnackbarDispatcher.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberFilePickerLauncher(
        type = FileKitType.File(extensions = listOf("json", "txt")),
        mode = FileKitMode.Single,
    ) { file: PlatformFile? ->
        if (file == null) return@rememberFilePickerLauncher
        scope.launch {
            importSelectedAgentFile(
                file = file,
                viewModel = viewModel,
                uiState = uiState,
                onReadFailed = {
                    snackbar.dispatch(context.getString(R.string.screen_agents_import_read_failed))
                },
                onImported = { importedCount, importedId ->
                    snackbar.dispatch(
                        context.resources.getQuantityString(
                            R.plurals.screen_agents_import_success,
                            importedCount,
                            importedCount,
                        ),
                    )
                    importedId?.let { onNavigateToAgent(it, uiState.pendingImportName, null) }
                },
            )
        }
    }
    return remember(launcher) { { launcher.launch() } }
}

private suspend fun importSelectedAgentFile(
    file: PlatformFile,
    viewModel: AgentListViewModel,
    uiState: AgentListUiState,
    onReadFailed: () -> Unit,
    onImported: (importedCount: Int, importedId: String?) -> Unit,
) {
    val bytes = runCatching { file.readBytes() }.getOrNull()
    if (bytes == null) {
        onReadFailed()
        return
    }
    viewModel.importAgent(
        fileName = file.name,
        fileBytes = bytes,
        overrideName = uiState.pendingImportName,
        overrideExistingTools = uiState.pendingImportOverrideTools,
        stripMessages = uiState.pendingImportStripMessages,
    ) { response ->
        onImported(response.agentIds.size, response.agentIds.firstOrNull())
    }
}

private data class AgentListScreenDisplay(
    val filteredAgents: List<Agent>,
    val displayAgents: AgentListDisplayAgents,
)

@Composable
private fun rememberAgentListScreenDisplay(
    viewModel: AgentListViewModel,
    uiState: AgentListUiState,
): AgentListScreenDisplay {
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
    return AgentListScreenDisplay(
        filteredAgents = filteredAgents,
        displayAgents = displayAgents,
    )
}

private data class AgentListScreenBindings(
    val topBarActions: AgentListTopBarActions,
    val contentState: AgentListContentState,
    val contentActions: AgentListContentActions,
)

@Composable
private fun rememberAgentListScreenBindings(
    uiState: AgentListUiState,
    viewModel: AgentListViewModel,
    isShareMode: Boolean,
    display: AgentListScreenDisplay,
    onNavigateBack: () -> Unit,
    onNavigateToEditAgent: (String) -> Unit,
    onSelectAgent: (String, String?) -> Unit,
): AgentListScreenBindings {
    val topBarActions = remember(onNavigateBack, viewModel) {
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
    val contentState = remember(uiState, isShareMode, display) {
        AgentListContentState(
            uiState = uiState,
            isShareMode = isShareMode,
            filteredAgents = display.filteredAgents,
            visibleFavoriteAgent = display.displayAgents.visibleFavoriteAgent,
            gridAgents = display.displayAgents.listAgents,
        )
    }
    val contentActions = AgentListContentActions(
        onSelectAgent = onSelectAgent,
        onNavigateToEditAgent = onNavigateToEditAgent,
        onDeleteAgent = viewModel::deleteAgent,
        onToggleFavorite = viewModel::toggleFavorite,
        onTogglePinned = viewModel::togglePinned,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::loadAgents,
        onCreateAgent = viewModel::showCreateDialog,
    )
    return AgentListScreenBindings(
        topBarActions = topBarActions,
        contentState = contentState,
        contentActions = contentActions,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentListScreenBody(
    uiState: AgentListUiState,
    viewModel: AgentListViewModel,
    isShareMode: Boolean,
    shareContentPreview: String?,
    display: AgentListScreenDisplay,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAgent: (String, String?, String?) -> Unit,
    onNavigateToEditAgent: (String) -> Unit,
    onSelectAgent: (String, String?) -> Unit,
    onImportLaunch: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val bindings = rememberAgentListScreenBindings(
        uiState = uiState,
        viewModel = viewModel,
        isShareMode = isShareMode,
        display = display,
        onNavigateBack = onNavigateBack,
        onNavigateToEditAgent = onNavigateToEditAgent,
        onSelectAgent = onSelectAgent,
    )

    AgentListScaffold(
        uiState = uiState,
        viewModel = viewModel,
        isShareMode = isShareMode,
        shareContentPreview = shareContentPreview,
        haptic = haptic,
        bindings = bindings,
    )

    AgentListDialogHost(
        uiState = uiState,
        viewModel = viewModel,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToAgent = onNavigateToAgent,
        onImport = { _, _, _ -> onImportLaunch() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentListScaffold(
    uiState: AgentListUiState,
    viewModel: AgentListViewModel,
    isShareMode: Boolean,
    shareContentPreview: String?,
    haptic: HapticFeedback,
    bindings: AgentListScreenBindings,
) {
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state = topAppBarState)

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
        topBar = {
            AgentListTopBar(
                state = AgentListTopBarState(
                    uiState = uiState,
                    isShareMode = isShareMode,
                    shareContentPreview = shareContentPreview,
                    scrollBehavior = scrollBehavior,
                ),
                actions = bindings.topBarActions,
                haptic = haptic,
            )
        },
        floatingActionButton = {
            if (!isShareMode) {
                FloatingActionButton(onClick = { viewModel.showCreateDialog() }) {
                    Icon(LettaIcons.Add, "Create Agent")
                }
            }
        },
    ) { paddingValues ->
        AgentListContent(
            state = bindings.contentState,
            actions = bindings.contentActions,
            layout = AgentListContentLayout(
                paddingValues = paddingValues,
                listState = listState,
                gridState = gridState,
                haptic = haptic,
            ),
        )
    }
}
