package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Terminal
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiToolCall

@Composable
internal fun DesktopSkillEnvelopeChip(item: ChatRenderItem.SkillEnvelopeChip) {
    // Desktop parity for the mobile skill-envelope chip: collapsed one-liner,
    // click to expand the raw envelope (monospace). Mirrors PR #852 mobile UI.
    var expanded by remember(item.messageId) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = skillEnvelopeLabel(item),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (expanded) {
            Text(
                text = item.rawContent,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@JvmInline
internal value class SkillSlug(val value: String)

@JvmInline
internal value class SkillArgs(val value: String)

private fun skillEnvelopeLabel(item: ChatRenderItem.SkillEnvelopeChip): String {
    val slug = SkillSlug(item.slug)
    val args = SkillArgs(item.args)
    val suffix = args.value.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
    return "\uD83E\uDDE9 Skill: ${slug.value}$suffix"
}

@JvmInline
internal value class StreamingMessageId(val value: String)

@Composable
internal fun DesktopRunBlock(
    item: ChatRenderItem.RunBlock,
    streamingMessageId: StreamingMessageId? = null,
) {
    val messages = item.messages.map { it.first }
    val reasoning = messages.filter { it.isReasoning && it.content.isNotBlank() }
    val toolCalls = messages.flatMap { it.toolCalls.orEmpty() }
    val narration = messages.filter {
        !it.isReasoning && it.toolCalls.isNullOrEmpty() && it.content.isNotBlank()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        reasoning.forEach { ReasoningRow(it.content) }
        if (toolCalls.isNotEmpty()) {
            RunStepsCard(toolCalls)
        }
        narration.forEach { message ->
            AgentText(
                AgentTextParams(
                    text = message.content,
                    isError = message.isError,
                    isStreaming = streamingMessageId?.value == message.id,
                ),
            )
        }
        messages.forEach { message ->
            message.generatedUi?.let { GeneratedUiCard(it) }
            message.approvalRequest?.let { ApprovalRequestCard(it) }
            message.approvalResponse?.let { ApprovalResponseCard(it) }
            DesktopImageAttachmentsGrid(
                attachments = message.attachments,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun ReasoningRow(text: String) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(16.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Text(
                text = "Thought",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            SelectionContainer {
                Text(
                    text = text.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

/** Compact, collapsible "Run · N steps" card with one row per tool call. */
@Composable
internal fun RunStepsCard(toolCalls: List<UiToolCall>) {
    var expanded by remember { mutableStateOf(true) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            RunStepsCardHeader(
                stepCount = toolCalls.size,
                expanded = expanded,
                onToggle = { expanded = !expanded },
            )
            if (expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    toolCalls.forEach { ToolStepRow(it) }
                }
            }
        }
    }
}

@Composable
private fun RunStepsCardHeader(
    stepCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            text = "Run · $stepCount step${if (stepCount == 1) "" else "s"}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
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
internal fun ToolStepRow(toolCall: UiToolCall) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StepStatusCircle(toolCall.stepState())
            Text(
                text = toolCall.stepLabel(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val summary = toolCall.stepSummary()
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (open) {
            Column(
                modifier = Modifier.padding(start = 28.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                toolCall.arguments.takeIf { it.isNotBlank() }?.let { CodeBlock(primaryToolArgument(ToolArgumentPayload(it))) }
                toolCall.result?.takeIf { it.isNotBlank() }?.let { ToolOutputBlock(it) }
                DesktopImageAttachmentsGrid(
                    attachments = toolCall.generatedImageAttachments,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun StepStatusCircle(state: StepState) {
    val teal = MaterialTheme.colorScheme.primary
    when (state) {
        StepState.Done -> Box(
            modifier = Modifier.size(16.dp).background(teal, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "done",
                modifier = Modifier.size(11.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        StepState.Running -> Box(
            modifier = Modifier
                .size(16.dp)
                .border(1.5.dp, teal, CircleShape),
        )
        StepState.Error -> Box(
            modifier = Modifier.size(16.dp).background(MaterialTheme.colorScheme.error, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "failed",
                modifier = Modifier.size(11.dp),
                tint = MaterialTheme.colorScheme.onError,
            )
        }
    }
}

internal val ChatColumnMaxWidth = 760.dp

/** A composer slash-command (shown when the message starts with "/"). */
data class ComposerCommand(
    val label: String,
    val description: String,
    /**
     * When true, selecting the command fills the composer (e.g. a server slash
     * command the user then edits/sends) rather than running an app action. The
     * palette won't intercept Enter for these, so the message can still be sent.
     */
    val fillsComposer: Boolean = false,
    val run: () -> Unit,
)
