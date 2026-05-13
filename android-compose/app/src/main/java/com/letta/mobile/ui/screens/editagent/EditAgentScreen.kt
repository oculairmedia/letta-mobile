package com.letta.mobile.ui.screens.editagent

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.screens.blocks.BlockLibraryViewModel
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.Accordions
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.MultiFieldInputDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ExpandableTitleSearch
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.LettaSearchBar
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.components.highlightSearchMatches
import com.letta.mobile.ui.components.rememberSearchHighlightColors
import com.letta.mobile.ui.screens.settings.ClientModeConnectionState
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

private data class CompactionModeOption(
    val value: String,
    val labelRes: Int,
)

private val compactionModeOptions = listOf(
    CompactionModeOption("sliding_window", R.string.screen_agent_edit_compaction_mode_sliding_window),
    CompactionModeOption("all", R.string.screen_agent_edit_compaction_mode_all),
    CompactionModeOption("self_compact_sliding_window", R.string.screen_agent_edit_compaction_mode_self_sliding_window),
    CompactionModeOption("self_compact_all", R.string.screen_agent_edit_compaction_mode_self_all),
)

private data class AdvancedModelOption(
    val value: String,
    val labelRes: Int,
)

private val reasoningEffortOptions = listOf(
    AdvancedModelOption("", R.string.screen_agent_edit_model_option_default),
    AdvancedModelOption("none", R.string.screen_agent_edit_reasoning_effort_none),
    AdvancedModelOption("minimal", R.string.screen_agent_edit_reasoning_effort_minimal),
    AdvancedModelOption("low", R.string.screen_agent_edit_model_option_low),
    AdvancedModelOption("medium", R.string.screen_agent_edit_model_option_medium),
    AdvancedModelOption("high", R.string.screen_agent_edit_model_option_high),
    AdvancedModelOption("xhigh", R.string.screen_agent_edit_reasoning_effort_xhigh),
)

private val verbosityOptions = listOf(
    AdvancedModelOption("", R.string.screen_agent_edit_model_option_default),
    AdvancedModelOption("low", R.string.screen_agent_edit_model_option_low),
    AdvancedModelOption("medium", R.string.screen_agent_edit_model_option_medium),
    AdvancedModelOption("high", R.string.screen_agent_edit_model_option_high),
)

private val anthropicEffortOptions = listOf(
    AdvancedModelOption("", R.string.screen_agent_edit_model_option_default),
    AdvancedModelOption("low", R.string.screen_agent_edit_model_option_low),
    AdvancedModelOption("medium", R.string.screen_agent_edit_model_option_medium),
    AdvancedModelOption("high", R.string.screen_agent_edit_model_option_high),
    AdvancedModelOption("max", R.string.screen_agent_edit_anthropic_effort_max),
)

