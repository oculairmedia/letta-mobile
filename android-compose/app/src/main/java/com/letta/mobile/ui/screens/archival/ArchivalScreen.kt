package com.letta.mobile.ui.screens.archival

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.letta.mobile.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.model.Passage
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.LoadingIndicator
import com.letta.mobile.ui.components.ShimmerCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivalScreen(
    onNavigateBack: () -> Unit,
    viewModel: ArchivalViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Passage?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_archival_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.screen_archival_add_action))
            }
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> com.letta.mobile.ui.components.ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadPassages() },
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> ArchivalContent(
                state = state.data,
                onSearchChange = { viewModel.search(it) },
                onToggleHasSource = viewModel::setFilterHasSource,
                onToggleHasMetadata = viewModel::setFilterHasMetadata,
                filteredPassages = viewModel.getFilteredPassages(),
                onInspectPassage = { viewModel.inspectPassage(it) },
                onDeletePassage = { deleteTarget = it },
                modifier = Modifier.padding(paddingValues),
            )
        }
    }

    if (showAddDialog) {
        AddPassageDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { text ->
                viewModel.addPassage(text)
                showAddDialog = false
            },
        )
    }

    (uiState as? UiState.Success)?.data?.selectedPassage?.let { passage ->
        PassageDetailDialog(
            passage = passage,
            onDismiss = { viewModel.clearSelectedPassage() },
        )
    }

    deleteTarget?.let { passage ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.screen_archival_delete_title)) },
            text = { Text(stringResource(R.string.screen_archival_delete_confirm, passage.id)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePassage(passage.id)
                        deleteTarget = null
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ArchivalContent(
    state: ArchivalUiState,
    onSearchChange: (String) -> Unit,
    onToggleHasSource: (Boolean) -> Unit,
    onToggleHasMetadata: (Boolean) -> Unit,
    filteredPassages: List<Passage>,
    onInspectPassage: (Passage) -> Unit,
    onDeletePassage: (Passage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.screen_archival_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.screen_archival_search_action)) },
            trailingIcon = {
                if (state.searchQuery.isNotBlank()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_close))
                    }
                }
            },
            singleLine = true,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.filterHasSource,
                onClick = { onToggleHasSource(!state.filterHasSource) },
                label = { Text(stringResource(R.string.screen_archival_filter_has_source)) },
            )
            FilterChip(
                selected = state.filterHasMetadata,
                onClick = { onToggleHasMetadata(!state.filterHasMetadata) },
                label = { Text(stringResource(R.string.screen_archival_filter_has_metadata)) },
            )
        }

        if (filteredPassages.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Search,
                message = if (state.searchQuery.isBlank()) {
                    stringResource(R.string.screen_archival_empty)
                } else {
                    stringResource(R.string.screen_archival_empty_search, state.searchQuery)
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredPassages, key = { it.id }) { passage ->
                    PassageCard(
                        passage = passage,
                        onInspect = { onInspectPassage(passage) },
                        onDelete = { onDeletePassage(passage) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PassageCard(
    passage: Passage,
    onInspect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onInspect, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = passage.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.screen_archival_delete_action))
                }
            }
            passage.createdAt?.let { createdAt ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = createdAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PassageDetailDialog(
    passage: Passage,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_archival_detail_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                passage.sourceId?.let {
                    Text(stringResource(R.string.screen_archival_source_id_label, it), style = MaterialTheme.typography.bodySmall)
                }
                passage.createdAt?.let {
                    Text(stringResource(R.string.screen_archival_created_label, it), style = MaterialTheme.typography.bodySmall)
                }
                val meta = passage.metadata
                if (!meta.isNullOrEmpty()) {
                    Text(stringResource(R.string.screen_archival_metadata_title), style = MaterialTheme.typography.labelLarge)
                    meta.entries.forEach { (key, value) ->
                        Text(
                            text = "$key: $value",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(passage.text, style = MaterialTheme.typography.bodyMedium)
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
private fun AddPassageDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_archival_add_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.screen_archival_passage_label)) },
                minLines = 3,
                maxLines = 6,
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
