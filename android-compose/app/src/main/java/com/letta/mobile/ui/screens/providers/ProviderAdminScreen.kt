package com.letta.mobile.ui.screens.providers

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.letta.mobile.ui.components.ExpandableSearchField
import com.letta.mobile.ui.components.ExpandableTitleSearch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.Provider
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.FormItem
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.MultiFieldInputDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.theme.listItemHeadline
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.icons.LettaIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderAdminScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProviderAdminViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Provider?>(null) }
    var deleteTarget by remember { mutableStateOf<Provider?>(null) }

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
                        expanded = isSearchExpanded,
                        onExpandedChange = { isSearchExpanded = it },
                        placeholder = stringResource(R.string.screen_providers_search_hint),
                        openSearchContentDescription = stringResource(R.string.action_search),
                        closeSearchContentDescription = stringResource(R.string.action_close),
                        titleContent = { Text(stringResource(R.string.screen_providers_title)) },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
            ExpandableSearchField(
                query = (uiState as? UiState.Success)?.data?.searchQuery.orEmpty(),
                onQueryChange = viewModel::updateSearchQuery,
                onClear = { viewModel.updateSearchQuery("") },
                expanded = isSearchExpanded,
                placeholder = stringResource(R.string.screen_providers_search_hint),
            )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(LettaIcons.Add, stringResource(R.string.screen_providers_add_title))
            }
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = viewModel::loadProviders,
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> {
                val filtered = remember(state.data.providers, state.data.searchQuery) { viewModel.getFilteredProviders() }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                    if (filtered.isEmpty()) {
                        EmptyState(
                            icon = LettaIcons.Cloud,
                            message = if (state.data.searchQuery.isBlank()) {
                                stringResource(R.string.screen_providers_empty)
                            } else {
                                stringResource(R.string.screen_providers_empty_search, state.data.searchQuery)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(filtered, key = { it.id ?: it.name }) { provider ->
                                ProviderCard(
                                    provider = provider,
                                    onInspect = { provider.id?.let(viewModel::inspectProvider) },
                                    onEdit = { editTarget = provider },
                                    onDelete = { deleteTarget = provider },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val state = (uiState as? UiState.Success)?.data
    state?.selectedProvider?.let { provider ->
        ProviderDetailDialog(
            provider = provider,
            onDismiss = viewModel::clearSelectedProvider,
            onEdit = {
                viewModel.clearSelectedProvider()
                editTarget = provider
            },
            onCheck = { provider.id?.let(viewModel::checkProvider) },
        )
    }

    if (showCreateDialog) {
        ProviderEditorDialog(
            title = stringResource(R.string.screen_providers_add_title),
            confirmLabel = stringResource(R.string.action_create),
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, providerType, apiKey, baseUrl, accessKey, region ->
                viewModel.createProvider(name, providerType, apiKey, baseUrl, accessKey, region) {
                    showCreateDialog = false
                }
            },
        )
    }

    editTarget?.let { provider ->
        ProviderEditorDialog(
            title = stringResource(R.string.screen_providers_edit_title),
            confirmLabel = stringResource(R.string.action_save),
            initialName = provider.name,
            initialProviderType = provider.providerType,
            initialApiKey = provider.apiKey.orEmpty(),
            initialBaseUrl = provider.baseUrl.orEmpty(),
            initialAccessKey = provider.accessKey.orEmpty(),
            initialRegion = provider.region.orEmpty(),
            isCreate = false,
            onDismiss = { editTarget = null },
            onConfirm = { _, _, apiKey, baseUrl, accessKey, region ->
                val providerId = provider.id ?: return@ProviderEditorDialog
                viewModel.updateProvider(providerId, apiKey, baseUrl, accessKey, region) {
                    editTarget = null
                }
            },
        )
    }

    deleteTarget?.let { provider ->
        val providerId = provider.id
        if (providerId != null) {
            ConfirmDialog(
                show = true,
                title = stringResource(R.string.screen_providers_delete_title),
                message = stringResource(R.string.screen_providers_delete_confirm, provider.name),
                confirmText = stringResource(R.string.action_delete),
                dismissText = stringResource(R.string.action_cancel),
                onConfirm = {
                    viewModel.deleteProvider(providerId)
                    deleteTarget = null
                },
                onDismiss = { deleteTarget = null },
                destructive = true,
            )
        }
    }

    state?.operationError?.let { operationError ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.common_error),
            message = operationError,
            confirmText = stringResource(R.string.action_dismiss),
            dismissText = stringResource(R.string.action_dismiss),
            onConfirm = viewModel::clearOperationError,
            onDismiss = viewModel::clearOperationError,
        )
    }

    state?.operationMessage?.let { operationMessage ->
        ConfirmDialog(
            show = true,
            title = stringResource(R.string.common_provider),
            message = operationMessage,
            confirmText = stringResource(R.string.action_dismiss),
            dismissText = stringResource(R.string.action_dismiss),
            onConfirm = viewModel::clearOperationMessage,
            onDismiss = viewModel::clearOperationMessage,
        )
    }
}

@Composable
private fun ProviderCard(
    provider: Provider,
    onInspect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var showContextMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onInspect,
        modifier = Modifier.fillMaxWidth(),
        colors = LettaCardDefaults.listCardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(provider.name, style = MaterialTheme.typography.listItemHeadline)
                    provider.baseUrl?.takeIf { it.isNotBlank() }?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.listItemSupporting, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { showContextMenu = true }) {
                    Icon(LettaIcons.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(provider.providerType) })
                provider.region?.let { AssistChip(onClick = {}, label = { Text(it) }) }
            }
        }
    }

    ActionSheet(
        show = showContextMenu,
        onDismiss = { showContextMenu = false },
        title = provider.name,
    ) {
        ActionSheetItem(
            text = stringResource(R.string.screen_providers_edit_title),
            icon = LettaIcons.Edit,
            onClick = {
                showContextMenu = false
                onEdit()
            },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_delete),
            icon = LettaIcons.Delete,
            onClick = {
                showContextMenu = false
                onDelete()
            },
            destructive = true,
        )
    }
}

