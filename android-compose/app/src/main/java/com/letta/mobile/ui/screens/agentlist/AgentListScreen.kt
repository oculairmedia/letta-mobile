package com.letta.mobile.ui.screens.agentlist

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.oculair.meridian.R
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.shell.header.HeaderAction
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.pages.agents.AgentsPage
import com.letta.mobile.ui.shell.pages.agents.AgentsPageState
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch

/**
 * Public entry-point parameters bundled for the [AgentListScreen] composable: navigation callbacks
 * and one-shot boot flags travel together as one navigation contract.
 */
data class AgentListScreenNavigation(
    val onNavigateBack: () -> Unit,
    val onNavigateToAgent: (String, String?, String?) -> Unit,
    val onNavigateToSettings: () -> Unit = {},
    val onNavigateToEditAgent: (String) -> Unit,
    val shareContentPreview: String? = null,
    val openCreateOnStart: Boolean = false,
)

object AgentListScreenTags {
    const val CREATE = "agent-list-create"
}

/**
 * letta-mobile-ckmub.2.4 (hard cut S16): the agent list. A container only: the shared shell header
 * and the shared agents page (the shell's agent library) in [LettaPageScaffold], bound to
 * [AgentListViewModel]; the create and import dialogs stay Android's until the shared New agent flow.
 */
@Composable
fun AgentListScreen(
    navigation: AgentListScreenNavigation,
    viewModel: AgentListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isShareMode = navigation.shareContentPreview != null
    var shareNavigationConsumed by rememberSaveable(navigation.shareContentPreview) { mutableStateOf(false) }

    LaunchedEffect(navigation.openCreateOnStart, isShareMode) {
        if (navigation.openCreateOnStart && !isShareMode) viewModel.showCreateDialog()
    }
    val selectAgent: (String) -> Unit = { agentId ->
        if (!(isShareMode && shareNavigationConsumed)) {
            if (isShareMode) shareNavigationConsumed = true
            navigation.onNavigateToAgent(agentId, uiState.agents.firstOrNull { it.id.value == agentId }?.name, null)
        }
    }
    val onImportLaunch = rememberAgentImportLauncher(viewModel, uiState, navigation.onNavigateToAgent)
    val identities = LocalMascotRegistry.current.identities
    AgentListRouteContent(
        state = AgentsPageState(
            agents = uiState.agents.map { it.id.value to it.name },
            identities = identities.toMap(),
            pinnedAgentIds = uiState.pinnedAgentIds.mapTo(mutableSetOf()) { it.value },
            loading = uiState.isLoading,
            error = uiState.error,
            sharePreview = navigation.shareContentPreview,
        ),
        actions = ShellAgentRailActions(
            onAgentSelected = selectAgent,
            onAgentPinnedChange = { agentId, pinned ->
                if ((AgentId(agentId) in uiState.pinnedAgentIds) != pinned) viewModel.togglePinned(AgentId(agentId))
            },
            onAgentSettings = navigation.onNavigateToEditAgent,
        ),
        chrome = AgentListChrome(
            onBack = navigation.onNavigateBack,
            onImport = viewModel::showImportDialog.takeUnless { isShareMode },
            onCreate = viewModel::showCreateDialog.takeUnless { isShareMode },
            onRetry = viewModel::loadAgents,
        ),
    )
    AgentListDialogHost(
        params = AgentListDialogHostParams(
            uiState = uiState,
            viewModel = viewModel,
            onNavigateToSettings = navigation.onNavigateToSettings,
            onNavigateToAgent = navigation.onNavigateToAgent,
            onImport = { _, _, _ -> onImportLaunch() },
        ),
    )
}

/** The header's back and import, and the create button; a null action is not offered (share mode). */
internal data class AgentListChrome(
    val onBack: () -> Unit = {},
    val onImport: (() -> Unit)? = null,
    val onCreate: (() -> Unit)? = null,
    val onRetry: () -> Unit = {},
)

/** The route's layout, without a view model: the shared header and agents page in the page frame. */
@Composable
internal fun AgentListRouteContent(state: AgentsPageState, actions: ShellAgentRailActions, chrome: AgentListChrome) {
    LettaPageScaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        header = {
            ShellHeader(
                title = HeaderTitle.Text(if (state.sharePreview != null) "Share to agent" else "Agents"),
                controls = HeaderControls(
                    nav = HeaderNav.Back(chrome.onBack),
                    actions = listOfNotNull(chrome.onImport?.let { HeaderAction.Icon(LettaIcons.FileOpen, "Import agent", it) }),
                ),
            )
        },
        floatingAction = chrome.onCreate?.let { create ->
            {
                FloatingActionButton(onClick = create, modifier = Modifier.padding(LettaDimens.Space.lg).testTag(AgentListScreenTags.CREATE)) {
                    Icon(LettaIcons.Add, "Create agent")
                }
            }
        },
    ) { padding ->
        AgentsPage(state = state, actions = actions, modifier = Modifier.padding(padding), onRetry = chrome.onRetry)
    }
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
                    onReadFailed = { snackbar.dispatch(context.getString(R.string.screen_agents_import_read_failed)) },
                    onImported = { importedCount, importedId ->
                        snackbar.dispatch(
                            context.resources.getQuantityString(R.plurals.screen_agents_import_success, importedCount, importedCount),
                        )
                        importedId?.let { onNavigateToAgent(it, uiState.pendingImportName, null) }
                    },
                ),
            )
        }
    }
    return remember(launcher) { { launcher.launch() } }
}

/** Callbacks routed to [importSelectedAgentFile], keeping it at three arguments. */
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
