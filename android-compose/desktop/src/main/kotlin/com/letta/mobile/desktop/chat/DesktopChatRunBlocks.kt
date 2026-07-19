package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.Image
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.onClick
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.sp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.StepDotIcon
import com.letta.mobile.data.chat.runtime.ChatScreenStatus
import com.letta.mobile.data.chat.runtime.ChatViewportFollowPolicy
import com.letta.mobile.data.chat.runtime.ChatViewportSnapshot
import com.letta.mobile.data.chat.runtime.isConnectionRetryable
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalResponse
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.composer.AutocompleteTrigger
import com.letta.mobile.data.composer.ComposerAutocomplete
import com.letta.mobile.data.composer.ComposerEffort
import com.letta.mobile.data.diff.DiffLineKind
import com.letta.mobile.data.diff.UnifiedDiff
import com.letta.mobile.data.composer.MentionCatalog
import com.letta.mobile.data.composer.MentionKind
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.onboarding.AgentOnboarding
import com.letta.mobile.data.onboarding.OnboardingTask
import com.letta.mobile.data.onboarding.OnboardingTaskKind
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.render.rememberSmoothedStreamingText
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopControlText
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopTextArea
import com.letta.mobile.desktop.DesktopTooltip
import com.letta.mobile.ui.theme.customColors
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.jewel.ui.component.PopupMenu as JewelPopupMenu
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState

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
            text = "\uD83E\uDDE9 Skill: ${item.slug}" + (item.args.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
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

@Composable
internal fun DesktopRunBlock(item: ChatRenderItem.RunBlock, streamingMessageId: String? = null) {
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
        narration.forEach { AgentText(it.content, it.isError, isStreaming = it.id == streamingMessageId) }
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

/** Lifecycle state of a tool step, driving the leading status circle. */
internal enum class StepState { Done, Running, Error }

internal fun UiToolCall.stepState(): StepState = when (status?.lowercase()) {
    "completed", "success", "ok" -> StepState.Done
    "failed", "error" -> StepState.Error
    else -> StepState.Done
}

/** "Ran ./gradlew …" / "Read Foo.kt" — a friendly verb + short target. */
internal fun UiToolCall.stepLabel(): String {
    val n = name.lowercase()
    val verb = when {
        listOf("bash", "shell", "command", "exec", "run", "terminal").any { n.contains(it) } -> "Ran"
        listOf("read", "cat", "view", "open").any { n.contains(it) } -> "Read"
        listOf("write", "create").any { n.contains(it) } -> "Wrote"
        listOf("edit", "replace", "apply", "patch").any { n.contains(it) } -> "Edited"
        listOf("search", "grep", "glob", "find", "list").any { n.contains(it) } -> "Searched"
        listOf("fetch", "http", "web", "curl", "request").any { n.contains(it) } -> "Fetched"
        else -> null
    }
    val target = primaryToolArgument(arguments).lineSequence().firstOrNull()?.trim().orEmpty()
        .let { if (it.length > 52) it.take(52) + "…" else it }
    return if (verb != null && target.isNotBlank()) "$verb  $target" else if (verb != null) verb else name
}

/** Right-aligned step summary (result first line / duration). */
internal fun UiToolCall.stepSummary(): String {
    val resultLine = result?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotBlank() }
    return when {
        !resultLine.isNullOrBlank() && resultLine.length <= 28 -> resultLine
        executionTimeMs != null -> "${executionTimeMs} ms"
        else -> ""
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
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
                    text = "Run · ${toolCalls.size} step${if (toolCalls.size == 1) "" else "s"}",
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
                toolCall.arguments.takeIf { it.isNotBlank() }?.let { CodeBlock(primaryToolArgument(it)) }
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

/** Inset output block (monospace) with light per-line colorization. */
@Composable
internal fun ToolOutputBlock(text: String, isError: Boolean = false) {
    // Unified diffs (file-edit tool output) render as a reviewable diff block
    // (Penpot "Diff review") rather than plain monospace lines.
    if (!isError && UnifiedDiff.looksLikeDiff(text)) {
        DiffBlock(text)
        return
    }
    // The "Tool error + retry" board renders failed output on a dark-red inset
    // instead of the neutral surface, so the failure reads at a glance.
    val blockColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = blockColor,
    ) {
        SelectionContainer {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                text.trim().lineSequence().take(40).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (isError) MaterialTheme.colorScheme.error else outputLineColor(line),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Renders a unified diff (Penpot "Diff review"): a line-numbered gutter (old |
 * new) with red removed rows, green added rows, and muted hunk headers. Parsing
 * is shared via [UnifiedDiff]; git metadata (diff/index/--- /+++) is dropped.
 */
@Composable
internal fun DiffBlock(text: String) {
    val lines = remember(text) {
        UnifiedDiff.parse(text).filterNot { it.kind == DiffLineKind.FileHeader }
    }
    val added = Color(0xFF2EA043)
    val removed = Color(0xFFE5484D)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        SelectionContainer {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                lines.take(200).forEach { line ->
                    val (rowColor, marker, textColor) = when (line.kind) {
                        DiffLineKind.Added -> Triple(added.copy(alpha = 0.12f), "+", added)
                        DiffLineKind.Removed -> Triple(removed.copy(alpha = 0.12f), "-", removed)
                        DiffLineKind.Hunk -> Triple(
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                            "",
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Triple(Color.Transparent, "", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowColor)
                            .padding(horizontal = 8.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DiffGutter(line.oldLine)
                        DiffGutter(line.newLine)
                        Text(
                            text = marker,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = textColor,
                            modifier = Modifier.width(12.dp),
                        )
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = textColor,
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

@Composable
internal fun DiffGutter(lineNumber: Int?) {
    Text(
        text = lineNumber?.toString().orEmpty(),
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        maxLines = 1,
        modifier = Modifier.width(34.dp).padding(end = 6.dp),
    )
}

@Composable
internal fun outputLineColor(line: String): Color {
    val success = MaterialTheme.customColors.successColor.takeIf { it != Color.Unspecified }
        ?: MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return when {
        isUnifiedDiffAddedLine(line) -> success
        isUnifiedDiffRemovedLine(line) -> error
        isSuccessOutputLine(line) -> success
        isFailureOutputLine(line) -> error
        else -> muted
    }
}

private fun isUnifiedDiffAddedLine(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("+") && !trimmed.startsWith("+++")
}

private fun isUnifiedDiffRemovedLine(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("-") && !trimmed.startsWith("---")
}

private fun isSuccessOutputLine(line: String): Boolean {
    val lower = line.lowercase()
    return lower.contains("build successful") || lower.contains("success") || lower.contains("passed")
}

private fun isFailureOutputLine(line: String): Boolean {
    val lower = line.lowercase()
    return lower.contains("error") ||
        lower.contains("failed") ||
        lower.contains("exception") ||
        lower.contains("fatal")
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
