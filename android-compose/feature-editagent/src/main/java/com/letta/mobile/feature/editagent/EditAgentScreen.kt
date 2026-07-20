package com.letta.mobile.feature.editagent

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.SnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

internal enum class EditAgentConfigTab(val label: String) {
    Basics("Basics"),
    Models("Models"),
    Memory("Memory"),
    Tools("Tools"),
    Runtime("Runtime"),
    Advanced("Advanced"),
}

internal object EditAgentTestTags {
    const val CONTENT_LIST = "edit_agent_content_list"
    const val TAB_PREFIX = "edit_agent_tab_"

    fun tab(label: String): String = TAB_PREFIX + label.lowercase(Locale.US)
}

internal data class EditAgentActionsSheetActions(
    val onDismiss: () -> Unit,
    val onSave: () -> Unit,
    val onExport: () -> Unit,
    val onClone: () -> Unit,
)

internal data class EditAgentOverlayState(
    val showSectionIndex: Boolean,
    val showActionSheet: Boolean,
    val showResetDialog: Boolean,
    val showDeleteDialog: Boolean,
    val showCloneDialog: Boolean,
)

internal data class EditAgentOverlayCallbacks(
    val onDismissSectionIndex: () -> Unit,
    val onSectionSelected: (String) -> Unit,
    val onDismissActionSheet: () -> Unit,
    val onSave: () -> Unit,
    val onExport: () -> Unit,
    val onClone: () -> Unit,
    val onDismissResetDialog: () -> Unit,
    val onConfirmReset: () -> Unit,
    val onDismissDeleteDialog: () -> Unit,
    val onConfirmDelete: () -> Unit,
    val onDismissCloneDialog: () -> Unit,
    val onCloneAgent: (String?, Boolean, Boolean) -> Unit,
)