private enum class EditAgentConfigTab(val label: String) {
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
                    ActionSheetItem(
                        text = stringResource(R.string.action_reset_messages),
                        icon = LettaIcons.Refresh,
                        onClick = {
                            showActionSheet = false
                            showResetDialog = true
                        },
                        destructive = true,
                    )
                    ActionSheetItem(
                        text = stringResource(R.string.screen_agents_dialog_delete_title),
                        icon = LettaIcons.Delete,
                        onClick = {
                            showActionSheet = false
                            showDeleteDialog = true
                        },
                        destructive = true,
                    )
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun EditAgentContent(
    state: EditAgentUiState,
    llmModels: List<LlmModel>,
    embeddingModels: List<EmbeddingModel>,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onEmbeddingChange: (String) -> Unit,
    onLoadModels: () -> Unit,
    onBlockValueChange: (String, String) -> Unit,
    onBlockDescriptionChange: (String, String) -> Unit,
    onBlockLimitChange: (String, Int?) -> Unit,
    onAddBlock: (String, String, String, Int?) -> Unit,
    onAttachExistingBlock: (String) -> Unit,
    onAttachExistingBlocks: (List<String>) -> Unit,
    onDeleteBlock: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onAttachTool: (String) -> Unit,
    onAttachTools: (List<String>) -> Unit,
    onDetachTool: (String) -> Unit,
    onToolRulesJsonChange: (String) -> Unit,
    onAddAgentSecret: () -> Unit,
    onAgentSecretKeyChange: (Int, String) -> Unit,
    onAgentSecretValueChange: (Int, String) -> Unit,
    onRemoveAgentSecret: (Int) -> Unit,
    onAddToolEnvironmentVariable: () -> Unit,
    onToolEnvironmentVariableKeyChange: (Int, String) -> Unit,
    onToolEnvironmentVariableValueChange: (Int, String) -> Unit,
    onRemoveToolEnvironmentVariable: (Int) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onProviderTypeChange: (String) -> Unit,
    onTemperatureChange: (Float) -> Unit,
    onMaxOutputTokensChange: (Int) -> Unit,
    onParallelToolCallsChange: (Boolean) -> Unit,
    onModelProviderNameChange: (String) -> Unit,
    onModelProviderCategoryChange: (String) -> Unit,
    onModelEnableReasonerChange: (Boolean) -> Unit,
    onModelReasoningEffortChange: (String) -> Unit,
    onModelMaxReasoningTokensChange: (String) -> Unit,
    onModelReasoningJsonChange: (String) -> Unit,
    onModelFrequencyPenaltyChange: (String) -> Unit,
    onModelVerbosityChange: (String) -> Unit,
    onModelStrictToolCallingChange: (Boolean) -> Unit,
    onModelResponseFormatJsonChange: (String) -> Unit,
    onModelResponseSchemaJsonChange: (String) -> Unit,
    onModelThinkingConfigJsonChange: (String) -> Unit,
    onModelPutInnerThoughtsInKwargsChange: (Boolean) -> Unit,
    onModelToolCallParserChange: (String) -> Unit,
    onModelAnthropicEffortChange: (String) -> Unit,
    onContextWindowChange: (Int) -> Unit,
    onEnableSleeptimeChange: (Boolean) -> Unit,
    onSummarizationPromptChange: (String) -> Unit,
    onCompactionClipCharsChange: (Int) -> Unit,
    onSlidingWindowPercentageChange: (Float) -> Unit,
    onPromptAcknowledgementChange: (Boolean) -> Unit,
    onCompactionModeChange: (String) -> Unit,
    onCompactionModelChange: (String) -> Unit,
    onCompactionModelSettingsJsonChange: (String) -> Unit,
    onClientModeEnabledChange: (Boolean) -> Unit,
    onClientModeBaseUrlChange: (String) -> Unit,
    onClientModeApiKeyChange: (String) -> Unit,
    onTestClientModeConnection: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarDispatcher.current
    var showToolPicker by remember { mutableStateOf(false) }
    var showAddBlockDialog by remember { mutableStateOf(false) }
    var showAttachBlockDialog by remember { mutableStateOf(false) }
    var selectedTool by remember { mutableStateOf<Tool?>(null) }
    var showLlmPicker by remember { mutableStateOf(false) }
    var showEmbeddingPicker by remember { mutableStateOf(false) }
    var showCompactionModelPicker by remember { mutableStateOf(false) }
    val embeddingDropdownModels = remember(embeddingModels) {
        embeddingModels.map {
            LlmModel(
                id = it.id,
                name = it.displayName,
                handle = it.handle ?: it.embeddingModel,
                providerType = it.providerType,
            )
        }
    }
    val selectedLlmModel = remember(state.model, llmModels) {
        llmModels.firstOrNull { model ->
            model.handle.equals(state.model, ignoreCase = true) ||
                model.name.equals(state.model, ignoreCase = true) ||
                model.displayName.equals(state.model, ignoreCase = true)
        }
    }
    val selectedEmbeddingModel = remember(state.embedding, embeddingDropdownModels) {
        embeddingDropdownModels.firstOrNull { model ->
            model.handle.equals(state.embedding, ignoreCase = true) ||
                model.name.equals(state.embedding, ignoreCase = true) ||
                model.displayName.equals(state.embedding, ignoreCase = true)
        }
    }
    val selectedCompactionModel = remember(state.compactionModel, llmModels) {
        llmModels.firstOrNull { model ->
            model.handle.equals(state.compactionModel, ignoreCase = true) ||
                model.name.equals(state.compactionModel, ignoreCase = true) ||
                model.displayName.equals(state.compactionModel, ignoreCase = true)
        }
    }
    val maxContextWindow = selectedLlmModel?.contextWindow?.takeIf { it > 0 }
        ?: state.agent?.llmConfig?.contextWindow?.takeIf { it > 0 }
        ?: state.agent?.contextWindowLimit?.takeIf { it > 0 }

    var selectedTab by rememberSaveable { mutableStateOf(EditAgentConfigTab.Basics) }
    val tabs = remember { EditAgentConfigTab.entries.toList() }
    var showSectionPicker by rememberSaveable { mutableStateOf(false) }
    val sectionPickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(maxContextWindow, state.contextWindow) {
        if (maxContextWindow != null && state.contextWindow > maxContextWindow) {
            onContextWindowChange(maxContextWindow)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(EditAgentTestTags.CONTENT_LIST),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Header ──
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.name.ifBlank { stringResource(R.string.screen_agent_edit_default_name) },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = state.agentId,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.agentType.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.common_type) + ": ${state.agentType}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.common_id), state.agentId))
                    snackbar.dispatch(context.getString(R.string.screen_agent_edit_agent_id_copied))
                }) {
                    Icon(LettaIcons.Copy, contentDescription = stringResource(R.string.screen_agent_edit_copy_agent_id))
                }
            }
        }

        // letta-mobile-qfn9: PrimaryTabRow with 6 fixed-width tabs wraps
        // text mid-word on 360–411dp screens (Basic\ns, Mode\ls, Runti\nme).
        // Replaced with a single-line section selector that opens a modal
        // bottom sheet listing every section with the same validation
        // warning marker the tabs previously showed.
        item(key = "tabs") {
            Surface(
                onClick = { showSectionPicker = true },
                shape = LettaCardDefaults.listShape,
                color = LettaCardDefaults.listContainerColor,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(EditAgentTestTags.SECTION_PICKER_TRIGGER),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Section",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val activeHasWarning = selectedTab.hasValidationWarning(state)
                        Text(
                            text = if (activeHasWarning) "${selectedTab.label} •" else selectedTab.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (activeHasWarning) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    // letta-mobile-qfn9: aggregate validation indicator. When
                    // a section other than the active one has a warning, the
                    // user could previously see the '•' on the affected tab.
                    // Surface that via a label here so warnings remain visible
                    // even when the affected section isn't selected.
                    val otherWarnings = tabs.count { it != selectedTab && it.hasValidationWarning(state) }
                    if (otherWarnings > 0) {
                        Text(
                            text = "$otherWarnings • ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Icon(
                        LettaIcons.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (selectedTab == EditAgentConfigTab.Basics) {
            // ── Identity ──
            item(key = "identity") {
                CardGroup(title = { Text("Identity") }) {
                    item(
                        headlineContent = {
                            OutlinedTextField(
                                value = state.name,
                                onValueChange = onNameChange,
                                label = { Text(stringResource(R.string.common_name)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            OutlinedTextField(
                                value = state.description,
                                onValueChange = onDescriptionChange,
                                label = { Text(stringResource(R.string.common_description)) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                            )
                        },
                    )
                }
            }

            // ── System Prompt ──
            item(key = "system_prompt") {
                CardGroup(title = { Text(stringResource(R.string.common_system_prompt) + " (${state.systemPrompt.length} chars)") }) {
                    item(
                        headlineContent = {
                            OutlinedTextField(
                                value = state.systemPrompt,
                                onValueChange = onSystemPromptChange,
                                label = { Text(stringResource(R.string.common_system_prompt), style = MaterialTheme.typography.bodySmall) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 5,
                                textStyle = MaterialTheme.typography.bodySmall,
                            )
                        },
                    )
                }
            }

            // ── Tags ──
            item(key = "tags") {
                var newTag by remember { mutableStateOf("") }
                CardGroup(title = { Text(stringResource(R.string.common_tags)) }) {
                    if (state.tags.isNotEmpty()) {
                        item(
                            headlineContent = {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    state.tags.forEach { tag ->
                                        InputChip(
                                            selected = false,
                                            onClick = { onRemoveTag(tag) },
                                            label = { Text(tag) },
                                            trailingIcon = {
                                                Icon(
                                                    LettaIcons.Close,
                                                    contentDescription = stringResource(R.string.screen_agent_edit_remove_tag),
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                    item(
                        headlineContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = newTag,
                                    onValueChange = { newTag = it },
                                    label = { Text(stringResource(R.string.screen_agent_edit_new_tag)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                FilledTonalButton(
                                    onClick = { onAddTag(newTag); newTag = "" },
                                    enabled = newTag.isNotBlank(),
                                ) { Text(stringResource(R.string.action_add)) }
                            }
                        },
                    )
                }
            }
        }

        if (selectedTab == EditAgentConfigTab.Models) {
            // ── Model ──
            item(key = "model") {
                CardGroup(title = { Text(stringResource(R.string.common_model)) }) {
                    item(
                        headlineContent = {
                            SearchPickerField(
                                label = stringResource(R.string.common_model),
                                title = selectedLlmModel?.displayName ?: state.model,
                                supporting = selectedLlmModel?.handle ?: state.model,
                                onClick = {
                                    onLoadModels()
                                    showLlmPicker = true
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            SearchPickerField(
                                label = stringResource(R.string.screen_agent_edit_embedding_model),
                                title = selectedEmbeddingModel?.displayName ?: state.embedding,
                                supporting = selectedEmbeddingModel?.handle ?: state.embedding,
                                onClick = {
                                    onLoadModels()
                                    showEmbeddingPicker = true
                                },
                            )
                        },
                    )
                }
            }

            // ── LLM Configuration ──
            item(key = "llm_config") {
                CardGroup(title = { Text(stringResource(R.string.screen_agent_edit_llm_configuration)) }) {
                    item(
                        headlineContent = {
                            OutlinedTextField(
                                value = state.providerType,
                                onValueChange = onProviderTypeChange,
                                label = { Text(stringResource(R.string.screen_agent_edit_provider_type)) },
                                placeholder = { Text(stringResource(R.string.screen_agents_create_provider_placeholder)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        },
                    )
                    item(
                        headlineContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    stringResource(R.string.screen_agent_edit_temperature_value, state.temperature),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Slider(
                                    value = state.temperature,
                                    onValueChange = onTemperatureChange,
                                    valueRange = 0f..2f,
                                    steps = 39,
                                )
                            }
                        },
                    )
                    item(
                        headlineContent = {
                            ContextWindowLimitSlider(
                                value = state.contextWindow,
                                maxValue = maxContextWindow,
                                onValueChange = onContextWindowChange,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.common_parallel_tool_calls)) },
                        trailingContent = {
                            Switch(checked = state.parallelToolCalls, onCheckedChange = onParallelToolCallsChange)
                        },
                    )
                    item(
                        headlineContent = {
                            OutlinedTextField(
                                value = state.maxOutputTokens.toString(),
                                onValueChange = { it.toIntOrNull()?.let(onMaxOutputTokensChange) },
                                label = { Text(stringResource(R.string.common_max_output_tokens)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.common_enable_sleeptime)) },
                        trailingContent = {
                            Switch(checked = state.enableSleeptime, onCheckedChange = onEnableSleeptimeChange)
                        },
                    )
                }
            }
        }

        if (selectedTab == EditAgentConfigTab.Advanced) {
            item(key = "primary_model_advanced") {
                PrimaryModelAdvancedSection(
                    state = state,
                    onModelProviderNameChange = onModelProviderNameChange,
                    onModelProviderCategoryChange = onModelProviderCategoryChange,
                    onModelEnableReasonerChange = onModelEnableReasonerChange,
                    onModelReasoningEffortChange = onModelReasoningEffortChange,
                    onModelMaxReasoningTokensChange = onModelMaxReasoningTokensChange,
                    onModelReasoningJsonChange = onModelReasoningJsonChange,
                    onModelFrequencyPenaltyChange = onModelFrequencyPenaltyChange,
                    onModelVerbosityChange = onModelVerbosityChange,
                    onModelStrictToolCallingChange = onModelStrictToolCallingChange,
                    onModelResponseFormatJsonChange = onModelResponseFormatJsonChange,
                    onModelResponseSchemaJsonChange = onModelResponseSchemaJsonChange,
                    onModelThinkingConfigJsonChange = onModelThinkingConfigJsonChange,
                    onModelPutInnerThoughtsInKwargsChange = onModelPutInnerThoughtsInKwargsChange,
                    onModelToolCallParserChange = onModelToolCallParserChange,
                    onModelAnthropicEffortChange = onModelAnthropicEffortChange,
                )
            }
        }

        if (selectedTab == EditAgentConfigTab.Memory) {
            item(key = "advanced_compaction") {
                AdvancedCompactionSection(
                    state = state,
                    onSummarizationPromptChange = onSummarizationPromptChange,
                    onCompactionClipCharsChange = onCompactionClipCharsChange,
                    onSlidingWindowPercentageChange = onSlidingWindowPercentageChange,
                    onPromptAcknowledgementChange = onPromptAcknowledgementChange,
                    onCompactionModeChange = onCompactionModeChange,
                    onCompactionModelChange = onCompactionModelChange,
                    onCompactionModelSettingsJsonChange = onCompactionModelSettingsJsonChange,
                    compactionModelTitle = selectedCompactionModel?.displayName
                        ?: state.compactionModel.ifBlank { stringResource(R.string.screen_agent_edit_compaction_model_default) },
                    compactionModelSupporting = selectedCompactionModel?.handle ?: state.compactionModel,
                    onOpenCompactionModelPicker = { showCompactionModelPicker = true },
                )
            }

            // Memory Blocks
            item(key = "memory_blocks") {
                CardGroup(title = { Text("${stringResource(R.string.screen_agent_memory_blocks_section)} (${state.blocks.size})") }) {
                    state.blocks.forEach { block ->
                        item(
                            headlineContent = {
                                MemoryBlockItem(
                                    block = block,
                                    onValueChange = { onBlockValueChange(block.label, it) },
                                    onDescriptionChange = { onBlockDescriptionChange(block.label, it) },
                                    onLimitChange = { onBlockLimitChange(block.label, it) },
                                    onDelete = { onDeleteBlock(block.id) },
                                )
                            },
                        )
                    }
                    item(
                        onClick = { showAddBlockDialog = true },
                        headlineContent = { Text(stringResource(R.string.screen_agent_edit_add_memory_block)) },
                        leadingContent = { Icon(LettaIcons.Add, contentDescription = null, modifier = Modifier.size(LettaIconSizing.Toolbar)) },
                    )
                    item(
                        onClick = { showAttachBlockDialog = true },
                        headlineContent = { Text(stringResource(R.string.screen_agent_edit_attach_existing_block)) },
                        leadingContent = { Icon(LettaIcons.Add, contentDescription = null, modifier = Modifier.size(LettaIconSizing.Toolbar)) },
                    )
                }
            }
        }

        if (selectedTab == EditAgentConfigTab.Tools) {
            item(key = "tool_environment") {
                ToolEnvironmentSection(
                    state = state,
                    onAddAgentSecret = onAddAgentSecret,
                    onAgentSecretKeyChange = onAgentSecretKeyChange,
                    onAgentSecretValueChange = onAgentSecretValueChange,
                    onRemoveAgentSecret = onRemoveAgentSecret,
                    onAddToolEnvironmentVariable = onAddToolEnvironmentVariable,
                    onToolEnvironmentVariableKeyChange = onToolEnvironmentVariableKeyChange,
                    onToolEnvironmentVariableValueChange = onToolEnvironmentVariableValueChange,
                    onRemoveToolEnvironmentVariable = onRemoveToolEnvironmentVariable,
                )
            }

            // ── Tools ──
            item(key = "tools") {
                CardGroup(title = { Text(stringResource(R.string.common_tools) + " (${state.attachedTools.size})") }) {
                    if (state.attachedTools.isEmpty()) {
                        item(
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.screen_tools_empty_attached),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    } else {
                        state.attachedTools.forEach { tool ->
                            item(
                                onClick = { selectedTool = tool },
                                headlineContent = {
                                    Text(tool.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = tool.description?.let { desc ->
                                    {
                                        Text(
                                            text = desc,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                leadingContent = {
                                    Icon(
                                        LettaIcons.Tool,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                            )
                        }
                    }
                    item(
                        onClick = { showToolPicker = true },
                        headlineContent = { Text(stringResource(R.string.screen_agent_edit_attach_tools)) },
                        leadingContent = { Icon(LettaIcons.Add, contentDescription = null, modifier = Modifier.size(LettaIconSizing.Toolbar)) },
                    )
                }
            }

            item(key = "tool_rules") {
                ToolRulesSection(
                    state = state,
                    onToolRulesJsonChange = onToolRulesJsonChange,
                )
            }
        }

        if (selectedTab == EditAgentConfigTab.Runtime) {
            item(key = "client_mode") {
                EditAgentClientModeSection(
                    state = state,
                    onClientModeEnabledChange = onClientModeEnabledChange,
                    onClientModeBaseUrlChange = onClientModeBaseUrlChange,
                    onClientModeApiKeyChange = onClientModeApiKeyChange,
                    onTestClientModeConnection = onTestClientModeConnection,
                )
            }
        }
    }

    // ── Dialogs ──

    selectedTool?.let { tool ->
        ToolDetailDialog(
            tool = tool,
            onDismiss = { selectedTool = null },
        )
    }

    if (showAddBlockDialog) {
        AddBlockDialog(
            onDismiss = { showAddBlockDialog = false },
            onAdd = { label, value, description, limit ->
                onAddBlock(label, value, description, limit)
                showAddBlockDialog = false
            },
        )
    }

    if (showAttachBlockDialog) {
        FullScreenBlockPickerDialog(
            excludedBlockIds = state.blocks.map { it.id },
            onDismiss = { showAttachBlockDialog = false },
            onConfirm = { selectedIds ->
                onAttachExistingBlocks(selectedIds)
                showAttachBlockDialog = false
            },
        )
    }

    if (showToolPicker) {
        FullScreenToolPickerDialog(
            tools = state.availableTools.filter { candidate ->
                state.attachedTools.none { attached -> attached.id == candidate.id }
            },
            selectedToolIds = emptyList(),
            title = stringResource(R.string.screen_agent_edit_attach_tools),
            onDismiss = { showToolPicker = false },
            onConfirm = { selectedIds ->
                onAttachTools(selectedIds)
                showToolPicker = false
            },
        )
    }

    if (showLlmPicker) {
        FullScreenModelPickerDialog(
            title = stringResource(R.string.common_model),
            placeholder = stringResource(R.string.screen_models_search_hint),
            models = llmModels,
            selectedValue = state.model,
            onDismiss = { showLlmPicker = false },
            onModelSelected = {
                onModelChange(it)
                showLlmPicker = false
            },
        )
    }

    if (showEmbeddingPicker) {
        FullScreenModelPickerDialog(
            title = stringResource(R.string.screen_agent_edit_embedding_model),
            placeholder = stringResource(R.string.screen_models_search_hint),
            models = embeddingDropdownModels,
            selectedValue = state.embedding,
            onDismiss = { showEmbeddingPicker = false },
            onModelSelected = {
                onEmbeddingChange(it)
                showEmbeddingPicker = false
            },
        )
    }

    if (showCompactionModelPicker) {
        FullScreenModelPickerDialog(
            title = stringResource(R.string.screen_agent_edit_compaction_model),
            placeholder = stringResource(R.string.screen_models_search_hint),
            models = llmModels,
            selectedValue = state.compactionModel,
            onDismiss = { showCompactionModelPicker = false },
            onModelSelected = {
                onCompactionModelChange(it)
                showCompactionModelPicker = false
            },
        )
    }

    // letta-mobile-qfn9: section picker bottom sheet. Replaces the wrapping
    // PrimaryTabRow with a one-line trigger and a sheet that lists every
    // section. Same validation warning marker ('•') applied per row.
    if (showSectionPicker) {
        ModalBottomSheet(
            onDismissRequest = { showSectionPicker = false },
            sheetState = sectionPickerSheetState,
            modifier = Modifier.testTag(EditAgentTestTags.SECTION_PICKER_SHEET),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Sections",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                tabs.forEach { tab ->
                    val hasWarning = tab.hasValidationWarning(state)
                    val isSelected = tab == selectedTab
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedTab = tab
                                showSectionPicker = false
                            }
                            .testTag(EditAgentTestTags.tab(tab.label)),
                        headlineContent = {
                            Text(
                                text = if (hasWarning) "${tab.label} •" else tab.label,
                                color = when {
                                    hasWarning -> MaterialTheme.colorScheme.error
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        leadingContent = {
                            if (isSelected) {
                                Icon(
                                    LettaIcons.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Spacer(modifier = Modifier.size(24.dp))
                            }
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            },
                        ),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

private fun EditAgentConfigTab.hasValidationWarning(state: EditAgentUiState): Boolean = when (this) {
    EditAgentConfigTab.Advanced -> listOf(
        state.modelReasoningJson,
        state.modelResponseFormatJson,
        state.modelResponseSchemaJson,
        state.modelThinkingConfigJson,
    ).any(::isInvalidJsonObjectIfPresent) ||
        isInvalidWholeNumberIfPresent(state.modelMaxReasoningTokens) ||
        isInvalidNumberIfPresent(state.modelFrequencyPenalty)
    EditAgentConfigTab.Memory -> isInvalidJsonObjectIfPresent(state.compactionModelSettingsJson)
    EditAgentConfigTab.Tools -> isInvalidJsonArrayIfPresent(state.toolRulesJson) ||
        state.agentSecrets.hasDuplicateKeys() ||
        state.toolEnvironmentVariables.hasDuplicateKeys()
    EditAgentConfigTab.Basics,
    EditAgentConfigTab.Models,
    EditAgentConfigTab.Runtime,
    -> false
}

private fun isInvalidJsonObjectIfPresent(value: String): Boolean {
    if (value.isBlank()) return false
    return !runCatching { Json.parseToJsonElement(value) is JsonObject }.getOrDefault(false)
}

private fun isInvalidJsonArrayIfPresent(value: String): Boolean {
    if (value.isBlank()) return false
    return !runCatching { Json.parseToJsonElement(value) is JsonArray }.getOrDefault(false)
}

private fun isInvalidWholeNumberIfPresent(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return false
    return trimmed.toIntOrNull()?.takeIf { it >= 0 } == null
}

private fun isInvalidNumberIfPresent(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return false
    return trimmed.toDoubleOrNull() == null
}

private fun List<EditableAgentEnvironmentVariable>.hasDuplicateKeys(): Boolean {
    val keys = map { it.key.trim() }.filter { it.isNotBlank() }
    return keys.distinct().size != keys.size
}

@Composable
private fun ToolRulesSection(
    state: EditAgentUiState,
    onToolRulesJsonChange: (String) -> Unit,
) {
    CardGroup(title = { Text(stringResource(R.string.screen_agent_edit_tool_rules_section)) }) {
        item(
            headlineContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.screen_agent_edit_tool_rules_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.attachedTools.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.screen_agent_edit_tool_rules_attached_tools,
                                state.attachedTools.joinToString { it.name },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = state.toolRulesJson,
                        onValueChange = onToolRulesJsonChange,
                        label = { Text(stringResource(R.string.screen_agent_edit_tool_rules_json)) },
                        placeholder = { Text(stringResource(R.string.screen_agent_edit_tool_rules_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            },
        )
    }
}

@Composable
private fun ToolEnvironmentSection(
    state: EditAgentUiState,
    onAddAgentSecret: () -> Unit,
    onAgentSecretKeyChange: (Int, String) -> Unit,
    onAgentSecretValueChange: (Int, String) -> Unit,
    onRemoveAgentSecret: (Int) -> Unit,
    onAddToolEnvironmentVariable: () -> Unit,
    onToolEnvironmentVariableKeyChange: (Int, String) -> Unit,
    onToolEnvironmentVariableValueChange: (Int, String) -> Unit,
    onRemoveToolEnvironmentVariable: (Int) -> Unit,
) {
    CardGroup(title = { Text(stringResource(R.string.screen_agent_edit_tool_environment)) }) {
        item(
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_secrets)) },
            supportingContent = { Text(stringResource(R.string.screen_agent_edit_secrets_helper)) },
        )
        state.agentSecrets.forEachIndexed { index, variable ->
            item(
                headlineContent = {
                    EnvironmentVariableEditorRow(
                        variable = variable,
                        valueLabel = stringResource(R.string.screen_agent_edit_secret_value),
                        maskValue = true,
                        onKeyChange = { onAgentSecretKeyChange(index, it) },
                        onValueChange = { onAgentSecretValueChange(index, it) },
                        onRemove = { onRemoveAgentSecret(index) },
                    )
                },
            )
        }
        item(
            onClick = onAddAgentSecret,
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_add_secret)) },
            leadingContent = {
                Icon(
                    LettaIcons.Add,
                    contentDescription = null,
                    modifier = Modifier.size(LettaIconSizing.Toolbar),
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_tool_environment_variables)) },
            supportingContent = { Text(stringResource(R.string.screen_agent_edit_tool_environment_variables_helper)) },
        )
        state.toolEnvironmentVariables.forEachIndexed { index, variable ->
            item(
                headlineContent = {
                    EnvironmentVariableEditorRow(
                        variable = variable,
                        valueLabel = stringResource(R.string.screen_agent_edit_environment_value),
                        maskValue = false,
                        onKeyChange = { onToolEnvironmentVariableKeyChange(index, it) },
                        onValueChange = { onToolEnvironmentVariableValueChange(index, it) },
                        onRemove = { onRemoveToolEnvironmentVariable(index) },
                    )
                },
            )
        }
        item(
            onClick = onAddToolEnvironmentVariable,
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_add_tool_environment_variable)) },
            leadingContent = {
                Icon(
                    LettaIcons.Add,
                    contentDescription = null,
                    modifier = Modifier.size(LettaIconSizing.Toolbar),
                )
            },
        )
    }
}

@Composable
private fun EnvironmentVariableEditorRow(
    variable: EditableAgentEnvironmentVariable,
    valueLabel: String,
    maskValue: Boolean,
    onKeyChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val hasHiddenStoredValue = variable.hasStoredValue && variable.originalValue == null && variable.value.isBlank()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = variable.key.ifBlank { stringResource(R.string.screen_agent_edit_environment_variable) },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasHiddenStoredValue) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.screen_agent_edit_value_stored)) },
                    enabled = false,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    LettaIcons.Close,
                    contentDescription = stringResource(R.string.action_remove),
                )
            }
        }
        OutlinedTextField(
            value = variable.key,
            onValueChange = onKeyChange,
            label = { Text(stringResource(R.string.screen_agent_edit_environment_key)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = variable.value,
            onValueChange = onValueChange,
            label = { Text(valueLabel) },
            placeholder = {
                if (hasHiddenStoredValue) {
                    Text(stringResource(R.string.screen_agent_edit_environment_value_hidden_placeholder))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (maskValue) PasswordVisualTransformation() else VisualTransformation.None,
        )
    }
}

@Composable
private fun PrimaryModelAdvancedSection(
    state: EditAgentUiState,
    onModelProviderNameChange: (String) -> Unit,
    onModelProviderCategoryChange: (String) -> Unit,
    onModelEnableReasonerChange: (Boolean) -> Unit,
    onModelReasoningEffortChange: (String) -> Unit,
    onModelMaxReasoningTokensChange: (String) -> Unit,
    onModelReasoningJsonChange: (String) -> Unit,
    onModelFrequencyPenaltyChange: (String) -> Unit,
    onModelVerbosityChange: (String) -> Unit,
    onModelStrictToolCallingChange: (Boolean) -> Unit,
    onModelResponseFormatJsonChange: (String) -> Unit,
    onModelResponseSchemaJsonChange: (String) -> Unit,
    onModelThinkingConfigJsonChange: (String) -> Unit,
    onModelPutInnerThoughtsInKwargsChange: (Boolean) -> Unit,
    onModelToolCallParserChange: (String) -> Unit,
    onModelAnthropicEffortChange: (String) -> Unit,
) {
    CardGroup(title = { Text(stringResource(R.string.screen_agent_edit_primary_model_advanced)) }) {
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelProviderName,
                    onValueChange = onModelProviderNameChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_model_provider_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelProviderCategory,
                    onValueChange = onModelProviderCategoryChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_model_provider_category)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_enable_reasoner)) },
            trailingContent = {
                Switch(
                    checked = state.modelEnableReasoner,
                    onCheckedChange = onModelEnableReasonerChange,
                )
            },
        )
        item(
            headlineContent = {
                AdvancedModelDropdown(
                    label = stringResource(R.string.screen_agent_edit_reasoning_effort),
                    selectedValue = state.modelReasoningEffort,
                    options = reasoningEffortOptions,
                    onValueChange = onModelReasoningEffortChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelMaxReasoningTokens,
                    onValueChange = onModelMaxReasoningTokensChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_max_reasoning_tokens)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelReasoningJson,
                    onValueChange = onModelReasoningJsonChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_reasoning_json)) },
                    placeholder = { Text(stringResource(R.string.screen_agent_edit_json_object_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelFrequencyPenalty,
                    onValueChange = onModelFrequencyPenaltyChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_frequency_penalty)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
        )
        item(
            headlineContent = {
                AdvancedModelDropdown(
                    label = stringResource(R.string.screen_agent_edit_verbosity),
                    selectedValue = state.modelVerbosity,
                    options = verbosityOptions,
                    onValueChange = onModelVerbosityChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_strict_tool_calling)) },
            trailingContent = {
                Switch(
                    checked = state.modelStrictToolCalling,
                    onCheckedChange = onModelStrictToolCallingChange,
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelResponseFormatJson,
                    onValueChange = onModelResponseFormatJsonChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_response_format_json)) },
                    placeholder = { Text(stringResource(R.string.screen_agent_edit_json_object_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelResponseSchemaJson,
                    onValueChange = onModelResponseSchemaJsonChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_response_schema_json)) },
                    placeholder = { Text(stringResource(R.string.screen_agent_edit_json_object_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelThinkingConfigJson,
                    onValueChange = onModelThinkingConfigJsonChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_thinking_config_json)) },
                    placeholder = { Text(stringResource(R.string.screen_agent_edit_json_object_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_put_inner_thoughts_in_kwargs)) },
            trailingContent = {
                Switch(
                    checked = state.modelPutInnerThoughtsInKwargs,
                    onCheckedChange = onModelPutInnerThoughtsInKwargsChange,
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.modelToolCallParser,
                    onValueChange = onModelToolCallParserChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_tool_call_parser)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
        )
        item(
            headlineContent = {
                AdvancedModelDropdown(
                    label = stringResource(R.string.screen_agent_edit_anthropic_effort),
                    selectedValue = state.modelAnthropicEffort,
                    options = anthropicEffortOptions,
                    onValueChange = onModelAnthropicEffortChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedModelDropdown(
    label: String,
    selectedValue: String,
    options: List<AdvancedModelOption>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.firstOrNull { it.value == selectedValue } ?: options.first()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = stringResource(selectedOption.labelRes),
            onValueChange = {},
            label = { Text(label) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = {
                        onValueChange(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AdvancedCompactionSection(
    state: EditAgentUiState,
    onSummarizationPromptChange: (String) -> Unit,
    onCompactionClipCharsChange: (Int) -> Unit,
    onSlidingWindowPercentageChange: (Float) -> Unit,
    onPromptAcknowledgementChange: (Boolean) -> Unit,
    onCompactionModeChange: (String) -> Unit,
    onCompactionModelChange: (String) -> Unit,
    onCompactionModelSettingsJsonChange: (String) -> Unit,
    compactionModelTitle: String,
    compactionModelSupporting: String,
    onOpenCompactionModelPicker: () -> Unit,
) {
    CardGroup(title = { Text(stringResource(R.string.screen_agent_edit_advanced_configuration)) }) {
        item(
            headlineContent = {
                CompactionModeDropdown(
                    selectedMode = state.compactionMode,
                    onModeChange = onCompactionModeChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
        item(
            headlineContent = {
                SearchPickerField(
                    label = stringResource(R.string.screen_agent_edit_compaction_model),
                    title = compactionModelTitle,
                    supporting = compactionModelSupporting,
                    onClick = onOpenCompactionModelPicker,
                )
            },
        )
        if (state.compactionModel.isNotBlank()) {
            item(
                onClick = { onCompactionModelChange("") },
                headlineContent = { Text(stringResource(R.string.screen_agent_edit_compaction_model_use_default)) },
                leadingContent = {
                    Icon(
                        LettaIcons.Close,
                        contentDescription = null,
                        modifier = Modifier.size(LettaIconSizing.Toolbar),
                    )
                },
            )
        }
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.compactionModelSettingsJson,
                    onValueChange = onCompactionModelSettingsJsonChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_compaction_model_settings)) },
                    placeholder = { Text(stringResource(R.string.screen_agent_edit_compaction_model_settings_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.summarizationPrompt,
                    onValueChange = onSummarizationPromptChange,
                    label = { Text(stringResource(R.string.screen_agent_edit_summarization_prompt)) },
                    placeholder = { Text(stringResource(R.string.screen_agent_edit_summarization_prompt_default)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            },
        )
        item(
            headlineContent = {
                OutlinedTextField(
                    value = state.compactionClipChars.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let(onCompactionClipCharsChange)
                    },
                    label = { Text(stringResource(R.string.screen_agent_edit_clip_chars)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
        )
        item(
            headlineContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(
                            R.string.screen_agent_edit_sliding_window_percentage,
                            formatCompactionPercentage(state.slidingWindowPercentage),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Slider(
                            value = state.slidingWindowPercentage.coerceIn(0f, 1f),
                            onValueChange = onSlidingWindowPercentageChange,
                            valueRange = 0f..1f,
                            steps = 19,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = formatCompactionPercentage(state.slidingWindowPercentage),
                            onValueChange = { value ->
                                value.toFloatOrNull()?.let(onSlidingWindowPercentageChange)
                            },
                            modifier = Modifier.width(92.dp),
                            singleLine = true,
                        )
                    }
                }
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.screen_agent_edit_prompt_acknowledgement)) },
            trailingContent = {
                Switch(
                    checked = state.promptAcknowledgement,
                    onCheckedChange = onPromptAcknowledgementChange,
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactionModeDropdown(
    selectedMode: String,
    onModeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = compactionModeOptions.firstOrNull { it.value == selectedMode }
        ?: compactionModeOptions.first()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = stringResource(selectedOption.labelRes),
            onValueChange = {},
            label = { Text(stringResource(R.string.screen_agent_edit_compaction_mode)) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            compactionModeOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = {
                        onModeChange(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ContextWindowLimitSlider(
    value: Int,
    maxValue: Int?,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.common_context_window),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (maxValue != null && maxValue > 0) {
            val coercedValue = value.coerceIn(0, maxValue)
            val percentage = ((coercedValue.toFloat() / maxValue.toFloat()) * 100f).roundToInt()
            Text(
                text = stringResource(
                    R.string.screen_chat_context_window_usage,
                    formatEditAgentNumber(coercedValue),
                    formatEditAgentNumber(maxValue),
                    percentage,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = coercedValue.toFloat(),
                onValueChange = { sliderValue ->
                    onValueChange(snapContextWindowValue(sliderValue, maxValue))
                },
                valueRange = 0f..maxValue.toFloat(),
            )
        } else {
            Text(
                text = if (value > 0) {
                    stringResource(R.string.screen_agent_edit_context_window, value)
                } else {
                    stringResource(R.string.screen_agent_edit_context_window_unavailable)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatCompactionPercentage(value: Float): String {
    return String.format(Locale.US, "%.2f", value.coerceIn(0f, 1f))
        .trimEnd('0')
        .trimEnd('.')
}

private fun snapContextWindowValue(value: Float, maxValue: Int): Int {
    if (maxValue <= 1_000) return value.roundToInt().coerceIn(0, maxValue)
    return (value / 1_000f).roundToInt()
        .times(1_000)
        .coerceIn(0, maxValue)
}

private fun formatEditAgentNumber(value: Int): String = String.format(Locale.US, "%,d", value)

@Composable
private fun SearchPickerField(
    label: String,
    title: String,
    supporting: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = LettaCardDefaults.listCardColors(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 84.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 36.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (supporting.isNotBlank() && supporting != title) supporting else " ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = LettaIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenModelPickerDialog(
    title: String,
    placeholder: String,
    models: List<LlmModel>,
    selectedValue: String,
    onDismiss: () -> Unit,
    onModelSelected: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var query by rememberSaveable { mutableStateOf("") }
        var searchExpanded by rememberSaveable { mutableStateOf(true) }
        val groupedModels = remember(models, query) {
            val filtered = if (query.isBlank()) {
                models
            } else {
                models.filter { model ->
                    model.displayName.contains(query, ignoreCase = true) ||
                        model.providerType.contains(query, ignoreCase = true) ||
                        (model.handle?.contains(query, ignoreCase = true) == true)
                }
            }
            filtered
                .groupBy { it.providerType.ifBlank { "other" } }
                .toSortedMap()
        }
        val sectionState = remember { mutableStateMapOf<String, Boolean>() }

        LaunchedEffect(groupedModels.keys) {
            groupedModels.keys.forEach { key -> sectionState.putIfAbsent(key, true) }
        }

        Scaffold(
            containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
            topBar = {
                LargeFlexibleTopAppBar(
                    title = {
                        ExpandableTitleSearch(
                            query = query,
                            onQueryChange = { query = it },
                            onClear = { query = "" },
                            expanded = searchExpanded,
                            onExpandedChange = { searchExpanded = it },
                            placeholder = placeholder,
                            titleContent = { Text(title) },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(LettaIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    colors = LettaTopBarDefaults.largeTopAppBarColors(),
                )
            },
        ) { paddingValues ->
            if (groupedModels.isEmpty()) {
                EmptyState(
                    icon = LettaIcons.Search,
                    message = stringResource(R.string.screen_models_no_models),
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = paddingValues.calculateTopPadding() + 8.dp,
                        bottom = paddingValues.calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    groupedModels.forEach { (provider, providerModels) ->
                        item(key = "section-$provider") {
                            Accordions(
                                title = provider,
                                subtitle = "${providerModels.size} model${if (providerModels.size == 1) "" else "s"}",
                                expanded = sectionState[provider] ?: true,
                                onExpandedChange = { expanded -> sectionState[provider] = expanded },
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    providerModels.forEach { model ->
                                        val selectionValue = model.handle ?: model.name ?: model.displayName
                                        val isSelected = selectionValue.equals(selectedValue, ignoreCase = true) ||
                                            model.displayName?.equals(selectedValue, ignoreCase = true) == true
                                        ModelPickerCard(
                                            model = model,
                                            selected = isSelected,
                                            onClick = { onModelSelected(selectionValue) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPickerCard(
    model: LlmModel,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = LettaCardDefaults.listCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                LettaCardDefaults.listContainerColor
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected) {
                    Icon(
                        imageVector = LettaIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            model.handle?.let { handle ->
                Text(
                    text = handle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(onClick = {}, enabled = false, label = { Text(model.providerType) })
                model.contextWindow?.let { contextWindow ->
                    Text(
                        text = "${contextWindow / 1000}K ctx",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenToolPickerDialog(
    tools: List<Tool>,
    selectedToolIds: List<String>,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(true) }
    var selection by remember(tools, selectedToolIds) { mutableStateOf(selectedToolIds.toSet()) }
    val filteredTools = remember(tools, query) {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) tools
        else tools.filter { tool ->
            tool.name.lowercase().contains(normalizedQuery) ||
                (tool.description?.lowercase()?.contains(normalizedQuery) == true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
            topBar = {
                LargeFlexibleTopAppBar(
                    title = {
                        ExpandableTitleSearch(
                            query = query,
                            onQueryChange = { query = it },
                            onClear = { query = "" },
                            expanded = searchExpanded,
                            onExpandedChange = { searchExpanded = it },
                            placeholder = stringResource(R.string.screen_models_search_hint),
                            titleContent = { Text(title) },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(LettaIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        TextButton(onClick = { onConfirm(selection.toList()) }) {
                            Text(stringResource(R.string.action_save))
                        }
                    },
                    colors = LettaTopBarDefaults.largeTopAppBarColors(),
                )
            },
        ) { paddingValues ->
            if (filteredTools.isEmpty()) {
                EmptyState(
                    icon = LettaIcons.Search,
                    message = stringResource(R.string.screen_tools_empty_search, query),
                    modifier = Modifier.padding(paddingValues).fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = paddingValues.calculateTopPadding() + 8.dp,
                        bottom = paddingValues.calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredTools, key = { it.id.value }) { tool ->
                        val isSelected = tool.id.value in selection
                        SelectableToolCard(
                            tool = tool,
                            query = query,
                            selected = isSelected,
                            onClick = {
                                selection = if (isSelected) selection - tool.id.value else selection + tool.id.value
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenBlockPickerDialog(
    excludedBlockIds: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    viewModel: BlockLibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(true) }
    var selection by remember(excludedBlockIds) { mutableStateOf(emptySet<String>()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
            topBar = {
                LargeFlexibleTopAppBar(
                    title = {
                        ExpandableTitleSearch(
                            query = query,
                            onQueryChange = { query = it },
                            onClear = { query = "" },
                            expanded = searchExpanded,
                            onExpandedChange = { searchExpanded = it },
                            placeholder = stringResource(R.string.screen_models_search_hint),
                            titleContent = { Text(stringResource(R.string.screen_agent_edit_attach_existing_block)) },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(LettaIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { onConfirm(selection.toList()) },
                            enabled = selection.isNotEmpty(),
                        ) {
                            Text(stringResource(R.string.action_attach))
                        }
                    },
                    colors = LettaTopBarDefaults.largeTopAppBarColors(),
                )
            },
        ) { paddingValues ->
            when (val state = uiState) {
                is UiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .padding(paddingValues)
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            androidx.compose.material3.CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.common_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                is UiState.Error -> ErrorContent(
                    message = state.message,
                    onRetry = { },
                    modifier = Modifier.padding(paddingValues),
                )
                is UiState.Success -> {
                    val availableBlocks = remember(state.data.blocks, excludedBlockIds, query) {
                        val normalizedQuery = query.trim().lowercase()
                        state.data.blocks
                            .filter { it.id !in excludedBlockIds.map { BlockId(it) } }
                            .filter { block ->
                                normalizedQuery.isBlank() ||
                                    (block.label?.lowercase()?.contains(normalizedQuery) == true) ||
                                    (block.description?.lowercase()?.contains(normalizedQuery) == true) ||
                                    block.value.lowercase().contains(normalizedQuery)
                            }
                    }
                    if (availableBlocks.isEmpty()) {
                        EmptyState(
                            icon = LettaIcons.Search,
                            message = stringResource(R.string.screen_blocks_empty_available),
                            modifier = Modifier.padding(paddingValues).fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = paddingValues.calculateTopPadding() + 8.dp,
                                bottom = paddingValues.calculateBottomPadding() + 24.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(availableBlocks, key = { it.id.value }) { block ->
                                val isSelected = block.id.value in selection
                                SelectableBlockCard(
                                    block = block,
                                    query = query,
                                    selected = isSelected,
                                    onClick = {
                                        selection = if (isSelected) selection - block.id.value else selection + block.id.value
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectableToolCard(
    tool: Tool,
    query: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightColors = rememberSearchHighlightColors()
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = LettaCardDefaults.listCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else LettaCardDefaults.listContainerColor,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(checked = selected, onCheckedChange = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = highlightSearchMatches(tool.name, query, highlightColors),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                tool.description?.let { description ->
                    Text(
                        text = highlightSearchMatches(description, query, highlightColors),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectableBlockCard(
    block: Block,
    query: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightColors = rememberSearchHighlightColors()
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = LettaCardDefaults.listCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else LettaCardDefaults.listContainerColor,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(checked = selected, onCheckedChange = null)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = highlightSearchMatches(block.label ?: stringResource(R.string.common_unknown), query, highlightColors),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (block.isTemplate == true) {
                        Text(
                            text = stringResource(R.string.screen_agent_edit_block_template),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                block.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = highlightSearchMatches(description, query, highlightColors),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (block.value.isNotBlank()) {
                    Text(
                        text = highlightSearchMatches(block.value, query, highlightColors),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditAgentClientModeSection(
    state: EditAgentUiState,
    onClientModeEnabledChange: (Boolean) -> Unit,
    onClientModeBaseUrlChange: (String) -> Unit,
    onClientModeApiKeyChange: (String) -> Unit,
    onTestClientModeConnection: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardGroup(title = { Text(stringResource(R.string.screen_settings_client_mode_section)) }) {
            item(
                headlineContent = {
                    FormItem(
                        label = { Text(stringResource(R.string.screen_settings_client_mode_enable)) },
                        description = {
                            Text(stringResource(R.string.screen_settings_client_mode_enable_description))
                        },
                        tail = {
                            Switch(
                                checked = state.clientModeEnabled,
                                onCheckedChange = onClientModeEnabledChange,
                            )
                        },
                    )
                },
            )
        }

        if (state.clientModeEnabled) {
            CardGroup {
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = state.clientModeBaseUrl,
                            onValueChange = onClientModeBaseUrlChange,
                            label = { Text(stringResource(R.string.screen_settings_client_mode_server_url)) },
                            placeholder = {
                                Text(stringResource(R.string.screen_settings_client_mode_server_url_placeholder))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    },
                )
                item(
                    headlineContent = {
                        OutlinedTextField(
                            value = state.clientModeApiKey,
                            onValueChange = onClientModeApiKeyChange,
                            label = { Text(stringResource(R.string.screen_settings_client_mode_api_key)) },
                            placeholder = {
                                Text(stringResource(R.string.screen_settings_client_mode_api_key_placeholder))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.screen_settings_client_mode_api_key_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                item(
                    headlineContent = {
                        FormItem(
                            label = { Text(stringResource(R.string.screen_settings_client_mode_test_connection)) },
                            description = {
                                val statusText = clientModeConnectionStatusText(state.clientModeConnectionState)
                                if (statusText != null) {
                                    Text(
                                        text = statusText,
                                        color = clientModeConnectionStatusColor(state.clientModeConnectionState),
                                    )
                                } else {
                                    Text(stringResource(R.string.screen_settings_client_mode_test_connection_helper))
                                }
                            },
                            tail = {
                                OutlinedButton(
                                    onClick = onTestClientModeConnection,
                                    enabled = state.clientModeConnectionState !is ClientModeConnectionState.Testing &&
                                        state.clientModeBaseUrl.isNotBlank(),
                                ) {
                                    if (state.clientModeConnectionState is ClientModeConnectionState.Testing) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Text(stringResource(R.string.screen_settings_client_mode_test_connection_action))
                                    }
                                }
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun clientModeConnectionStatusText(state: ClientModeConnectionState): String? {
    val formatter = remember {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
    }

    return when (state) {
        ClientModeConnectionState.Idle -> null
        ClientModeConnectionState.Testing -> stringResource(R.string.screen_settings_client_mode_testing)
        is ClientModeConnectionState.Success -> stringResource(
            R.string.screen_settings_client_mode_success,
            formatter.format(Instant.ofEpochMilli(state.testedAtMillis).atZone(ZoneId.systemDefault())),
        )
        is ClientModeConnectionState.Failure -> stringResource(
            R.string.screen_settings_client_mode_failure,
            state.message,
            formatter.format(Instant.ofEpochMilli(state.testedAtMillis).atZone(ZoneId.systemDefault())),
        )
    }
}

@Composable
private fun clientModeConnectionStatusColor(state: ClientModeConnectionState) = when (state) {
    ClientModeConnectionState.Idle,
    ClientModeConnectionState.Testing,
    -> MaterialTheme.colorScheme.onSurfaceVariant
    is ClientModeConnectionState.Success -> MaterialTheme.colorScheme.tertiary
    is ClientModeConnectionState.Failure -> MaterialTheme.colorScheme.error
}

// ---------------------------------------------------------------------------
// Memory block item
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoryBlockItem(
    block: EditAgentUiState.BlockState,
    onValueChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onLimitChange: (Int?) -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = if (block.readOnly) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showDeleteConfirm = true },
                )
        },
    ) {
        OutlinedTextField(
            value = block.value,
            onValueChange = onValueChange,
            label = { Text(block.label, style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = !block.readOnly,
            supportingText = block.limit?.let { limit ->
                { Text("${block.value.length}/$limit chars", style = MaterialTheme.typography.labelSmall) }
            },
            textStyle = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = block.description.orEmpty(),
            onValueChange = onDescriptionChange,
            label = { Text(stringResource(R.string.common_description), style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = !block.readOnly,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = block.limit?.toString().orEmpty(),
            onValueChange = { value ->
                if (value.isBlank() || value.toIntOrNull() != null) {
                    onLimitChange(value.toIntOrNull())
                }
            },
            label = { Text(stringResource(R.string.screen_agent_edit_character_limit), style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !block.readOnly,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        if (block.isTemplate || block.readOnly) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (block.isTemplate) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.screen_agent_edit_block_template)) },
                    )
                }
                if (block.readOnly) {
                    AssistChip(
                        enabled = false,
                        onClick = {},
                        label = { Text(stringResource(R.string.screen_agent_edit_block_read_only)) },
                    )
                }
            }
        }
    }

    ConfirmDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.screen_agent_edit_detach_block_title, block.label),
        message = stringResource(R.string.screen_agent_edit_detach_block_message),
        confirmText = stringResource(R.string.action_remove),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { showDeleteConfirm = false; onDelete() },
        onDismiss = { showDeleteConfirm = false },
        destructive = true,
    )
}

// ---------------------------------------------------------------------------
// Add block dialog
// ---------------------------------------------------------------------------

@Composable
private fun AddBlockDialog(
    onDismiss: () -> Unit,
    onAdd: (label: String, value: String, description: String, limit: Int?) -> Unit,
) {
    var newLabel by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var newDescription by remember { mutableStateOf("") }
    var newLimit by remember { mutableStateOf("") }

    MultiFieldInputDialog(
        show = true,
        title = stringResource(R.string.screen_agent_edit_add_memory_block),
        confirmText = stringResource(R.string.action_create),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = newLabel.isNotBlank(),
        onConfirm = { onAdd(newLabel, newValue, newDescription, newLimit.toIntOrNull()) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newLabel,
                onValueChange = { newLabel = it },
                label = { Text(stringResource(R.string.common_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newValue,
                onValueChange = { newValue = it },
                label = { Text(stringResource(R.string.common_value)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newDescription,
                onValueChange = { newDescription = it },
                label = { Text(stringResource(R.string.common_description)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            OutlinedTextField(
                value = newLimit,
                onValueChange = { value ->
                    if (value.isBlank() || value.toIntOrNull() != null) {
                        newLimit = value
                    }
                },
                label = { Text(stringResource(R.string.screen_agent_edit_character_limit)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Clone dialog
// ---------------------------------------------------------------------------

@Composable
private fun CloneAgentDialog(
    initialName: String,
    isCloning: Boolean,
    onDismiss: () -> Unit,
    onClone: (cloneName: String?, overrideExistingTools: Boolean, stripMessages: Boolean) -> Unit,
) {
    var cloneName by remember(initialName) { mutableStateOf(if (initialName.isBlank()) "" else "$initialName Copy") }
    var overrideExistingTools by remember { mutableStateOf(true) }
    var stripMessages by remember { mutableStateOf(true) }

    MultiFieldInputDialog(
        show = true,
        title = stringResource(R.string.screen_settings_clone_title),
        confirmText = stringResource(R.string.action_clone_agent),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = !isCloning,
        onConfirm = {
            onClone(cloneName.ifBlank { null }, overrideExistingTools, stripMessages)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.screen_settings_clone_dialog_helper),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = cloneName,
                onValueChange = { cloneName = it },
                label = { Text(stringResource(R.string.screen_settings_clone_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.screen_agents_import_override_tools_title), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.screen_agents_import_override_tools_helper),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = overrideExistingTools, onCheckedChange = { overrideExistingTools = it })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.screen_agents_import_strip_messages_title), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.screen_agents_import_strip_messages_helper),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = stripMessages, onCheckedChange = { stripMessages = it })
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tool detail dialog
// ---------------------------------------------------------------------------

@Composable
private fun ToolDetailDialog(
    tool: Tool,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        show = true,
        title = tool.name,
        confirmText = stringResource(R.string.action_close),
        dismissText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LettaIcons.Tool, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(tool.name, style = MaterialTheme.typography.titleMedium)
            }
            tool.description?.let { desc ->
                Text(text = desc, style = MaterialTheme.typography.bodyMedium)
            }
            tool.toolType?.let { type ->
                Row {
                    Text(
                        text = stringResource(R.string.common_type) + ": ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(text = type, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (tool.tags.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.common_tags) + ": " + tool.tags.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
