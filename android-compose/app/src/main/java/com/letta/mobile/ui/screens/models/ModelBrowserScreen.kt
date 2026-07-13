package com.letta.mobile.ui.screens.models

import com.letta.mobile.ui.theme.LettaCodeFont

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.ExpandableSearchField
import com.letta.mobile.ui.components.ExpandableTitleSearch
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.icons.LettaIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelBrowserScreen(
    onNavigateBack: () -> Unit,
    onModelSelected: ((String) -> Unit)? = null,
    viewModel: ModelBrowserViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isSearchExpanded = rememberSaveable { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
            LargeFlexibleTopAppBar(
                title = {
                    ExpandableTitleSearch(
                        query = (uiState as? UiState.Success)?.data?.searchQuery.orEmpty(),
                        onQueryChange = viewModel::updateSearchQuery,
                        onClear = { viewModel.updateSearchQuery("") },
                        expanded = isSearchExpanded.value,
                        onExpandedChange = { isSearchExpanded.value = it },
                        placeholder = stringResource(R.string.screen_models_search_hint),
                        openSearchContentDescription = stringResource(R.string.action_search),
                        closeSearchContentDescription = stringResource(R.string.action_close),
                        titleContent = { Text(stringResource(R.string.screen_models_title)) },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
            ExpandableSearchField(
                query = (uiState as? UiState.Success)?.data?.searchQuery.orEmpty(),
                onQueryChange = viewModel::updateSearchQuery,
                onClear = { viewModel.updateSearchQuery("") },
                expanded = isSearchExpanded.value,
                placeholder = stringResource(R.string.screen_models_search_hint),
            )
            }
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> EmptyState(
                icon = LettaIcons.Search,
                message = state.message,
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
            )
            is UiState.Success -> ModelBrowserContent(
                state = state.data,
                filteredModels = viewModel.getFilteredModels(),
                filteredEmbeddingModels = viewModel.getFilteredEmbeddingModels(),
                providers = viewModel.getProviders(),
                onSearchChange = { viewModel.updateSearchQuery(it) },
                onProviderSelect = { viewModel.selectProvider(it) },
                onTabSelect = { viewModel.selectTab(it) },
                onLlmModelClick = { viewModel.selectLlmModel(it) },
                onEmbeddingModelClick = { viewModel.selectEmbeddingModel(it) },
                onModelSelected = onModelSelected,
                modifier = Modifier.padding(paddingValues),
            )
        }
    }

    val selectedLlm = (uiState as? UiState.Success)?.data?.selectedLlmModel
    if (selectedLlm != null) {
        LlmModelDetailDialog(
            model = selectedLlm,
            onDismiss = { viewModel.clearSelectedLlmModel() },
        )
    }

    val selectedEmbedding = (uiState as? UiState.Success)?.data?.selectedEmbeddingModel
    if (selectedEmbedding != null) {
        EmbeddingModelDetailDialog(
            model = selectedEmbedding,
            onDismiss = { viewModel.clearSelectedEmbeddingModel() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelBrowserContent(
    state: ModelBrowserUiState,
    filteredModels: List<LlmModel>,
    filteredEmbeddingModels: List<EmbeddingModel>,
    providers: List<String>,
    onSearchChange: (String) -> Unit,
    onProviderSelect: (String?) -> Unit,
    onTabSelect: (ModelTab) -> Unit,
    onLlmModelClick: (LlmModel) -> Unit,
    onEmbeddingModelClick: (EmbeddingModel) -> Unit,
    onModelSelected: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
            Tab(
                selected = state.selectedTab == ModelTab.LLM,
                onClick = { onTabSelect(ModelTab.LLM) },
                text = { Text(stringResource(R.string.screen_models_tab_llm)) },
            )
            Tab(
                selected = state.selectedTab == ModelTab.EMBEDDING,
                onClick = { onTabSelect(ModelTab.EMBEDDING) },
                text = { Text(stringResource(R.string.screen_models_tab_embedding)) },
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = state.selectedProvider == null,
                    onClick = { onProviderSelect(null) },
                    label = { Text(stringResource(R.string.screen_models_filter_all)) },
                )
            }
            items(providers, key = { it }) { provider ->
                FilterChip(
                    selected = state.selectedProvider == provider,
                    onClick = { onProviderSelect(provider) },
                    label = { Text(provider) },
                    leadingIcon = if (state.selectedProvider == provider) {
                        { Icon(LettaIcons.Check, contentDescription = "Selected", modifier = Modifier.height(16.dp)) }
                    } else {
                        null
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (state.selectedTab) {
            ModelTab.LLM -> {
                if (filteredModels.isEmpty()) {
                    EmptyState(
                        icon = LettaIcons.Search,
                        message = stringResource(R.string.screen_models_no_models),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(filteredModels, key = ::llmModelListKey) { _, model ->
                            LlmModelCard(
                                model = model,
                                onClick = { onLlmModelClick(model) },
                            )
                        }
                    }
                }
            }
            ModelTab.EMBEDDING -> {
                if (filteredEmbeddingModels.isEmpty()) {
                    EmptyState(
                        icon = LettaIcons.Search,
                        message = stringResource(R.string.screen_models_no_embedding_models),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(filteredEmbeddingModels, key = ::embeddingModelListKey) { _, model ->
                            EmbeddingModelCard(
                                model = model,
                                onClick = { onEmbeddingModelClick(model) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LlmModelCard(
    model: LlmModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = LettaCardDefaults.listCardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            model.handle?.let { handle ->
                Text(
                    text = handle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = LettaCodeFont,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(onClick = {}, label = { Text(model.providerType) })
                model.contextWindow?.let { contextWindow ->
                    Text(
                        text = "${contextWindow / 1000}K ctx",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                model.tier?.let { tier ->
                    Text(
                        text = tier,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (model.enableReasoner == true) {
                    AssistChip(onClick = {}, label = { Text("Reasoner") })
                }
            }
        }
    }
}

@Composable
private fun EmbeddingModelCard(
    model: EmbeddingModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = LettaCardDefaults.listCardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            model.handle?.let { handle ->
                Text(
                    text = handle,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = LettaCodeFont,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(onClick = {}, label = { Text(model.providerType) })
                model.embeddingDim?.let { dim ->
                    Text(
                        text = "${dim}d",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                model.embeddingChunkSize?.let { chunk ->
                    Text(
                        text = "${chunk} chunk",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LlmModelDetailDialog(
    model: LlmModel,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        show = true,
        title = model.displayName,
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            model.handle?.let {
                DetailRow(stringResource(R.string.screen_models_detail_handle, it))
            }
            DetailRow(stringResource(R.string.screen_models_detail_provider, model.providerType))
            model.providerName?.let {
                DetailRow(stringResource(R.string.screen_models_detail_provider_name, it))
            }
            model.providerCategory?.let {
                DetailRow(stringResource(R.string.screen_models_detail_provider_category, it))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            model.contextWindow?.let {
                DetailRow(stringResource(R.string.screen_models_detail_context_window, formatNumber(it)))
            }
            model.maxOutputTokens?.let {
                DetailRow(stringResource(R.string.screen_models_detail_max_output, it))
            }
            model.temperature?.let {
                DetailRow(stringResource(R.string.screen_models_detail_temperature, it))
            }
            model.maxTokens?.let {
                DetailRow(stringResource(R.string.screen_models_detail_max_tokens, it))
            }
            model.parallelToolCalls?.let {
                DetailRow(stringResource(R.string.screen_models_detail_parallel_tool_calls, stringResource(if (it) R.string.screen_models_detail_yes else R.string.screen_models_detail_no)))
            }
            model.frequencyPenalty?.let {
                DetailRow(stringResource(R.string.screen_models_detail_frequency_penalty, it))
            }

            if (model.enableReasoner == true || model.reasoningEffort != null || model.maxReasoningTokens != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                model.enableReasoner?.let {
                    DetailRow(stringResource(R.string.screen_models_detail_reasoning, stringResource(if (it) R.string.screen_models_detail_enabled else R.string.screen_models_detail_disabled)))
                }
                model.reasoningEffort?.let {
                    DetailRow(stringResource(R.string.screen_models_detail_reasoning_effort, it))
                }
                model.maxReasoningTokens?.let {
                    DetailRow(stringResource(R.string.screen_models_detail_max_reasoning, it))
                }
            }

            if (model.modelEndpointType != null || model.modelEndpoint != null || model.modelWrapper != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                model.modelEndpointType?.let {
                    DetailRow(stringResource(R.string.screen_models_detail_endpoint_type, it))
                }
                model.modelEndpoint?.let {
                    DetailRow(stringResource(R.string.screen_models_detail_endpoint, it))
                }
                model.modelWrapper?.let {
                    DetailRow(stringResource(R.string.screen_models_detail_wrapper, it))
                }
            }

            if (model.compatibilityType != null || model.verbosity != null || model.tier != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                model.compatibilityType?.let {
                    DetailRow(stringResource(R.string.screen_models_detail_compatibility, it))
                }
                model.verbosity?.let {
                    DetailRow(stringResource(R.string.screen_models_detail_verbosity, it))
                }
                model.tier?.let {
                    DetailRow(stringResource(R.string.screen_models_detail_tier, it))
                }
            }
        }
    }
}

@Composable
private fun EmbeddingModelDetailDialog(
    model: EmbeddingModel,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        show = true,
        title = model.displayName,
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            model.handle?.let {
                DetailRow(stringResource(R.string.screen_models_detail_handle, it))
            }
            DetailRow(stringResource(R.string.screen_models_detail_provider, model.providerType))
            model.providerName?.let {
                DetailRow(stringResource(R.string.screen_models_detail_provider_name, it))
            }
            model.providerCategory?.let {
                DetailRow(stringResource(R.string.screen_models_detail_provider_category, it))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            model.embeddingModel?.let {
                DetailRow(stringResource(R.string.screen_models_embedding_model, it))
            }
            model.embeddingDim?.let {
                DetailRow(stringResource(R.string.screen_models_embedding_dim, it))
            }
            model.embeddingChunkSize?.let {
                DetailRow(stringResource(R.string.screen_models_embedding_chunk_size, it))
            }
            model.batchSize?.let {
                DetailRow(stringResource(R.string.screen_models_embedding_batch_size, it))
            }

            if (model.embeddingEndpointType != null || model.embeddingEndpoint != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                model.embeddingEndpointType?.let {
                    DetailRow(stringResource(R.string.screen_models_detail_endpoint_type, it))
                }
                model.embeddingEndpoint?.let {
                    DetailRow(stringResource(R.string.screen_models_detail_endpoint, it))
                }
            }

            if (model.azureEndpoint != null || model.azureVersion != null || model.azureDeployment != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                model.azureEndpoint?.let {
                    DetailRow(stringResource(R.string.screen_models_detail_azure_endpoint, it))
                }
                model.azureVersion?.let {
                    DetailRow(stringResource(R.string.screen_models_detail_azure_version, it))
                }
                model.azureDeployment?.let {
                    DetailRow(stringResource(R.string.screen_models_detail_azure_deployment, it))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatNumber(value: Int): String {
    return if (value >= 1000) {
        val thousands = value / 1000
        val remainder = value % 1000
        if (remainder == 0) "${thousands},000" else "$thousands,${remainder.toString().padStart(3, '0')}"
    } else {
        value.toString()
    }
}

private fun llmModelListKey(index: Int, model: LlmModel): String {
    return model.stableModelListKey(index, prefix = "llm")
}

private fun embeddingModelListKey(index: Int, model: EmbeddingModel): String {
    return model.stableModelListKey(index, prefix = "embedding")
}

private fun LlmModel.stableModelListKey(index: Int, prefix: String): String {
    return buildModelListKey(
        prefix = prefix,
        index = index,
        id = id,
        handle = handle,
        name = name,
        providerType = providerType,
    )
}

private fun EmbeddingModel.stableModelListKey(index: Int, prefix: String): String {
    return buildModelListKey(
        prefix = prefix,
        index = index,
        id = id,
        handle = handle,
        name = name,
        providerType = providerType,
    )
}

private fun buildModelListKey(
    prefix: String,
    index: Int,
    id: String,
    handle: String?,
    name: String,
    providerType: String,
): String {
    val identity = id.ifBlank { handle.orEmpty() }.ifBlank { name }.ifBlank { providerType }.ifBlank { "model" }
    return "$prefix:$identity:$index"
}
