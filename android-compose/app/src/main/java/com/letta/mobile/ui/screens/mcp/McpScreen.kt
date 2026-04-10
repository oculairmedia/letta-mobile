package com.letta.mobile.ui.screens.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerCreateParams
import com.letta.mobile.data.model.McpServerUpdateParams
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.effectiveArgs
import com.letta.mobile.data.model.effectiveAuthHeader
import com.letta.mobile.data.model.effectiveAuthToken
import com.letta.mobile.data.model.effectiveCommand
import com.letta.mobile.data.model.effectiveCustomHeaders
import com.letta.mobile.data.model.effectiveEnv
import com.letta.mobile.data.model.effectiveServerType
import com.letta.mobile.data.model.effectiveServerUrl
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MCP_TYPE_STDIO = "stdio"
private const val MCP_TYPE_SSE = "sse"
private const val MCP_TYPE_STREAMABLE_HTTP = "streamable_http"

@androidx.compose.runtime.Immutable
private data class McpServerFormState(
    val serverName: String = "",
    val transportType: String = MCP_TYPE_STREAMABLE_HTTP,
    val serverUrl: String = "",
    val command: String = "",
    val argsText: String = "",
    val authHeader: String = "",
    val authToken: String = "",
    val customHeadersText: String = "",
    val envText: String = "",
    val rawConfigText: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(
    onNavigateBack: () -> Unit,
    onNavigateToServerTools: (String) -> Unit = {},
    viewModel: McpViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showServerDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<McpServer?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_mcp_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshAll() }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            val state = (uiState as? UiState.Success)?.data
            if (state?.selectedTab == 1) {
                FloatingActionButton(
                    onClick = {
                        editingServer = null
                        showServerDialog = true
                    }
                ) {
                    Icon(Icons.Default.Add, stringResource(R.string.screen_mcp_dialog_add_title))
                }
            }
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadData() },
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> McpContent(
                state = state.data,
                onTabSelected = { viewModel.selectTab(it) },
                onDeleteServer = { viewModel.deleteServer(it.id) },
                onEditServer = {
                    editingServer = it
                    showServerDialog = true
                },
                onCheckServer = viewModel::checkServer,
                onNavigateToServerTools = onNavigateToServerTools,
                modifier = Modifier.padding(paddingValues),
            )
        }
    }

    if (showServerDialog) {
        ServerFormDialog(
            initialServer = editingServer,
            onDismiss = {
                showServerDialog = false
                editingServer = null
            },
            onCreate = { params ->
                viewModel.addServer(params)
                showServerDialog = false
                editingServer = null
            },
            onUpdate = { serverId, params ->
                viewModel.updateServer(serverId, params)
                showServerDialog = false
                editingServer = null
            },
        )
    }
}

@Composable
private fun McpContent(
    state: McpUiState,
    onTabSelected: (Int) -> Unit,
    onDeleteServer: (McpServer) -> Unit,
    onEditServer: (McpServer) -> Unit,
    onCheckServer: (String) -> Unit,
    onNavigateToServerTools: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = state.selectedTab) {
            Tab(
                selected = state.selectedTab == 0,
                onClick = { onTabSelected(0) },
                text = { Text(stringResource(R.string.common_tools)) },
            )
            Tab(
                selected = state.selectedTab == 1,
                onClick = { onTabSelected(1) },
                text = { Text(stringResource(R.string.screen_mcp_servers_tab)) },
            )
        }

        when (state.selectedTab) {
            0 -> ToolsTab(
                tools = state.allTools,
                toolParents = state.toolParents,
                onNavigateToServerTools = onNavigateToServerTools,
            )
            1 -> ServersTab(
                servers = state.servers,
                serverTools = state.serverTools,
                serverChecks = state.serverChecks,
                onDeleteServer = onDeleteServer,
                onEditServer = onEditServer,
                onCheckServer = onCheckServer,
            )
        }
    }
}

@Composable
private fun ToolsTab(
    tools: List<Tool>,
    toolParents: Map<String, McpToolParent>,
    onNavigateToServerTools: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tools.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Build,
            message = stringResource(R.string.screen_tools_empty),
            modifier = modifier.fillMaxSize(),
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tools, key = { it.id }) { tool ->
                ToolCard(
                    tool = tool,
                    parent = toolParents[tool.id],
                    onNavigateToServerTools = onNavigateToServerTools,
                )
            }
        }
    }
}

