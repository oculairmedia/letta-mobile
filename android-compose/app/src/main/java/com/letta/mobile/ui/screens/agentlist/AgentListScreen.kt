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
    val isShareMode = navigation.shareContentPreview != null
    var shareNavigationConsumed by rememberSaveable(navigation.shareContentPreview) {
        mutableStateOf(false)
    }

    LaunchedEffect(navigation.openCreateOnStart, isShareMode) {
        if (navigation.openCreateOnStart && !isShareMode) {
            viewModel.showCreateDialog()
        }
    }

    val selectAgent: (String, String?) -> Unit = { agentId, agentName ->
        if (!(isShareMode && shareNavigationConsumed)) {
            if (isShareMode) shareNavigationConsumed = true
            navigation.onNavigateToAgent(agentId, agentName, null)
        }
    }
    val onImportLaunch = rememberAgentImportLauncher(
        viewModel = viewModel,
        uiState = uiState,
        onNavigateToAgent = navigation.onNavigateToAgent,
    )
    val display = rememberAgentListScreenDisplay(viewModel = viewModel, uiState = uiState)

    AgentListScreenBody(
        params = AgentListScreenBodyParams(
            uiState = uiState,
            viewModel = viewModel,
            isShareMode = isShareMode,
            navigation = navigation,
            display = display,
            onSelectAgent = selectAgent,
            onImportLaunch = onImportLaunch,
        ),
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
                callbacks = AgentImportCallbacks(
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
                ),
            )
        }
    }
    return remember(launcher) { { launcher.launch() } }
}

/**
 * Callbacks routed to [importSelectedAgentFile]. Bundling them keeps that
 * suspend helper at three arguments so no function on this screen exceeds
 * the 4-argument CodeScene threshold.
 */
private data class AgentImportCallbacks(
    val onReadFailed: () -> Unit,
    val onImported: (importedCount: Int, importedId: String?) -> Unit,
)

private suspend fun importSelectedAgentFile(
    file: PlatformFile,
    viewModel: AgentListViewModel,
    uiState: AgentListUiState,
    callbacks: AgentImportCallbacks,
) {
    val bytes = runCatching { file.readBytes() }.getOrNull()
    if (bytes == null) {
        callbacks.onReadFailed()
        return
    }
    viewModel.importAgent(
        fileName = file.name,
        fileBytes = bytes,
        overrideName = uiState.pendingImportName,
        overrideExistingTools = uiState.pendingImportOverrideTools,
        stripMessages = uiState.pendingImportStripMessages,
    ) { response ->
        callbacks.onImported(response.agentIds.size, response.agentIds.firstOrNull())
    }
}

internal data class AgentListScreenDisplay(
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

internal data class AgentListScreenBodyParams(
    val uiState: AgentListUiState,
    val viewModel: AgentListViewModel,
    val isShareMode: Boolean,
    val navigation: AgentListScreenNavigation,
    val display: AgentListScreenDisplay,
    val onSelectAgent: (String, String?) -> Unit,
    val onImportLaunch: () -> Unit,
)

private data class AgentListScreenBindings(
    val topBarActions: AgentListTopBarActions,
    val contentState: AgentListContentState,
    val contentActions: AgentListContentActions,
)

@Composable
private fun rememberAgentListScreenBindings(
    params: AgentListScreenBodyParams,
): AgentListScreenBindings {
    val topBarActions = remember(params.navigation.onNavigateBack, params.viewModel) {
        AgentListTopBarActions(
            onNavigateBack = params.navigation.onNavigateBack,
            onShowImportDialog = params.viewModel::showImportDialog,
            onUpdateSearchQuery = params.viewModel::updateSearchQuery,
            onSetSearchExpanded = params.viewModel::setSearchExpanded,
            onSetShowGrid = params.viewModel::setShowGrid,
            onClearTags = params.viewModel::clearTags,
            onToggleTag = params.viewModel::toggleTag,
            getAllTags = params.viewModel::getAllTags,
        )
    }
    val contentState = remember(params.uiState, params.isShareMode, params.display) {
        AgentListContentState(
            uiState = params.uiState,
            isShareMode = params.isShareMode,
            filteredAgents = params.display.filteredAgents,
            visibleFavoriteAgent = params.display.displayAgents.visibleFavoriteAgent,
            gridAgents = params.display.displayAgents.listAgents,
        )
    }
    val contentActions = AgentListContentActions(
        onSelectAgent = params.onSelectAgent,
        onNavigateToEditAgent = params.navigation.onNavigateToEditAgent,
        onDeleteAgent = params.viewModel::deleteAgent,
        onToggleFavorite = params.viewModel::toggleFavorite,
        onTogglePinned = params.viewModel::togglePinned,
        onRefresh = params.viewModel::refresh,
        onRetry = params.viewModel::loadAgents,
        onCreateAgent = params.viewModel::showCreateDialog,
    )
    return AgentListScreenBindings(
        topBarActions = topBarActions,
        contentState = contentState,
        contentActions = contentActions,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentListScreenBody(params: AgentListScreenBodyParams) {
    val haptic = LocalHapticFeedback.current
    val bindings = rememberAgentListScreenBindings(params)

    AgentListScaffold(
        params = AgentListScaffoldParams(
            uiState = params.uiState,
            viewModel = params.viewModel,
            isShareMode = params.isShareMode,
            shareContentPreview = params.navigation.shareContentPreview,
            haptic = haptic,
            bindings = bindings,
        ),
    )

    AgentListDialogHost(
        params = AgentListDialogHostParams(
            uiState = params.uiState,
            viewModel = params.viewModel,
            onNavigateToSettings = params.navigation.onNavigateToSettings,
            onNavigateToAgent = params.navigation.onNavigateToAgent,
            onImport = { _, _, _ -> params.onImportLaunch() },
        ),
    )
}

private data class AgentListScaffoldParams(
    val uiState: AgentListUiState,
    val viewModel: AgentListViewModel,
    val isShareMode: Boolean,
    val shareContentPreview: String?,
    val haptic: HapticFeedback,
    val bindings: AgentListScreenBindings,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentListScaffold(params: AgentListScaffoldParams) {
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state = topAppBarState)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            AgentListTopBar(
                state = AgentListTopBarState(
                    uiState = params.uiState,
                    isShareMode = params.isShareMode,
                    shareContentPreview = params.shareContentPreview,
                    scrollBehavior = scrollBehavior,
                ),
                actions = params.bindings.topBarActions,
                haptic = params.haptic,
            )
        },
        floatingActionButton = {
            if (!params.isShareMode) {
                FloatingActionButton(onClick = { params.viewModel.showCreateDialog() }) {
                    Icon(LettaIcons.Add, "Create Agent")
                }
            }
        },
    ) { paddingValues ->
        AgentListContent(
            state = params.bindings.contentState,
            actions = params.bindings.contentActions,
            layout = AgentListContentLayout(
                paddingValues = paddingValues,
                listState = listState,
                gridState = gridState,
                haptic = params.haptic,
            ),
        )
    }
}
