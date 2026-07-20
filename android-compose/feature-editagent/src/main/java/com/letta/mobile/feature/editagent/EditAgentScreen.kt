package com.letta.mobile.feature.editagent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults
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
    var showActionSheet by remember { mutableStateOf(false) }
    var showCloneDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    // letta-mobile-mpr4: section-jump Index. Title tap opens a sheet
    // that lists every section; selecting one animateScrollToItems the
    // shared LazyListState in EditAgentContent.
    var showSectionIndex by remember { mutableStateOf(false) }
    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val topBarActions = EditAgentTopBarActions(
        onNavigateBack = onNavigateBack,
        onTitleClick = { showSectionIndex = true },
        onSave = {
            viewModel.saveAgent {
                snackbar.dispatch(context.getString(R.string.screen_agent_edit_agent_saved))
            }
        },
        onOpenActions = { showActionSheet = true },
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            EditAgentTopBar(
                uiState = uiState,
                scrollBehavior = scrollBehavior,
                actions = topBarActions,
            )
        }
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadAgent() },
                modifier = Modifier.padding(paddingValues)
            )
            is UiState.Success -> {
                EditAgentLoadedContent(
                    data = EditAgentLoadedData(
                        state = state.data,
                        llmModels = llmModels,
                        embeddingModels = embeddingModels,
                        contentPadding = paddingValues,
                        lazyListState = lazyListState,
                    ),
                    contentCallbacks = editAgentContentCallbacks(
                        viewModel = viewModel,
                        onRequestResetDialog = { showResetDialog = true },
                        onRequestDeleteDialog = { showDeleteDialog = true },
                    ),
                    overlayState = EditAgentOverlayState(
                        showSectionIndex = showSectionIndex,
                        showActionSheet = showActionSheet,
                        showResetDialog = showResetDialog,
                        showDeleteDialog = showDeleteDialog,
                        showCloneDialog = showCloneDialog,
                    ),
                    overlayCallbacks = EditAgentOverlayCallbacks(
                        onDismissSectionIndex = { showSectionIndex = false },
                        onSectionSelected = { targetKey ->
                            showSectionIndex = false
                            coroutineScope.launch {
                                lazyListState.animateScrollToKey(targetKey)
                            }
                        },
                        onDismissActionSheet = { showActionSheet = false },
                        onSave = topBarActions.onSave,
                        onExport = {
                            viewModel.exportAgent { exportData ->
                                val exported = shareAgentExport(context, exportData)
                                snackbar.dispatch(
                                    context.getString(
                                        if (exported) {
                                            R.string.screen_settings_export_ready
                                        } else {
                                            R.string.screen_settings_export_unavailable
                                        }
                                    )
                                )
                            }
                        },
                        onClone = { showCloneDialog = true },
                        onDismissResetDialog = { showResetDialog = false },
                        onConfirmReset = {
                            showResetDialog = false
                            viewModel.resetMessages {
                                snackbar.dispatch(context.getString(R.string.screen_settings_messages_reset))
                            }
                        },
                        onDismissDeleteDialog = { showDeleteDialog = false },
                        onConfirmDelete = {
                            showDeleteDialog = false
                            viewModel.deleteAgent(onNavigateBack)
                        },
                        onDismissCloneDialog = { showCloneDialog = false },
                        onCloneAgent = { cloneName, overrideExistingTools, stripMessages ->
                            showCloneDialog = false
                            viewModel.cloneAgent(
                                cloneName = cloneName,
                                overrideExistingTools = overrideExistingTools,
                                stripMessages = stripMessages,
                            ) { response ->
                                snackbar.dispatch(
                                    context.resources.getQuantityString(
                                        R.plurals.screen_settings_clone_success,
                                        response.agentIds.size,
                                        response.agentIds.size,
                                    )
                                )
                            }
                        },
                    ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditAgentTopBar(
    uiState: UiState<EditAgentUiState>,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    actions: EditAgentTopBarActions,
) {
    val agentName = (uiState as? UiState.Success)?.data?.name?.takeIf { it.isNotBlank() }
    LargeFlexibleTopAppBar(
        title = {
            if (agentName != null) {
                // letta-mobile-mpr4: tap title to open the
                // section-jump Index. Chevron advertises that
                // it's interactive — without it the affordance
                // is invisible.
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.clickable(onClick = actions.onTitleClick),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = agentName,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Icon(
                        LettaIcons.ExpandMore,
                        contentDescription = stringResource(R.string.screen_agent_edit_jump_to_section),
                    )
                }
            }
        },
        colors = LettaTopBarDefaults.largeTopAppBarColors(),
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            IconButton(onClick = actions.onNavigateBack) {
                Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
            }
        },
        actions = {
            IconButton(onClick = actions.onSave) {
                Icon(LettaIcons.Save, contentDescription = stringResource(R.string.action_save_changes))
            }
            IconButton(onClick = actions.onOpenActions) {
                Icon(LettaIcons.MoreVert, contentDescription = "More actions")
            }
        },
    )
}

@Composable
private fun EditAgentLoadedContent(
    data: EditAgentLoadedData,
    contentCallbacks: EditAgentContentCallbacks,
    overlayState: EditAgentOverlayState,
    overlayCallbacks: EditAgentOverlayCallbacks,
) {
    EditAgentContent(
        state = data.state,
        llmModels = data.llmModels,
        embeddingModels = data.embeddingModels,
        onNameChange = contentCallbacks.onNameChange,
        onDescriptionChange = contentCallbacks.onDescriptionChange,
        onModelChange = contentCallbacks.onModelChange,
        onEmbeddingChange = contentCallbacks.onEmbeddingChange,
        onLoadModels = contentCallbacks.onLoadModels,
        onBlockValueChange = contentCallbacks.onBlockValueChange,
        onBlockDescriptionChange = contentCallbacks.onBlockDescriptionChange,
        onBlockLimitChange = contentCallbacks.onBlockLimitChange,
        onAddBlock = contentCallbacks.onAddBlock,
        onAttachExistingBlock = contentCallbacks.onAttachExistingBlock,
        onAttachExistingBlocks = contentCallbacks.onAttachExistingBlocks,
        onDeleteBlock = contentCallbacks.onDeleteBlock,
        onAddTag = contentCallbacks.onAddTag,
        onRemoveTag = contentCallbacks.onRemoveTag,
        onAttachTool = contentCallbacks.onAttachTool,
        onAttachTools = contentCallbacks.onAttachTools,
        onDetachTool = contentCallbacks.onDetachTool,
        onToolRulesJsonChange = contentCallbacks.onToolRulesJsonChange,
        onAddAgentSecret = contentCallbacks.onAddAgentSecret,
        onAgentSecretKeyChange = contentCallbacks.onAgentSecretKeyChange,
        onAgentSecretValueChange = contentCallbacks.onAgentSecretValueChange,
        onRemoveAgentSecret = contentCallbacks.onRemoveAgentSecret,
        onAddToolEnvironmentVariable = contentCallbacks.onAddToolEnvironmentVariable,
        onToolEnvironmentVariableKeyChange = contentCallbacks.onToolEnvironmentVariableKeyChange,
        onToolEnvironmentVariableValueChange = contentCallbacks.onToolEnvironmentVariableValueChange,
        onRemoveToolEnvironmentVariable = contentCallbacks.onRemoveToolEnvironmentVariable,
        onSystemPromptChange = contentCallbacks.onSystemPromptChange,
        onProviderTypeChange = contentCallbacks.onProviderTypeChange,
        onTemperatureChange = contentCallbacks.onTemperatureChange,
        onMaxOutputTokensChange = contentCallbacks.onMaxOutputTokensChange,
        onParallelToolCallsChange = contentCallbacks.onParallelToolCallsChange,
        onModelProviderNameChange = contentCallbacks.onModelProviderNameChange,
        onModelProviderCategoryChange = contentCallbacks.onModelProviderCategoryChange,
        onModelEnableReasonerChange = contentCallbacks.onModelEnableReasonerChange,
        onModelReasoningEffortChange = contentCallbacks.onModelReasoningEffortChange,
        onModelMaxReasoningTokensChange = contentCallbacks.onModelMaxReasoningTokensChange,
        onModelReasoningJsonChange = contentCallbacks.onModelReasoningJsonChange,
        onModelFrequencyPenaltyChange = contentCallbacks.onModelFrequencyPenaltyChange,
        onModelVerbosityChange = contentCallbacks.onModelVerbosityChange,
        onModelStrictToolCallingChange = contentCallbacks.onModelStrictToolCallingChange,
        onModelResponseFormatJsonChange = contentCallbacks.onModelResponseFormatJsonChange,
        onModelResponseSchemaJsonChange = contentCallbacks.onModelResponseSchemaJsonChange,
        onModelThinkingConfigJsonChange = contentCallbacks.onModelThinkingConfigJsonChange,
        onModelPutInnerThoughtsInKwargsChange = contentCallbacks.onModelPutInnerThoughtsInKwargsChange,
        onModelToolCallParserChange = contentCallbacks.onModelToolCallParserChange,
        onModelAnthropicEffortChange = contentCallbacks.onModelAnthropicEffortChange,
        onContextWindowChange = contentCallbacks.onContextWindowChange,
        onEnableSleeptimeChange = contentCallbacks.onEnableSleeptimeChange,
        onSummarizationPromptChange = contentCallbacks.onSummarizationPromptChange,
        onCompactionClipCharsChange = contentCallbacks.onCompactionClipCharsChange,
        onSlidingWindowPercentageChange = contentCallbacks.onSlidingWindowPercentageChange,
        onPromptAcknowledgementChange = contentCallbacks.onPromptAcknowledgementChange,
        onCompactionModeChange = contentCallbacks.onCompactionModeChange,
        onCompactionModelChange = contentCallbacks.onCompactionModelChange,
        onCompactionModelSettingsJsonChange = contentCallbacks.onCompactionModelSettingsJsonChange,
        onResetMessages = contentCallbacks.onResetMessages,
        onDeleteAgent = contentCallbacks.onDeleteAgent,
        contentPadding = data.contentPadding,
        lazyListState = data.lazyListState,
    )

    if (overlayState.showSectionIndex) {
        SectionIndexSheet(
            onDismiss = overlayCallbacks.onDismissSectionIndex,
            onSelect = overlayCallbacks.onSectionSelected,
        )
    }

    EditAgentActionsSheet(
        show = overlayState.showActionSheet,
        actions = EditAgentActionsSheetActions(
            onDismiss = overlayCallbacks.onDismissActionSheet,
            onSave = overlayCallbacks.onSave,
            onExport = overlayCallbacks.onExport,
            onClone = overlayCallbacks.onClone,
        ),
    )

    ConfirmDialog(
        show = overlayState.showResetDialog,
        title = stringResource(R.string.screen_settings_reset_messages_title),
        message = stringResource(R.string.screen_settings_reset_messages_confirm),
        confirmText = stringResource(R.string.action_reset_messages),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = overlayCallbacks.onConfirmReset,
        onDismiss = overlayCallbacks.onDismissResetDialog,
        destructive = true,
    )

    ConfirmDialog(
        show = overlayState.showDeleteDialog,
        title = stringResource(R.string.screen_agents_dialog_delete_title),
        message = stringResource(R.string.screen_agents_dialog_delete_confirm_permanent),
        confirmText = stringResource(R.string.action_delete),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = overlayCallbacks.onConfirmDelete,
        onDismiss = overlayCallbacks.onDismissDeleteDialog,
        destructive = true,
    )

    if (overlayState.showCloneDialog) {
        CloneAgentDialog(
            initialName = data.state.name,
            isCloning = data.state.isCloning,
            onDismiss = overlayCallbacks.onDismissCloneDialog,
            onClone = overlayCallbacks.onCloneAgent,
        )
    }
}

@Composable
private fun EditAgentActionsSheet(
    show: Boolean,
    actions: EditAgentActionsSheetActions,
) {
    ActionSheet(
        show = show,
        onDismiss = actions.onDismiss,
        title = "Actions",
    ) {
        ActionSheetItem(
            text = stringResource(R.string.action_save_settings),
            icon = LettaIcons.Check,
            onClick = {
                actions.onDismiss()
                actions.onSave()
            },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_export_agent),
            icon = LettaIcons.Share,
            onClick = {
                actions.onDismiss()
                actions.onExport()
            },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_clone_agent),
            icon = LettaIcons.Copy,
            onClick = {
                actions.onDismiss()
                actions.onClone()
            },
        )
        // letta-mobile-cygd: Reset Messages and Delete Agent
        // moved to EditAgentContent's bottom Danger Zone so
        // destructive actions live in one unmistakable spot
        // instead of being a long-press away in the overflow.
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionIndexSheet(
    onDismiss: () -> Unit,
    onSelect: (anchorKey: String) -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entries = listOf(
        SectionAnchors.BASICS to R.string.screen_agent_edit_section_basics,
        SectionAnchors.MODELS to R.string.screen_agent_edit_section_models,
        SectionAnchors.MEMORY to R.string.screen_agent_edit_section_memory,
        SectionAnchors.TOOLS to R.string.screen_agent_edit_section_tools,
        SectionAnchors.RUNTIME to R.string.screen_agent_edit_section_runtime,
        SectionAnchors.ADVANCED to R.string.screen_agent_edit_section_advanced,
        SectionAnchors.DANGER to R.string.screen_create_project_danger_zone_title,
    )
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.screen_agent_edit_jump_to_section),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            entries.forEach { (anchorKey, labelRes) ->
                val isDanger = anchorKey == SectionAnchors.DANGER
                androidx.compose.material3.ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(anchorKey) },
                    headlineContent = {
                        Text(
                            text = stringResource(labelRes),
                            color = if (isDanger) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                )
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * letta-mobile-mpr4: scroll a [LazyListState] to the item that matches
 * [targetKey]. If the item is currently visible, animate to it
 * directly; otherwise, sweep forward in chunks until it appears (or we
 * hit the end of the list). LazyListState exposes keys only for
 * visible items, so a sweep is unavoidable for off-screen targets —
 * the agent config has ~21 items so two or three sweep iterations
 * cover any jump.
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.animateScrollToKey(
    targetKey: Any,
) {
    val visible = layoutInfo.visibleItemsInfo
    val direct = visible.firstOrNull { it.key == targetKey }?.index
    if (direct != null) {
        animateScrollToItem(direct)
        return
    }
    val total = layoutInfo.totalItemsCount
    if (total == 0) return

    var lastSeenIndex = visible.lastOrNull()?.index ?: 0
    var safety = 0
    while (lastSeenIndex < total - 1 && safety < 16) {
        val nextStart = (lastSeenIndex + 1).coerceAtMost(total - 1)
        scrollToItem(nextStart)
        val found = layoutInfo.visibleItemsInfo.firstOrNull { it.key == targetKey }?.index
        if (found != null) {
            animateScrollToItem(found)
            return
        }
        val newLast = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: break
        if (newLast <= lastSeenIndex) break
        lastSeenIndex = newLast
        safety++
    }
}

private fun shareAgentExport(context: Context, exportData: String): Boolean {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, exportData)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.screen_settings_export_subject))
    }

    val chooser = Intent.createChooser(shareIntent, context.getString(R.string.action_export_agent))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    try {
        context.startActivity(chooser)
        return true
    } catch (_: ActivityNotFoundException) {
        return false
    }
}