@Composable
private fun ServersTab(
    servers: List<McpServer>,
    serverTools: Map<String, List<Tool>>,
    serverChecks: Map<String, McpServerCheckState>,
    onDeleteServer: (McpServer) -> Unit,
    onEditServer: (McpServer) -> Unit,
    onCheckServer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (servers.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Storage,
            message = stringResource(R.string.screen_mcp_empty),
            modifier = modifier.fillMaxSize(),
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(servers, key = { it.id }) { server ->
                ServerCard(
                    server = server,
                    tools = serverTools[server.id] ?: emptyList(),
                    checkState = serverChecks[server.id],
                    onEdit = { onEditServer(server) },
                    onDelete = { onDeleteServer(server) },
                    onCheck = { onCheckServer(server.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolCard(
    tool: Tool,
    parent: McpToolParent?,
    onNavigateToServerTools: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Build, null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }

            tool.description?.let { description ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            tool.toolType?.let { toolType ->
                Spacer(modifier = Modifier.height(8.dp))
                AssistChip(
                    onClick = {},
                    label = { Text(toolType, style = MaterialTheme.typography.labelSmall) },
                )
            }

            parent?.let {
                Spacer(modifier = Modifier.height(8.dp))
                AssistChip(
                    onClick = { onNavigateToServerTools(it.serverId) },
                    label = {
                        Text(
                            stringResource(R.string.screen_mcp_tool_parent_server, it.serverName),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerCard(
    server: McpServer,
    tools: List<Tool>,
    checkState: McpServerCheckState?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        onClick = { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = server.serverName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        server.effectiveServerType()?.let { serverType ->
                            Spacer(modifier = Modifier.width(8.dp))
                            AssistChip(
                                onClick = {},
                                label = { Text(text = serverType, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }

                    server.effectiveServerUrl()?.let { url ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    server.effectiveCommand()?.let { command ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.screen_mcp_server_command),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = command,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    server.effectiveArgs().takeIf { it.isNotEmpty() }?.let { args ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = args.joinToString(" "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    server.createdAt?.let { createdAt ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_created, createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    server.updatedAt?.let { updatedAt ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_updated, updatedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    server.effectiveAuthHeader()?.let { authHeader ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_auth_header, authHeader),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    server.effectiveAuthToken()?.let {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_token_present),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    server.effectiveCustomHeaders()?.takeIf { it.isNotEmpty() }?.let { headers ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_custom_headers_count, headers.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    server.effectiveEnv()?.takeIf { it.isNotEmpty() }?.let { env ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_env_count, env.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    server.organizationId?.let { organizationId ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_organization, organizationId),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    server.createdById?.let { createdById ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_created_by, createdById),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    server.lastUpdatedById?.let { lastUpdatedById ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_updated_by, lastUpdatedById),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (server.metadata.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_metadata_count, server.metadata.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (tools.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_tools_count, tools.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Text(
                        text = when {
                            checkState?.isChecking == true -> stringResource(R.string.screen_mcp_server_checking)
                            checkState?.isReachable == true -> stringResource(R.string.screen_mcp_server_reachable)
                            checkState?.isReachable == false -> stringResource(R.string.screen_mcp_server_unreachable)
                            else -> stringResource(R.string.screen_mcp_server_unchecked)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (checkState?.isReachable) {
                            true -> MaterialTheme.colorScheme.primary
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    checkState?.message?.let { message ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row {
                    IconButton(onClick = onCheck) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.action_check))
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, stringResource(R.string.action_edit))
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, stringResource(R.string.action_delete))
                    }
                }
            }

            if (expanded && tools.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.screen_mcp_server_discovered_tools),
                    style = MaterialTheme.typography.labelMedium,
                )
                tools.forEach { tool ->
                    Row(
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "• ${tool.name}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        tool.description?.let { desc ->
                            Text(
                                text = " - $desc",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }

    ConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.screen_mcp_dialog_delete_title),
        message = stringResource(R.string.screen_mcp_dialog_delete_confirm, server.serverName),
        confirmText = stringResource(R.string.action_delete),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = {
            showDeleteDialog = false
            onDelete()
        },
        onDismiss = { showDeleteDialog = false },
        destructive = true,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerFormDialog(
    initialServer: McpServer?,
    onDismiss: () -> Unit,
    onCreate: (McpServerCreateParams) -> Unit,
    onUpdate: (String, McpServerUpdateParams) -> Unit,
) {
    var formState by remember(initialServer) { mutableStateOf(initialFormState(initialServer)) }
    val validationMessage = validateForm(formState)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialServer == null) {
                    stringResource(R.string.screen_mcp_dialog_add_title)
                } else {
                    stringResource(R.string.screen_mcp_dialog_edit_title)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = formState.serverName,
                    onValueChange = { formState = formState.copy(serverName = it) },
                    label = { Text(stringResource(R.string.screen_mcp_server_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.screen_mcp_transport_type),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = formState.transportType == MCP_TYPE_STREAMABLE_HTTP,
                        onClick = { formState = formState.copy(transportType = MCP_TYPE_STREAMABLE_HTTP) },
                        label = { Text(stringResource(R.string.screen_mcp_transport_streamable_http)) },
                    )
                    FilterChip(
                        selected = formState.transportType == MCP_TYPE_SSE,
                        onClick = { formState = formState.copy(transportType = MCP_TYPE_SSE) },
                        label = { Text(stringResource(R.string.screen_mcp_transport_sse)) },
                    )
                    FilterChip(
                        selected = formState.transportType == MCP_TYPE_STDIO,
                        onClick = { formState = formState.copy(transportType = MCP_TYPE_STDIO) },
                        label = { Text(stringResource(R.string.screen_mcp_transport_stdio)) },
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (formState.transportType == MCP_TYPE_STDIO) {
                    OutlinedTextField(
                        value = formState.command,
                        onValueChange = { formState = formState.copy(command = it) },
                        label = { Text(stringResource(R.string.screen_mcp_command_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = formState.argsText,
                        onValueChange = { formState = formState.copy(argsText = it) },
                        label = { Text(stringResource(R.string.screen_mcp_args_label)) },
                        placeholder = { Text(stringResource(R.string.screen_mcp_args_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = formState.envText,
                        onValueChange = { formState = formState.copy(envText = it) },
                        label = { Text(stringResource(R.string.screen_mcp_env_label)) },
                        placeholder = { Text(stringResource(R.string.screen_mcp_key_value_placeholder)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = formState.serverUrl,
                        onValueChange = { formState = formState.copy(serverUrl = it) },
                        label = { Text(stringResource(R.string.common_server_url)) },
                        placeholder = { Text(stringResource(R.string.screen_mcp_url_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = formState.authHeader,
                        onValueChange = { formState = formState.copy(authHeader = it) },
                        label = { Text(stringResource(R.string.screen_mcp_auth_header_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = formState.authToken,
                        onValueChange = { formState = formState.copy(authToken = it) },
                        label = { Text(stringResource(R.string.screen_mcp_auth_token_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = formState.customHeadersText,
                        onValueChange = { formState = formState.copy(customHeadersText = it) },
                        label = { Text(stringResource(R.string.screen_mcp_custom_headers_label)) },
                        placeholder = { Text(stringResource(R.string.screen_mcp_key_value_placeholder)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = formState.rawConfigText,
                    onValueChange = { formState = formState.copy(rawConfigText = it) },
                    label = { Text(stringResource(R.string.screen_mcp_raw_config_label)) },
                    placeholder = { Text(stringResource(R.string.screen_mcp_raw_config_placeholder)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                validationMessage?.let { message ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val config = buildConfig(formState).getOrNull() ?: return@TextButton
                    if (initialServer == null) {
                        onCreate(
                            McpServerCreateParams(
                                serverName = formState.serverName.trim(),
                                config = config,
                            )
                        )
                    } else {
                        onUpdate(
                            initialServer.id,
                            McpServerUpdateParams(
                                serverName = formState.serverName.trim(),
                                config = config,
                            )
                        )
                    }
                },
                enabled = validationMessage == null,
            ) {
                Text(
                    if (initialServer == null) {
                        stringResource(R.string.action_add)
                    } else {
                        stringResource(R.string.action_save)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun initialFormState(server: McpServer?): McpServerFormState {
    if (server == null) return McpServerFormState()

    val envText = server.effectiveEnv()?.entries
        ?.joinToString("\n") { (key, value) -> "$key=$value" }
        .orEmpty()
    val customHeadersText = server.effectiveCustomHeaders()?.entries
        ?.joinToString("\n") { (key, value) -> "$key=$value" }
        .orEmpty()

    return McpServerFormState(
        serverName = server.serverName,
        transportType = server.effectiveServerType() ?: if (server.effectiveCommand() != null) MCP_TYPE_STDIO else MCP_TYPE_STREAMABLE_HTTP,
        serverUrl = server.effectiveServerUrl().orEmpty(),
        command = server.effectiveCommand().orEmpty(),
        argsText = server.effectiveArgs().joinToString(" "),
        authHeader = server.effectiveAuthHeader().orEmpty(),
        authToken = server.effectiveAuthToken().orEmpty(),
        customHeadersText = customHeadersText,
        envText = envText,
        rawConfigText = "",
    )
}

private fun validateForm(state: McpServerFormState): String? {
    if (state.serverName.isBlank()) {
        return "Server name is required"
    }
    if (state.transportType == MCP_TYPE_STDIO && state.command.isBlank() && state.rawConfigText.isBlank()) {
        return "Command is required for stdio servers"
    }
    if (state.transportType != MCP_TYPE_STDIO && state.serverUrl.isBlank() && state.rawConfigText.isBlank()) {
        return "Server URL is required for HTTP-based servers"
    }
    if (state.transportType != MCP_TYPE_STDIO && state.serverUrl.isNotBlank()) {
        val url = state.serverUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Server URL must start with http:// or https://"
        }
    }
    if (state.rawConfigText.isNotBlank() && buildConfig(state).isFailure) {
        return "Raw config must be valid JSON"
    }
    if (parseKeyValueObject(state.customHeadersText).isFailure) {
        return "Custom headers must use one KEY=value pair per line"
    }
    if (parseKeyValueObject(state.envText).isFailure) {
        return "Environment variables must use one KEY=value pair per line"
    }
    return null
}

private fun buildConfig(state: McpServerFormState): Result<JsonObject> {
    if (state.rawConfigText.isNotBlank()) {
        return runCatching {
            Json.parseToJsonElement(state.rawConfigText).jsonObject
        }
    }

    return runCatching {
        val customHeaders = parseKeyValueObject(state.customHeadersText).getOrThrow()
        val env = parseKeyValueObject(state.envText).getOrThrow()
        val args = state.argsText.trim()
            .split(Regex("[\\s,]+"))
            .filter { it.isNotBlank() }

        buildJsonObject {
            put("mcp_server_type", JsonPrimitive(state.transportType))
            if (state.transportType == MCP_TYPE_STDIO) {
                put("command", JsonPrimitive(state.command.trim()))
                put("args", buildJsonArray { args.forEach { add(JsonPrimitive(it)) } })
                env?.let { put("env", it) }
            } else {
                put("server_url", JsonPrimitive(state.serverUrl.trim()))
                state.authHeader.trim().takeIf { it.isNotBlank() }?.let { put("auth_header", JsonPrimitive(it)) }
                state.authToken.trim().takeIf { it.isNotBlank() }?.let { put("auth_token", JsonPrimitive(it)) }
                customHeaders?.let { put("custom_headers", it) }
            }
        }
    }
}

private fun parseKeyValueObject(text: String): Result<JsonObject?> {
    if (text.isBlank()) return Result.success(null)

    return runCatching {
        buildJsonObject {
            text.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val separatorIndex = line.indexOf('=')
                    require(separatorIndex > 0 && separatorIndex < line.length - 1)
                    val key = line.substring(0, separatorIndex).trim()
                    val value = line.substring(separatorIndex + 1).trim()
                    require(key.isNotBlank())
                    put(key, JsonPrimitive(value))
                }
        }
    }
}
