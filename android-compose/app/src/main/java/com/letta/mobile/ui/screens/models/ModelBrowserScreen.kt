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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.LoadingIndicator
import com.letta.mobile.ui.components.ShimmerCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelBrowserScreen(
    onNavigateBack: () -> Unit,
    onModelSelected: ((String) -> Unit)? = null,
    viewModel: ModelBrowserViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_models_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> EmptyState(
                icon = Icons.Default.Search,
                message = state.message,
                modifier = Modifier.padding(paddingValues).fillMaxSize()
            )
            is UiState.Success -> ModelBrowserContent(
                state = state.data,
                filteredModels = viewModel.getFilteredModels(),
                providers = viewModel.getProviders(),
                onSearchChange = { viewModel.updateSearchQuery(it) },
                onProviderSelect = { viewModel.selectProvider(it) },
                onModelClick = { onModelSelected?.invoke(it.id) },
                modifier = Modifier.padding(paddingValues),
            )
        }
    }
}

@Composable
private fun ModelBrowserContent(
    state: ModelBrowserUiState,
    filteredModels: List<LlmModel>,
    providers: List<String>,
    onSearchChange: (String) -> Unit,
    onProviderSelect: (String?) -> Unit,
    onModelClick: (LlmModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.screen_models_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
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
                        { Icon(Icons.Default.Check, contentDescription = "Selected", modifier = Modifier.height(16.dp)) }
                    } else null,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (filteredModels.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Search,
                message = "No models found",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredModels, key = { it.id }) { model ->
                    ModelCard(model = model, onClick = { onModelClick(model) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelCard(
    model: LlmModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.providerType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                model.contextWindow?.let { contextWindow ->
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${contextWindow / 1000}K context",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
