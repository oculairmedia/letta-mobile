package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalResponse
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.theme.customColors

/**
 * Full, collapsible single-tool card matching the Penpot "Tool call (expanded)"
 * board: terminal glyph + name + green outlined success badge + copy/chevron,
 * the command, an inset output block, and an exit-code footer.
 */
@Composable
internal fun ToolCard(
    toolCall: UiToolCall,
    disclosureKey: String = toolCall.disclosureKey(),
) {
    var expanded by remember(disclosureKey) { mutableStateOf(toolCall.shouldInitiallyExpand()) }
    val isError = toolCall.isErrorStatus()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column {
            ToolCardHeader(
                toolCall = toolCall,
                expanded = expanded,
                onToggle = { expanded = !expanded },
            )
            if (expanded) {
                ToolCardBody(toolCall = toolCall, isError = isError)
            }
        }
    }
}

@Composable
private fun ToolCardHeader(
    toolCall: UiToolCall,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val collapsedSummary = toolCall.stepLabel()
        .takeUnless { it == toolCall.name }
        ?: toolCall.stepSummary()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tool-card-toggle")
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Terminal,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = toolCall.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (!expanded && collapsedSummary.isNotBlank()) {
            Text(
                text = collapsedSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        ToolStatusBadge(ToolStatusToken(toolCall.status ?: "tool call"))
        if (expanded) Spacer(Modifier.weight(1f))
        CopyIconButton(
            text = toolCall.copyPayload(),
            contentDescription = "Copy tool call",
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToolCardBody(toolCall: UiToolCall, isError: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
    Column(
        modifier = Modifier
            .testTag("tool-card-body")
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        toolCall.arguments.takeIf { it.isNotBlank() }?.let { args ->
            SelectionContainer {
                Text(
                    text = "$ ${primaryToolArgument(ToolArgumentPayload(args))}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        toolCall.result?.takeIf { it.isNotBlank() }?.let { result ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Output",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                CopyIconButton(
                    text = result,
                    contentDescription = "Copy output",
                )
            }
            ToolOutputBlock(result, isError = isError)
        }
        DesktopImageAttachmentsGrid(
            attachments = toolCall.generatedImageAttachments,
            modifier = Modifier.fillMaxWidth(),
        )
        toolCall.executionTimeMs?.let { ms ->
            Text(
                text = "${toolCall.status?.replaceFirstChar { it.uppercase() } ?: "Done"} · ${ms} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun GeneratedUiCard(generatedUi: UiGeneratedComponent) {
    ArtifactCard(
        icon = Icons.Outlined.Widgets,
        title = generatedUi.name,
        status = ToolStatusToken("A2UI"),
    ) {
        generatedUi.fallbackText?.takeIf { it.isNotBlank() }?.let { fallback ->
            Text(
                text = fallback,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = generatedUi.propsJson,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ApprovalRequestCard(approvalRequest: UiApprovalRequest) {
    ArtifactCard(
        icon = Icons.Outlined.CheckCircle,
        title = "Approval requested",
        status = ToolStatusToken(approvalRequest.requestId),
    ) {
        approvalRequest.toolCalls.forEach { toolCall ->
            Text(
                text = "${toolCall.name} - ${toolCall.arguments}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun ApprovalResponseCard(approvalResponse: UiApprovalResponse) {
    val label = when (approvalResponse.approved) {
        true -> "Approved"
        false -> "Rejected"
        null -> "Approval response"
    }
    ArtifactCard(
        icon = if (approvalResponse.approved == false) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
        title = label,
        status = ToolStatusToken(approvalResponse.requestId ?: "response"),
    ) {
        approvalResponse.reason?.takeIf { it.isNotBlank() }?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (approvalResponse.approvals.isNotEmpty()) {
            Text(
                text = "${approvalResponse.approvals.size} tool decisions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ArtifactCard(
    icon: ImageVector?,
    title: String,
    status: ToolStatusToken,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ToolStatusBadge(status)
                Spacer(Modifier.weight(1f))
            }
            content()
        }
    }
}

/**
 * Outlined status badge for tool cards (Penpot: green-bordered "success",
 * red-bordered "error", muted otherwise).
 */
@Composable
internal fun ToolStatusBadge(status: String) =
    ToolStatusBadge(ToolStatusToken(status))

@Composable
internal fun ToolStatusBadge(status: ToolStatusToken) {
    val color = when {
        status.isSuccess() -> MaterialTheme.customColors.successColor.takeIf { it != Color.Unspecified }
            ?: MaterialTheme.colorScheme.primary
        status.isError() -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = Color.Transparent,
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f)),
    ) {
        Text(
            text = status.value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Monospace code block matching the mockup's #262626 inset. */
@Composable
internal fun CodeBlock(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
