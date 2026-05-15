package com.letta.mobile.ui.screens.editagent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
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

object EditAgentTestTags {
    const val CONTENT_LIST = "edit_agent_content_list"
    const val TAB_PREFIX = "edit_agent_tab_"
    const val SECTION_PICKER_TRIGGER = "edit_agent_section_picker_trigger"
    const val SECTION_PICKER_SHEET = "edit_agent_section_picker_sheet"

    fun tab(label: String): String = TAB_PREFIX + label.lowercase(Locale.US)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditAgentScreen(
    onNavigateBack: () -> Unit,
    viewModel: EditAgentViewModel = hiltViewModel()
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

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {},
                colors = LettaTopBarDefaults.largeTopAppBarColors(),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.saveAgent {
                            snackbar.dispatch(context.getString(R.string.screen_agent_edit_agent_saved))
                        }
                    }) {
                        Icon(LettaIcons.Save, contentDescription = stringResource(R.string.action_save_changes))
                    }
                    IconButton(onClick = { showActionSheet = true }) {
                        Icon(LettaIcons.MoreVert, contentDescription = "More actions")
                    }
                },
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
                EditAgentContent(
                    state = state.data,
                    llmModels = llmModels,
                    embeddingModels = embeddingModels,
                    onNameChange = { viewModel.updateName(it) },
                    onDescriptionChange = { viewModel.updateDescription(it) },
                    onModelChange = { viewModel.updateModel(it) },
                    onEmbeddingChange = { viewModel.updateEmbedding(it) },
                    onLoadModels = { viewModel.loadModels() },
                    onBlockValueChange = { label, value -> viewModel.updateBlockValue(label, value) },
                    onBlockDescriptionChange = { label, value -> viewModel.updateBlockDescription(label, value) },
                    onBlockLimitChange = { label, value -> viewModel.updateBlockLimit(label, value) },
                    onAddBlock = { label, value, description, limit -> viewModel.addBlock(label, value, description, limit) },
                    onAttachExistingBlock = { viewModel.attachExistingBlock(it) },
                    onAttachExistingBlocks = { viewModel.attachExistingBlocks(it) },
                    onDeleteBlock = { viewModel.deleteBlock(it) },
                    onAddTag = { viewModel.addTag(it) },
                    onRemoveTag = { viewModel.removeTag(it) },
                    onAttachTool = { viewModel.attachTool(it) },
                    onAttachTools = { viewModel.attachTools(it) },
                    onDetachTool = { viewModel.detachTool(it) },
                    onToolRulesJsonChange = { viewModel.updateToolRulesJson(it) },
                    onAddAgentSecret = { viewModel.addAgentSecret() },
                    onAgentSecretKeyChange = { index, value -> viewModel.updateAgentSecretKey(index, value) },
                    onAgentSecretValueChange = { index, value -> viewModel.updateAgentSecretValue(index, value) },
                    onRemoveAgentSecret = { viewModel.removeAgentSecret(it) },
                    onAddToolEnvironmentVariable = { viewModel.addToolEnvironmentVariable() },
                    onToolEnvironmentVariableKeyChange = { index, value -> viewModel.updateToolEnvironmentVariableKey(index, value) },
                    onToolEnvironmentVariableValueChange = { index, value -> viewModel.updateToolEnvironmentVariableValue(index, value) },
                    onRemoveToolEnvironmentVariable = { viewModel.removeToolEnvironmentVariable(it) },
                    onSystemPromptChange = { viewModel.updateSystemPrompt(it) },
                    onProviderTypeChange = { viewModel.updateProviderType(it) },
                    onTemperatureChange = { viewModel.updateTemperature(it) },
                    onMaxOutputTokensChange = { viewModel.updateMaxOutputTokens(it) },
                    onParallelToolCallsChange = { viewModel.updateParallelToolCalls(it) },
                    onModelProviderNameChange = { viewModel.updateModelProviderName(it) },
                    onModelProviderCategoryChange = { viewModel.updateModelProviderCategory(it) },
                    onModelEnableReasonerChange = { viewModel.updateModelEnableReasoner(it) },
                    onModelReasoningEffortChange = { viewModel.updateModelReasoningEffort(it) },
                    onModelMaxReasoningTokensChange = { viewModel.updateModelMaxReasoningTokens(it) },
                    onModelReasoningJsonChange = { viewModel.updateModelReasoningJson(it) },
                    onModelFrequencyPenaltyChange = { viewModel.updateModelFrequencyPenalty(it) },
                    onModelVerbosityChange = { viewModel.updateModelVerbosity(it) },
                    onModelStrictToolCallingChange = { viewModel.updateModelStrictToolCalling(it) },
                    onModelResponseFormatJsonChange = { viewModel.updateModelResponseFormatJson(it) },
                    onModelResponseSchemaJsonChange = { viewModel.updateModelResponseSchemaJson(it) },
                    onModelThinkingConfigJsonChange = { viewModel.updateModelThinkingConfigJson(it) },
                    onModelPutInnerThoughtsInKwargsChange = { viewModel.updateModelPutInnerThoughtsInKwargs(it) },
                    onModelToolCallParserChange = { viewModel.updateModelToolCallParser(it) },
                    onModelAnthropicEffortChange = { viewModel.updateModelAnthropicEffort(it) },
                    onContextWindowChange = { viewModel.updateContextWindow(it) },
                    onEnableSleeptimeChange = { viewModel.updateEnableSleeptime(it) },
                    onSummarizationPromptChange = { viewModel.updateSummarizationPrompt(it) },
                    onCompactionClipCharsChange = { viewModel.updateCompactionClipChars(it) },
                    onSlidingWindowPercentageChange = { viewModel.updateSlidingWindowPercentage(it) },
                    onPromptAcknowledgementChange = { viewModel.updatePromptAcknowledgement(it) },
                    onCompactionModeChange = { viewModel.updateCompactionMode(it) },
                    onCompactionModelChange = { viewModel.updateCompactionModel(it) },
                    onCompactionModelSettingsJsonChange = { viewModel.updateCompactionModelSettingsJson(it) },
                    onClientModeEnabledChange = { viewModel.updateClientModeEnabled(it) },
                    onClientModeBaseUrlChange = { viewModel.updateClientModeBaseUrl(it) },
                    onClientModeApiKeyChange = { viewModel.updateClientModeApiKey(it) },
                    onTestClientModeConnection = { viewModel.testClientModeConnection() },
                    onResetMessages = { showResetDialog = true },
                    onDeleteAgent = { showDeleteDialog = true },
                    contentPadding = paddingValues,
                )

                // ActionSheet
                ActionSheet(
                    show = showActionSheet,
                    onDismiss = { showActionSheet = false },
                    title = "Actions",
                ) {
                    ActionSheetItem(
                        text = stringResource(R.string.action_save_settings),
                        icon = LettaIcons.Check,
                        onClick = {
                            showActionSheet = false
                            viewModel.saveAgent {
                                snackbar.dispatch(context.getString(R.string.screen_agent_edit_agent_saved))
                            }
                        },
                    )
                    ActionSheetItem(
                        text = stringResource(R.string.action_export_agent),
                        icon = LettaIcons.Share,
                        onClick = {
                            showActionSheet = false
                            viewModel.exportAgent { exportData ->
                                val exported = shareAgentExport(context, exportData)
                                snackbar.dispatch(
                                    context.getString(
                                        if (exported) R.string.screen_settings_export_ready else R.string.screen_settings_export_unavailable
                                    )
                                )
                            }
                        },
                    )
                    ActionSheetItem(
                        text = stringResource(R.string.action_clone_agent),
                        icon = LettaIcons.Copy,
                        onClick = {
                            showActionSheet = false
                            showCloneDialog = true
                        },
                    )
                    // letta-mobile-cygd: Reset Messages and Delete Agent
                    // moved to EditAgentContent's bottom Danger Zone so
                    // destructive actions live in one unmistakable spot
                    // instead of being a long-press away in the overflow.
                }

                // Confirm dialogs
                ConfirmDialog(
                    show = showResetDialog,
                    title = stringResource(R.string.screen_settings_reset_messages_title),
                    message = stringResource(R.string.screen_settings_reset_messages_confirm),
                    confirmText = stringResource(R.string.action_reset_messages),
                    dismissText = stringResource(R.string.action_cancel),
                    onConfirm = {
                        showResetDialog = false
                        viewModel.resetMessages {
                            snackbar.dispatch(context.getString(R.string.screen_settings_messages_reset))
                        }
                    },
                    onDismiss = { showResetDialog = false },
                    destructive = true,
                )

                ConfirmDialog(
                    show = showDeleteDialog,
                    title = stringResource(R.string.screen_agents_dialog_delete_title),
                    message = stringResource(R.string.screen_agents_dialog_delete_confirm_permanent),
                    confirmText = stringResource(R.string.action_delete),
                    dismissText = stringResource(R.string.action_cancel),
                    onConfirm = {
                        showDeleteDialog = false
                        viewModel.deleteAgent(onNavigateBack)
                    },
                    onDismiss = { showDeleteDialog = false },
                    destructive = true,
                )

                if (showCloneDialog) {
                    CloneAgentDialog(
                        initialName = state.data.name,
                        isCloning = state.data.isCloning,
                        onDismiss = { showCloneDialog = false },
                        onClone = { cloneName, overrideExistingTools, stripMessages ->
                            showCloneDialog = false
                            viewModel.cloneAgent(
                                cloneName = cloneName,
                                overrideExistingTools = overrideExistingTools,
                                stripMessages = stripMessages,
                            ) { response ->
                                snackbar.dispatch(
                                    context.getString(
                                        if (response.agentIds.size == 1) R.string.screen_settings_clone_success_single else R.string.screen_settings_clone_success_multiple,
                                        response.agentIds.size,
                                    )
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

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
