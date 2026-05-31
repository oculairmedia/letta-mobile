package com.letta.mobile.ui.screens.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.data.model.McpServer
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.effectiveArgs
import com.letta.mobile.data.model.effectiveAuthHeader
import com.letta.mobile.data.model.effectiveAuthToken
import com.letta.mobile.data.model.effectiveCommand
import com.letta.mobile.data.model.effectiveCustomHeaders
import com.letta.mobile.data.model.effectiveEnv
import com.letta.mobile.data.model.effectiveServerType
import com.letta.mobile.data.model.effectiveServerUrl
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.components.expressiveContentSize
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.dialogSectionHeading
import com.letta.mobile.ui.theme.listItemHeadline
import com.letta.mobile.ui.theme.listItemMetadata
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.util.formatRelativeTime

@Composable
internal fun PhoneBridgeCard(
    onConnectPhone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .expressiveContentSize(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.screen_mcp_connect_phone_title),
                style = MaterialTheme.typography.listItemHeadline,
            )
            Text(
                text = stringResource(R.string.screen_mcp_connect_phone_body),
                style = MaterialTheme.typography.listItemSupporting,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onConnectPhone) {
                Text(stringResource(R.string.screen_mcp_connect_phone_action))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolCard(
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
                Icon(LettaIcons.Tool, null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.listItemHeadline,
                    modifier = Modifier.weight(1f),
                )
            }

            tool.description?.let { description ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.listItemSupporting,
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
                    onClick = { onNavigateToServerTools(it.serverId.value) },
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
internal fun ServerCard(
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
    var showContextMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = LettaCardDefaults.listCardColors(),
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
                            style = MaterialTheme.typography.listItemHeadline,
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
                            style = MaterialTheme.typography.listItemSupporting,
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
                                style = MaterialTheme.typography.listItemMetadata,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = command,
                                style = MaterialTheme.typography.listItemSupporting,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    server.effectiveArgs().takeIf { it.isNotEmpty() }?.let { args ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = args.joinToString(" "),
                            style = MaterialTheme.typography.listItemSupporting,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    serverActivityText(server)?.let { activityText ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = activityText,
                            style = MaterialTheme.typography.listItemMetadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    server.effectiveAuthHeader()?.let { authHeader ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_auth_header, authHeader),
                            style = MaterialTheme.typography.listItemMetadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    server.effectiveAuthToken()?.let {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_token_present),
                            style = MaterialTheme.typography.listItemMetadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    server.effectiveCustomHeaders()?.takeIf { it.isNotEmpty() }?.let { headers ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_custom_headers_count, headers.size),
                            style = MaterialTheme.typography.listItemMetadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    server.effectiveEnv()?.takeIf { it.isNotEmpty() }?.let { env ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_env_count, env.size),
                            style = MaterialTheme.typography.listItemMetadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    server.organizationId?.let { organizationId ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_organization, organizationId),
                            style = MaterialTheme.typography.listItemMetadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    server.createdById?.let { createdById ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_created_by, createdById),
                            style = MaterialTheme.typography.listItemMetadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    server.lastUpdatedById?.let { lastUpdatedById ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_updated_by, lastUpdatedById),
                            style = MaterialTheme.typography.listItemMetadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (server.metadata.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_metadata_count, server.metadata.size),
                            style = MaterialTheme.typography.listItemMetadata,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (tools.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.screen_mcp_server_tools_count, tools.size),
                            style = MaterialTheme.typography.listItemMetadata,
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
                        style = MaterialTheme.typography.listItemMetadata,
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
                            style = MaterialTheme.typography.listItemSupporting,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                IconButton(onClick = { showContextMenu = true }) {
                    Icon(LettaIcons.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
            }

            if (expanded && tools.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.screen_mcp_server_discovered_tools),
                    style = MaterialTheme.typography.dialogSectionHeading,
                )
                tools.forEach { tool ->
                    Row(
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "• ${tool.name}",
                            style = MaterialTheme.typography.listItemSupporting,
                        )
                        tool.description?.let { desc ->
                            Text(
                                text = " - $desc",
                                style = MaterialTheme.typography.listItemSupporting,
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

    ActionSheet(
        show = showContextMenu,
        onDismiss = { showContextMenu = false },
        title = server.serverName,
    ) {
        ActionSheetItem(
            text = stringResource(R.string.screen_mcp_server_resync_action),
            icon = LettaIcons.Refresh,
            onClick = {
                showContextMenu = false
                onCheck()
            },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_edit),
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
                showDeleteDialog = true
            },
            destructive = true,
        )
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

internal fun serverActivityText(server: McpServer): String? {
    val updatedAt = server.updatedAt?.takeIf { it.isNotBlank() }
    val createdAt = server.createdAt?.takeIf { it.isNotBlank() }
    val timestamp = updatedAt ?: createdAt ?: return null
    val relative = formatRelativeTime(timestamp).takeIf { it.isNotBlank() } ?: return null
    return if (updatedAt != null) "Updated $relative" else "Created $relative"
}