internal data class EditAgentLoadedData(
    val state: EditAgentUiState,
    val llmModels: List<LlmModel>,
    val embeddingModels: List<EmbeddingModel>,
    val contentPadding: PaddingValues,
    val lazyListState: LazyListState,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditAgentScreen(
    onNavigateBack: () -> Unit,
) {
    EditAgentScreenContent(
        onNavigateBack = onNavigateBack,
        viewModel = hiltViewModel(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditAgentScreenContent(
    onNavigateBack: () -> Unit,
    viewModel: EditAgentViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val llmModels by viewModel.llmModels.collectAsStateWithLifecycle()
    val embeddingModels by viewModel.embeddingModels.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarDispatcher.current
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val dialogState = rememberEditAgentDialogState()
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            EditAgentTopBar(
                params = EditAgentTopBarParams(
                    uiState = uiState,
                    scrollBehavior = scrollBehavior,
                    dialogState = dialogState,
                    onNavigateBack = onNavigateBack,
                    onSave = {
                        viewModel.saveAgent {
                            snackbar.dispatch(context.getString(R.string.screen_agent_edit_agent_saved))
                        }
                    },
                ),
            )
        },
    ) { paddingValues ->
        EditAgentScreenBody(
            params = EditAgentScreenBodyParams(
                uiState = uiState,
                models = EditAgentModelsBundle(llmModels = llmModels, embeddingModels = embeddingModels),
                viewModel = viewModel,
                layout = EditAgentLayoutBundle(
                    paddingValues = paddingValues,
                    lazyListState = lazyListState,
                    coroutineScope = coroutineScope,
                ),
                environment = EditAgentEnvironmentBundle(
                    snackbar = snackbar,
                    context = context,
                    onNavigateBack = onNavigateBack,
                ),
                dialogState = dialogState,
            ),
        )
    }
}

internal class EditAgentDialogState {
    var showActionSheet by mutableStateOf(false)
    var showCloneDialog by mutableStateOf(false)
    var showResetDialog by mutableStateOf(false)
    var showDeleteDialog by mutableStateOf(false)
    var showSectionIndex by mutableStateOf(false)
}

@Composable
private fun rememberEditAgentDialogState(): EditAgentDialogState = remember { EditAgentDialogState() }

/**
 * Everything [EditAgentTopBar] needs. Bundling the state, dialog handles, and
 * navigation callbacks keeps the composable at a single argument and clears
 * the CodeScene "Excess Number of Function Arguments" advisory.
 */
internal data class EditAgentTopBarParams(
    val uiState: UiState<EditAgentUiState>,
    val scrollBehavior: TopAppBarScrollBehavior,
    val dialogState: EditAgentDialogState,
    val onNavigateBack: () -> Unit,
    val onSave: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditAgentTopBar(params: EditAgentTopBarParams) {
    val agentName = (params.uiState as? UiState.Success)?.data?.name?.takeIf { it.isNotBlank() }
    LargeFlexibleTopAppBar(
        title = {
            if (agentName != null) {
                EditAgentTitleJumpControl(
                    agentName = agentName,
                    onClick = { params.dialogState.showSectionIndex = true },
                )
            }
        },
        colors = LettaTopBarDefaults.largeTopAppBarColors(),
        scrollBehavior = params.scrollBehavior,
        navigationIcon = {
            IconButton(onClick = params.onNavigateBack) {
                Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
            }
        },
        actions = {
            IconButton(onClick = params.onSave) {
                Icon(LettaIcons.Save, contentDescription = stringResource(R.string.action_save_changes))
            }
            IconButton(onClick = { params.dialogState.showActionSheet = true }) {
                Icon(LettaIcons.MoreVert, contentDescription = "More actions")
            }
        },
    )
}

@Composable
private fun EditAgentTitleJumpControl(
    agentName: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = agentName,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            LettaIcons.ExpandMore,
            contentDescription = stringResource(R.string.screen_agent_edit_jump_to_section),
        )
    }
}

/** LLM and embedding model catalogues rendered on the edit screen. */
internal data class EditAgentModelsBundle(
    val llmModels: List<LlmModel>,
    val embeddingModels: List<EmbeddingModel>,
)

/** Surface handles (padding, list state, coroutine scope) for the loaded UI. */
internal data class EditAgentLayoutBundle(
    val paddingValues: PaddingValues,
    val lazyListState: LazyListState,
    val coroutineScope: CoroutineScope,
)

/** Ambient environment (snackbar, context, back-navigation) used by dialogs. */
internal data class EditAgentEnvironmentBundle(
    val snackbar: SnackbarDispatcher,
    val context: Context,
    val onNavigateBack: () -> Unit,
)

/**
 * Bundle of everything [EditAgentScreenBody] needs. Keeping this as one holder
 * lets the composable stay at a single argument, satisfying the CodeScene
 * "Excess Number of Function Arguments" advisory.
 */
internal data class EditAgentScreenBodyParams(
    val uiState: UiState<EditAgentUiState>,
    val models: EditAgentModelsBundle,
    val viewModel: EditAgentViewModel,
    val layout: EditAgentLayoutBundle,
    val environment: EditAgentEnvironmentBundle,
    val dialogState: EditAgentDialogState,
)

@Composable
private fun EditAgentScreenBody(params: EditAgentScreenBodyParams) {
    when (val state = params.uiState) {
        is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
        is UiState.Error -> ErrorContent(
            message = state.message,
            onRetry = { params.viewModel.loadAgent() },
            modifier = Modifier.padding(params.layout.paddingValues),
        )
        is UiState.Success -> EditAgentLoadedContent(
            params = EditAgentLoadedContentParams(
                agentState = state.data,
                models = params.models,
                viewModel = params.viewModel,
                layout = params.layout,
                environment = params.environment,
                dialogState = params.dialogState,
            ),
        )
    }
}

/**
 * Parameters bundled for the loaded-agent branch of the screen. Bundling keeps
 * [EditAgentLoadedContent] at a single argument and clears the CodeScene
 * "Excess Number of Function Arguments" advisory.
 */
internal data class EditAgentLoadedContentParams(
    val agentState: EditAgentUiState,
    val models: EditAgentModelsBundle,
    val viewModel: EditAgentViewModel,
    val layout: EditAgentLayoutBundle,
    val environment: EditAgentEnvironmentBundle,
    val dialogState: EditAgentDialogState,
)

@Composable
private fun EditAgentLoadedContent(params: EditAgentLoadedContentParams) {
    val callbacks = params.viewModel.contentCallbacks(
        onResetMessages = { params.dialogState.showResetDialog = true },
        onDeleteAgent = { params.dialogState.showDeleteDialog = true },
    )
    EditAgentContent(
        state = params.agentState,
        llmModels = params.models.llmModels,
        embeddingModels = params.models.embeddingModels,
        callbacks = callbacks,
        contentPadding = params.layout.paddingValues,
        lazyListState = params.layout.lazyListState,
    )
    if (params.dialogState.showSectionIndex) {
        SectionIndexSheet(
            onDismiss = { params.dialogState.showSectionIndex = false },
            onSelect = { targetKey ->
                params.dialogState.showSectionIndex = false
                params.layout.coroutineScope.launch {
                    params.layout.lazyListState.animateScrollToKey(targetKey)
                }
            },
        )
    }
    EditAgentDialogs(
        visibility = params.dialogState.toVisibility(),
        host = EditAgentDialogsHost(
            agentState = params.agentState,
            viewModel = params.viewModel,
            snackbar = params.environment.snackbar,
            context = params.environment.context,
            onNavigateBack = params.environment.onNavigateBack,
        ),
    )
}

private fun EditAgentDialogState.toVisibility(): EditAgentDialogVisibility =
    EditAgentDialogVisibility(
        actionSheet = EditAgentDialogToggle(
            visible = showActionSheet,
            onVisibleChange = { showActionSheet = it },
        ),
        resetDialog = EditAgentDialogToggle(
            visible = showResetDialog,
            onVisibleChange = { showResetDialog = it },
        ),
        deleteDialog = EditAgentDialogToggle(
            visible = showDeleteDialog,
            onVisibleChange = { showDeleteDialog = it },
        ),
        cloneDialog = EditAgentDialogToggle(
            visible = showCloneDialog,
            onVisibleChange = { showCloneDialog = it },
        ),
    )
