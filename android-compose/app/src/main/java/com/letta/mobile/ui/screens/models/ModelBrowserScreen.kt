package com.letta.mobile.ui.screens.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.EmbeddingModel
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.EmptyState
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

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_models_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
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

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.screen_models_search_hint)) },
            leadingIcon = { Icon(LettaIcons.Search, contentDescription = null) },
            singleLine = true,
        )

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
                        items(filteredModels, key = { it.id }) { model ->
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
                        items(filteredEmbeddingModels, key = { it.id }) { model ->
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
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
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
                    fontFamily = FontFamily.Monospace,
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
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
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
                    fontFamily = FontFamily.Monospace,
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.displayName) },
        text = {
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
                    DetailRow(stringResource(R.string.screen_models_detail_parallel_tool_calls, if (it) "Yes" else "No"))
                }
                model.frequencyPenalty?.let {
                    DetailRow(stringResource(R.string.screen_models_detail_frequency_penalty, it))
                }

                if (model.enableReasoner == true || model.reasoningEffort != null || model.maxReasoningTokens != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    model.enableReasoner?.let {
                        DetailRow(stringResource(R.string.screen_models_detail_reasoning, if (it) "Enabled" else "Disabled"))
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun EmbeddingModelDetailDialog(
    model: EmbeddingModel,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(model.displayName) },
        text = {
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
                        DetailRow("Azure Endpoint: $it")
                    }
                    model.azureVersion?.let {
                        DetailRow("Azure Version: $it")
                    }
                    model.azureDeployment?.let {
                        DetailRow("Azure Deployment: $it")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
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
