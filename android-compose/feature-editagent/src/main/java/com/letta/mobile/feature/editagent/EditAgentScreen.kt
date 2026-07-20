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

internal data class EditAgentTopBarActions(
    val onNavigateBack: () -> Unit,
    val onTitleClick: () -> Unit,
    val onSave: () -> Unit,
    val onOpenActions: () -> Unit,
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
    val llmModels: List<com.letta.mobile.data.model.LlmModel>,
    val embeddingModels: List<com.letta.mobile.data.model.EmbeddingModel>,
    val contentPadding: androidx.compose.foundation.layout.PaddingValues,
    val lazyListState: androidx.compose.foundation.lazy.LazyListState,
)

internal data class EditAgentContentCallbacks(
    val onNameChange: (String) -> Unit,
    val onDescriptionChange: (String) -> Unit,
    val onModelChange: (String) -> Unit,
    val onEmbeddingChange: (String) -> Unit,
    val onLoadModels: () -> Unit,
    val onBlockValueChange: (String, String) -> Unit,
    val onBlockDescriptionChange: (String, String) -> Unit,
    val onBlockLimitChange: (String, Int?) -> Unit,
    val onAddBlock: (String, String, String, Int?) -> Unit,
    val onAttachExistingBlock: (String) -> Unit,
    val onAttachExistingBlocks: (List<String>) -> Unit,
    val onDeleteBlock: (String) -> Unit,
    val onAddTag: (String) -> Unit,
    val onRemoveTag: (String) -> Unit,
    val onAttachTool: (String) -> Unit,
    val onAttachTools: (List<String>) -> Unit,
    val onDetachTool: (String) -> Unit,
    val onToolRulesJsonChange: (String) -> Unit,
    val onAddAgentSecret: () -> Unit,
    val onAgentSecretKeyChange: (Int, String) -> Unit,
    val onAgentSecretValueChange: (Int, String) -> Unit,
    val onRemoveAgentSecret: (Int) -> Unit,
    val onAddToolEnvironmentVariable: () -> Unit,
    val onToolEnvironmentVariableKeyChange: (Int, String) -> Unit,
    val onToolEnvironmentVariableValueChange: (Int, String) -> Unit,
    val onRemoveToolEnvironmentVariable: (Int) -> Unit,
    val onSystemPromptChange: (String) -> Unit,
    val onProviderTypeChange: (String) -> Unit,
    val onTemperatureChange: (Float) -> Unit,
    val onMaxOutputTokensChange: (Int) -> Unit,
    val onParallelToolCallsChange: (Boolean) -> Unit,
    val onModelProviderNameChange: (String) -> Unit,
    val onModelProviderCategoryChange: (String) -> Unit,
    val onModelEnableReasonerChange: (Boolean) -> Unit,
    val onModelReasoningEffortChange: (String) -> Unit,
    val onModelMaxReasoningTokensChange: (String) -> Unit,
    val onModelReasoningJsonChange: (String) -> Unit,
    val onModelFrequencyPenaltyChange: (String) -> Unit,
    val onModelVerbosityChange: (String) -> Unit,
    val onModelStrictToolCallingChange: (Boolean) -> Unit,
    val onModelResponseFormatJsonChange: (String) -> Unit,
    val onModelResponseSchemaJsonChange: (String) -> Unit,
    val onModelThinkingConfigJsonChange: (String) -> Unit,
    val onModelPutInnerThoughtsInKwargsChange: (Boolean) -> Unit,
    val onModelToolCallParserChange: (String) -> Unit,
    val onModelAnthropicEffortChange: (String) -> Unit,
    val onContextWindowChange: (Int) -> Unit,
    val onEnableSleeptimeChange: (Boolean) -> Unit,
    val onSummarizationPromptChange: (String) -> Unit,
    val onCompactionClipCharsChange: (Int) -> Unit,
    val onSlidingWindowPercentageChange: (Float) -> Unit,
    val onPromptAcknowledgementChange: (Boolean) -> Unit,
    val onCompactionModeChange: (String) -> Unit,
    val onCompactionModelChange: (String) -> Unit,
    val onCompactionModelSettingsJsonChange: (String) -> Unit,
    val onResetMessages: () -> Unit,
    val onDeleteAgent: () -> Unit,
)

