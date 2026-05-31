package com.letta.mobile.ui.screens.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.McpServerId
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.icons.LettaIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(
    onNavigateBack: () -> Unit,
    onNavigateToServerTools: (String) -> Unit = {},
    viewModel: McpViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val defaultPhoneBridgeName = stringResource(R.string.screen_mcp_connect_phone_default_name)
    var showServerDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<McpServer?>(null) }
    var initialFormOverride by remember { mutableStateOf<McpServerFormState?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = com.letta.mobile.ui.theme.LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_mcp_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshAll() }) {
                        Icon(LettaIcons.Refresh, stringResource(R.string.action_refresh))
                    }
                },
                colors = com.letta.mobile.ui.theme.LettaTopBarDefaults.largeTopAppBarColors(),
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
                    Icon(LettaIcons.Add, stringResource(R.string.screen_mcp_dialog_add_title))
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
                onConnectPhone = {
                    editingServer = null
                    initialFormOverride = McpServerFormState(
                        serverName = defaultPhoneBridgeName,
                        transportType = "streamable_http",
                        authHeader = "Authorization",
                    )
                    showServerDialog = true
                },
                onDeleteServer = { viewModel.deleteServer(it.id) },
                onEditServer = {
                    editingServer = it
                    initialFormOverride = null
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
            initialFormStateOverride = initialFormOverride,
            onDismiss = {
                showServerDialog = false
                editingServer = null
                initialFormOverride = null
            },
            onCreate = { params ->
                viewModel.addServer(params)
                showServerDialog = false
                editingServer = null
                initialFormOverride = null
            },
            onUpdate = { serverId, params ->
                viewModel.updateServer(McpServerId(serverId), params)
                showServerDialog = false
                editingServer = null
                initialFormOverride = null
            },
        )
    }
}

private fun McpServerFormState(
    serverName: String = "",
    transportType: String = "streamable_http",
    authHeader: String = "",
): McpServerFormState = McpServerFormState(
    serverName = serverName,
    transportType = transportType,
    serverUrl = "",
    command = "",
    argsText = "",
    authHeader = authHeader,
    authToken = "",
    customHeadersText = "",
    envText = "",
    rawConfigText = "",
)

@Composable
private fun McpContent(
    state: McpUiState,
    onTabSelected: (Int) -> Unit,
    onConnectPhone: () -> Unit,
    onDeleteServer: (McpServer) -> Unit,
    onEditServer: (McpServer) -> Unit,
    onCheckServer: (McpServerId) -> Unit,
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
                onConnectPhone = onConnectPhone,
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
    toolParents: Map<ToolId, McpToolParent>,
    onNavigateToServerTools: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tools.isEmpty()) {
        EmptyState(
            icon = LettaIcons.Tool,
            message = stringResource(R.string.screen_tools_empty),
            modifier = modifier.fillMaxSize(),
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tools, key = { it.id.value }) { tool ->
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
    serverTools: Map<McpServerId, List<Tool>>,
    serverChecks: Map<McpServerId, McpServerCheckState>,
    onConnectPhone: () -> Unit,
    onDeleteServer: (McpServer) -> Unit,
    onEditServer: (McpServer) -> Unit,
    onCheckServer: (McpServerId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PhoneBridgeCard(
            onConnectPhone = onConnectPhone,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        if (servers.isEmpty()) {
            EmptyState(
                icon = LettaIcons.Storage,
                message = stringResource(R.string.screen_mcp_empty),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
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
}