@Composable
private fun ProviderDetailDialog(
    provider: Provider,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onCheck: () -> Unit,
) {
    ConfirmDialog(
        show = true,
        title = provider.name,
        confirmText = stringResource(R.string.action_close),
        dismissText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardGroup {
                provider.id?.let { id ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_providers_id_label, "")) },
                        supportingContent = { Text(id.value, style = MaterialTheme.typography.listItemSupporting) },
                    )
                }
                item(
                    headlineContent = { Text(stringResource(R.string.screen_providers_type_label, "")) },
                    supportingContent = { Text(provider.providerType, style = MaterialTheme.typography.listItemSupporting) },
                )
                provider.providerCategory?.let { category ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_providers_category_label, "")) },
                        supportingContent = { Text(category, style = MaterialTheme.typography.listItemSupporting) },
                    )
                }
                provider.baseUrl?.let { url ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_providers_base_url_label, "")) },
                        supportingContent = { Text(url, style = MaterialTheme.typography.listItemSupporting) },
                    )
                }
                provider.region?.let { region ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_providers_region_label, "")) },
                        supportingContent = { Text(region, style = MaterialTheme.typography.listItemSupporting) },
                    )
                }
                provider.organizationId?.let { orgId ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_providers_organization_label, "")) },
                        supportingContent = { Text(orgId, style = MaterialTheme.typography.listItemSupporting) },
                    )
                }
                provider.updatedAt?.let { updated ->
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_providers_updated_label, "")) },
                        supportingContent = { Text(updated, style = MaterialTheme.typography.listItemSupporting) },
                    )
                }
                item(
                    headlineContent = { Text(stringResource(R.string.screen_providers_secret_present_label, "")) },
                    supportingContent = { Text(provider.apiKey.isNullOrBlank().not().toString(), style = MaterialTheme.typography.listItemSupporting) },
                )
                provider.accessKey?.takeIf { it.isNotBlank() }?.let {
                    item(
                        headlineContent = { Text(stringResource(R.string.screen_providers_access_key_present_label, "")) },
                        supportingContent = { Text(true.toString(), style = MaterialTheme.typography.listItemSupporting) },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.screen_providers_edit_title)) }
                TextButton(onClick = onCheck) { Text(stringResource(R.string.action_check)) }
            }
        }
    }
}

@Composable
private fun ProviderEditorDialog(
    title: String,
    confirmLabel: String,
    initialName: String = "",
    initialProviderType: String = "",
    initialApiKey: String = "",
    initialBaseUrl: String = "",
    initialAccessKey: String = "",
    initialRegion: String = "",
    isCreate: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var providerType by remember(initialProviderType) { mutableStateOf(initialProviderType) }
    var apiKey by remember(initialApiKey) { mutableStateOf(initialApiKey) }
    var baseUrl by remember(initialBaseUrl) { mutableStateOf(initialBaseUrl) }
    var accessKey by remember(initialAccessKey) { mutableStateOf(initialAccessKey) }
    var region by remember(initialRegion) { mutableStateOf(initialRegion) }

    MultiFieldInputDialog(
        show = true,
        title = title,
        confirmText = confirmLabel,
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmEnabled = apiKey.isNotBlank() && (!isCreate || (name.isNotBlank() && providerType.isNotBlank())),
        onConfirm = {
            onConfirm(name.trim(), providerType.trim(), apiKey.trim(), baseUrl.trim(), accessKey.trim(), region.trim())
        },
    ) {
        CardGroup {
            item(
                headlineContent = {
                    FormItem(label = { Text(stringResource(R.string.common_name)) }) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            enabled = isCreate,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
            item(
                headlineContent = {
                    FormItem(label = { Text(stringResource(R.string.screen_providers_type_input)) }) {
                        OutlinedTextField(
                            value = providerType,
                            onValueChange = { providerType = it },
                            singleLine = true,
                            enabled = isCreate,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
            item(
                headlineContent = {
                    FormItem(label = { Text(stringResource(R.string.screen_providers_api_key_input)) }) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
            item(
                headlineContent = {
                    FormItem(label = { Text(stringResource(R.string.screen_providers_base_url_input)) }) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
            item(
                headlineContent = {
                    FormItem(label = { Text(stringResource(R.string.screen_providers_access_key_input)) }) {
                        OutlinedTextField(
                            value = accessKey,
                            onValueChange = { accessKey = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
            item(
                headlineContent = {
                    FormItem(label = { Text(stringResource(R.string.screen_providers_region_input)) }) {
                        OutlinedTextField(
                            value = region,
                            onValueChange = { region = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
        }
    }
}
