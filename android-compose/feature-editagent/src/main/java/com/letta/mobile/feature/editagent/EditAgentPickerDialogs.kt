package com.letta.mobile.feature.editagent

import com.letta.mobile.ui.theme.LettaCodeFont

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.letta.mobile.feature.editagent.R
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockId
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.Tool
import com.letta.mobile.ui.components.Accordions
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ExpandableTitleSearch
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.highlightSearchMatches
import com.letta.mobile.ui.components.rememberSearchHighlightColors
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults

@Composable
internal fun SearchPickerField(
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
                    fontFamily = LettaCodeFont,
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
internal fun FullScreenModelPickerDialog(
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
internal fun ModelPickerCard(
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
                    fontFamily = LettaCodeFont,
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
internal fun FullScreenToolPickerDialog(
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

@Composable
internal fun SelectableToolCard(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullScreenBlockPickerDialog(
    excludedBlockIds: List<String>,
    availableBlocks: List<Block>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
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
            val filteredBlocks = remember(availableBlocks, excludedBlockIds, query) {
                val normalizedQuery = query.trim().lowercase()
                val excluded = excludedBlockIds.mapTo(HashSet()) { BlockId(it) }
                availableBlocks
                    .filter { it.id !in excluded }
                    .filter { block ->
                        normalizedQuery.isBlank() ||
                            (block.label?.lowercase()?.contains(normalizedQuery) == true) ||
                            (block.description?.lowercase()?.contains(normalizedQuery) == true) ||
                            block.value.lowercase().contains(normalizedQuery)
                    }
            }
            if (filteredBlocks.isEmpty()) {
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
                    items(filteredBlocks, key = { it.id.value }) { block ->
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

@Composable
internal fun SelectableBlockCard(
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
