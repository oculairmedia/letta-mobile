package com.letta.mobile.ui.screens.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.data.model.McpServerResyncResult
import com.letta.mobile.data.model.McpToolExecutionResult
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.effectiveServerType
import com.letta.mobile.data.model.effectiveServerUrl
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServerToolsScreen(
    onNavigateBack: () -> Unit,
    viewModel: McpServerToolsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingRunTool by remember { mutableStateOf<Tool?>(null) }
    val context = LocalContext.current

    LaunchedEffect((uiState as? UiState.Success)?.data?.toolRunState?.result) {
        val result = (uiState as? UiState.Success)?.data?.toolRunState?.result ?: return@LaunchedEffect
        copyToClipboard(
            context = context,
            label = context.getString(R.string.screen_mcp_tool_execution_dialog_title),
            text = buildExecutionClipboardText(result),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val serverName = (uiState as? UiState.Success)?.data?.server?.serverName
                    Text(serverName ?: stringResource(R.string.screen_mcp_server_tools_title))
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshServerTools() }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh))
                    }
                },
            )
        },
    ) { paddingValues ->
        when (val state = uiState) {
            is UiState.Loading -> ShimmerCard(modifier = Modifier.padding(16.dp))
            is UiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.loadServerTools() },
                modifier = Modifier.padding(paddingValues),
            )
            is UiState.Success -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                ) {
                    ServerSummaryCard(
                        state.data.server.serverName,
                        state.data.server.effectiveServerUrl(),
                        state.data.server.effectiveServerType(),
                        state.data.tools.size,
                        state.data.refreshSummary,
                    )
                    if (state.data.tools.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.Build,
                            message = stringResource(R.string.screen_mcp_server_tools_empty),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.data.tools, key = { it.id }) { tool ->
                                McpServerToolCard(
                                    tool = tool,
                                    isRunning = state.data.toolRunState.activeToolId == tool.id && state.data.toolRunState.result == null && state.data.toolRunState.errorMessage == null,
                                    onRun = { pendingRunTool = tool },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val successState = (uiState as? UiState.Success)?.data
    if (pendingRunTool != null) {
        ToolRunDialog(
            tool = pendingRunTool!!,
            onDismiss = { pendingRunTool = null },
            onRun = { rawArgs ->
                viewModel.runTool(pendingRunTool!!.id, rawArgs)
                pendingRunTool = null
            },
        )
    }
    successState?.toolRunState?.result?.let { result ->
        ToolExecutionResultDialog(
            result = result,
            onDismiss = { viewModel.clearToolRunState() },
        )
    }
    successState?.toolRunState?.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearToolRunState() },
            title = { Text(stringResource(R.string.common_error)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearToolRunState() }) {
                    Text(stringResource(R.string.action_dismiss))
                }
            },
        )
    }
}

@Composable
private fun ServerSummaryCard(
    serverName: String,
    serverUrl: String?,
    serverType: String?,
    toolCount: Int,
    refreshSummary: McpServerResyncResult?,
) {
    Card(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(serverName, style = MaterialTheme.typography.titleMedium)
                serverType?.let {
                    Spacer(modifier = Modifier.weight(1f))
                    AssistChip(onClick = {}, label = { Text(it) })
                }
            }
            serverUrl?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.screen_mcp_server_tools_count, toolCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            refreshSummary?.let { summary ->
                val summaryText = buildRefreshSummary(summary)
                if (summaryText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun McpServerToolCard(
    tool: Tool,
    isRunning: Boolean,
    onRun: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tool.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onRun, enabled = !isRunning) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (isRunning) {
                            stringResource(R.string.screen_mcp_tool_run_running)
                        } else {
                            stringResource(R.string.screen_mcp_tool_run_action)
                        }
                    )
                }
            }
            tool.description?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            tool.toolType?.let {
                Spacer(modifier = Modifier.height(8.dp))
                AssistChip(onClick = {}, label = { Text(it) })
            }
        }
    }
}

@Composable
private fun ToolRunDialog(
    tool: Tool,
    onDismiss: () -> Unit,
    onRun: (String) -> Unit,
) {
    var rawArgs by remember(tool.id) { mutableStateOf("{}") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_mcp_tool_run_dialog_title, tool.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tool.description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = rawArgs,
                    onValueChange = { rawArgs = it },
                    label = { Text(stringResource(R.string.screen_mcp_tool_run_args_label)) },
                    supportingText = { Text(stringResource(R.string.screen_mcp_tool_run_args_helper)) },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRun(rawArgs) }) {
                Text(stringResource(R.string.screen_mcp_tool_run_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ToolExecutionResultDialog(
    result: McpToolExecutionResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screen_mcp_tool_execution_dialog_title)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        text = stringResource(R.string.screen_mcp_tool_execution_status, result.status),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                result.funcReturn?.let {
                    item {
                        Text(stringResource(R.string.screen_mcp_tool_execution_result_label), style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = it.toString(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
                result.stdout?.takeIf { it.isNotEmpty() }?.let { stdout ->
                    item {
                        Text(stringResource(R.string.screen_mcp_tool_execution_stdout_label), style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = stdout.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
                result.stderr?.takeIf { it.isNotEmpty() }?.let { stderr ->
                    item {
                        Text(stringResource(R.string.screen_mcp_tool_execution_stderr_label), style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = stderr.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                result.sandboxConfigFingerprint?.let {
                    item {
                        Text(
                            text = stringResource(R.string.screen_mcp_tool_execution_fingerprint, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

private fun buildRefreshSummary(summary: McpServerResyncResult): String? {
    val parts = buildList {
        if (summary.added.isNotEmpty()) add("+${summary.added.size} added")
        if (summary.updated.isNotEmpty()) add("~${summary.updated.size} updated")
        if (summary.deleted.isNotEmpty()) add("-${summary.deleted.size} removed")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

private fun buildExecutionClipboardText(result: McpToolExecutionResult): String {
    return buildString {
        append("Status: ${result.status}")
        result.funcReturn?.let {
            append("\n\nResult:\n")
            append(it.toString())
        }
        result.stdout?.takeIf { it.isNotEmpty() }?.let {
            append("\n\nStdout:\n")
            append(it.joinToString("\n"))
        }
        result.stderr?.takeIf { it.isNotEmpty() }?.let {
            append("\n\nStderr:\n")
            append(it.joinToString("\n"))
        }
    }
}

private fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(R.string.action_copied), Toast.LENGTH_SHORT).show()
    }
}