internal fun editAgentContentCallbacks(
    viewModel: EditAgentViewModel,
    onRequestResetDialog: () -> Unit,
    onRequestDeleteDialog: () -> Unit,
): EditAgentContentCallbacks = EditAgentContentCallbacks(
    onNameChange = viewModel::updateName,
    onDescriptionChange = viewModel::updateDescription,
    onModelChange = viewModel::updateModel,
    onEmbeddingChange = viewModel::updateEmbedding,
    onLoadModels = viewModel::loadModels,
    onBlockValueChange = viewModel::updateBlockValue,
    onBlockDescriptionChange = viewModel::updateBlockDescription,
    onBlockLimitChange = viewModel::updateBlockLimit,
    onAddBlock = viewModel::addBlock,
    onAttachExistingBlock = viewModel::attachExistingBlock,
    onAttachExistingBlocks = viewModel::attachExistingBlocks,
    onDeleteBlock = viewModel::deleteBlock,
    onAddTag = viewModel::addTag,
    onRemoveTag = viewModel::removeTag,
    onAttachTool = viewModel::attachTool,
    onAttachTools = viewModel::attachTools,
    onDetachTool = viewModel::detachTool,
    onToolRulesJsonChange = viewModel::updateToolRulesJson,
    onAddAgentSecret = viewModel::addAgentSecret,
    onAgentSecretKeyChange = viewModel::updateAgentSecretKey,
    onAgentSecretValueChange = viewModel::updateAgentSecretValue,
    onRemoveAgentSecret = viewModel::removeAgentSecret,
    onAddToolEnvironmentVariable = viewModel::addToolEnvironmentVariable,
    onToolEnvironmentVariableKeyChange = viewModel::updateToolEnvironmentVariableKey,
    onToolEnvironmentVariableValueChange = viewModel::updateToolEnvironmentVariableValue,
    onRemoveToolEnvironmentVariable = viewModel::removeToolEnvironmentVariable,
    onSystemPromptChange = viewModel::updateSystemPrompt,
    onProviderTypeChange = viewModel::updateProviderType,
    onTemperatureChange = viewModel::updateTemperature,
    onMaxOutputTokensChange = viewModel::updateMaxOutputTokens,
    onParallelToolCallsChange = viewModel::updateParallelToolCalls,
    onModelProviderNameChange = viewModel::updateModelProviderName,
    onModelProviderCategoryChange = viewModel::updateModelProviderCategory,
    onModelEnableReasonerChange = viewModel::updateModelEnableReasoner,
    onModelReasoningEffortChange = viewModel::updateModelReasoningEffort,
    onModelMaxReasoningTokensChange = viewModel::updateModelMaxReasoningTokens,
    onModelReasoningJsonChange = viewModel::updateModelReasoningJson,
    onModelFrequencyPenaltyChange = viewModel::updateModelFrequencyPenalty,
    onModelVerbosityChange = viewModel::updateModelVerbosity,
    onModelStrictToolCallingChange = viewModel::updateModelStrictToolCalling,
    onModelResponseFormatJsonChange = viewModel::updateModelResponseFormatJson,
    onModelResponseSchemaJsonChange = viewModel::updateModelResponseSchemaJson,
    onModelThinkingConfigJsonChange = viewModel::updateModelThinkingConfigJson,
    onModelPutInnerThoughtsInKwargsChange = viewModel::updateModelPutInnerThoughtsInKwargs,
    onModelToolCallParserChange = viewModel::updateModelToolCallParser,
    onModelAnthropicEffortChange = viewModel::updateModelAnthropicEffort,
    onContextWindowChange = viewModel::updateContextWindow,
    onEnableSleeptimeChange = viewModel::updateEnableSleeptime,
    onSummarizationPromptChange = viewModel::updateSummarizationPrompt,
    onCompactionClipCharsChange = viewModel::updateCompactionClipChars,
    onSlidingWindowPercentageChange = viewModel::updateSlidingWindowPercentage,
    onPromptAcknowledgementChange = viewModel::updatePromptAcknowledgement,
    onCompactionModeChange = viewModel::updateCompactionMode,
    onCompactionModelChange = viewModel::updateCompactionModel,
    onCompactionModelSettingsJsonChange = viewModel::updateCompactionModelSettingsJson,
    onResetMessages = onRequestResetDialog,
    onDeleteAgent = onRequestDeleteDialog,
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
                uiState = uiState,
                scrollBehavior = scrollBehavior,
                dialogState = dialogState,
                onNavigateBack = onNavigateBack,
                onSave = {
                    viewModel.saveAgent {
                        snackbar.dispatch(context.getString(R.string.screen_agent_edit_agent_saved))
                    }
                },
            )
        },
    ) { paddingValues ->
        EditAgentScreenBody(
            uiState = uiState,
            llmModels = llmModels,
            embeddingModels = embeddingModels,
            viewModel = viewModel,
            paddingValues = paddingValues,
            lazyListState = lazyListState,
            dialogState = dialogState,
            snackbar = snackbar,
            context = context,
            onNavigateBack = onNavigateBack,
            coroutineScope = coroutineScope,
        )
    }
}

private class EditAgentDialogState {
    var showActionSheet by mutableStateOf(false)
    var showCloneDialog by mutableStateOf(false)
    var showResetDialog by mutableStateOf(false)
    var showDeleteDialog by mutableStateOf(false)
    var showSectionIndex by mutableStateOf(false)
}

@Composable
private fun rememberEditAgentDialogState(): EditAgentDialogState = remember { EditAgentDialogState() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditAgentTopBar(
    uiState: UiState<EditAgentUiState>,
    scrollBehavior: TopAppBarScrollBehavior,
    dialogState: EditAgentDialogState,
    onNavigateBack: () -> Unit,
    onSave: () -> Unit,
) {
    val agentName = (uiState as? UiState.Success)?.data?.name?.takeIf { it.isNotBlank() }
    LargeFlexibleTopAppBar(
        title = {
            if (agentName != null) {
                EditAgentTitleJumpControl(
                    agentName = agentName,
                    onClick = { dialogState.showSectionIndex = true },
                )
            }
        },
        colors = LettaTopBarDefaults.largeTopAppBarColors(),
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
            }
        },
        actions = {
            IconButton(onClick = onSave) {
                Icon(LettaIcons.Save, contentDescription = stringResource(R.string.action_save_changes))
            }
            IconButton(onClick = { dialogState.showActionSheet = true }) {
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

@Composable
private fun EditAgentScreenBody(
    uiState: UiState<EditAgentUiState>,
    llmModels: List<LlmModel>,
    embeddingModels: List<EmbeddingModel>,
    viewModel: EditAgentViewModel,
    paddingValues: PaddingValues,
    lazyListState: LazyListState,
    dialogState: EditAgentDialogState,
    snackbar: SnackbarDispatcher,
    context: Context,
    onNavigateBack: () -> Unit,
    coroutineScope: CoroutineScope,
) {
    when (val state = uiState) {
        is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
        is UiState.Error -> ErrorContent(
            message = state.message,
            onRetry = { viewModel.loadAgent() },
            modifier = Modifier.padding(paddingValues),
        )
        is UiState.Success -> EditAgentLoadedContent(
            agentState = state.data,
            llmModels = llmModels,
            embeddingModels = embeddingModels,
            viewModel = viewModel,
            paddingValues = paddingValues,
            lazyListState = lazyListState,
            dialogState = dialogState,
            snackbar = snackbar,
            context = context,
            onNavigateBack = onNavigateBack,
            coroutineScope = coroutineScope,
        )
    }
}

@Composable
private fun EditAgentLoadedContent(
    agentState: EditAgentUiState,
    llmModels: List<LlmModel>,
    embeddingModels: List<EmbeddingModel>,
    viewModel: EditAgentViewModel,
    paddingValues: PaddingValues,
    lazyListState: LazyListState,
    dialogState: EditAgentDialogState,
    snackbar: SnackbarDispatcher,
    context: Context,
    onNavigateBack: () -> Unit,
    coroutineScope: CoroutineScope,
) {
    val callbacks = viewModel.contentCallbacks(
        onResetMessages = { dialogState.showResetDialog = true },
        onDeleteAgent = { dialogState.showDeleteDialog = true },
    )
    EditAgentContent(
        state = agentState,
        llmModels = llmModels,
        embeddingModels = embeddingModels,
        callbacks = callbacks,
        contentPadding = paddingValues,
        lazyListState = lazyListState,
    )
    if (dialogState.showSectionIndex) {
        SectionIndexSheet(
            onDismiss = { dialogState.showSectionIndex = false },
            onSelect = { targetKey ->
                dialogState.showSectionIndex = false
                coroutineScope.launch {
                    lazyListState.animateScrollToKey(targetKey)
                }
            },
        )
    }
    EditAgentDialogs(
        visibility = dialogState.toVisibility(),
        host = EditAgentDialogsHost(
            agentState = agentState,
            viewModel = viewModel,
            snackbar = snackbar,
            context = context,
            onNavigateBack = onNavigateBack,
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
