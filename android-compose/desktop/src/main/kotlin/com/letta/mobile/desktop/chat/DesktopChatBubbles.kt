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
internal fun DesktopMessageBubble(
    message: UiMessage,
    streamingMessageId: StreamingMessageId? = null,
) {
    if (message.role == "user") {
        UserPrompt(message)
        return
    }
    // Assistant message (standalone): reasoning / text / tool cards, full-width.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (message.isReasoning && message.content.isNotBlank()) {
            ReasoningRow(message.content)
        } else if (message.content.isNotBlank()) {
            AgentText(
                AgentTextParams(
                    text = message.content,
                    isError = message.isError,
                    isStreaming = streamingMessageId?.value == message.id,
                ),
            )
        }
        DesktopImageAttachmentsGrid(
            attachments = message.attachments,
            modifier = Modifier.fillMaxWidth(),
        )
        message.toolCalls.orEmpty().forEach { toolCall -> ToolCard(toolCall) }
        message.generatedUi?.let { GeneratedUiCard(it) }
        message.approvalRequest?.let { ApprovalRequestCard(it) }
        message.approvalResponse?.let { ApprovalResponseCard(it) }
    }
}

/**
 * Small, functional copy affordance: click copies [text] to the clipboard and
 * the glyph briefly flips to a green check. Replaces the former decorative copy
 * glyphs that did nothing on click.
 */
@Composable
internal fun CopyIconButton(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }
    Icon(
        imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
        contentDescription = if (copied) "Copied" else "Copy",
        tint = if (copied) Color(0xFF34C759) else tint,
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                clipboard.setText(AnnotatedString(text))
                copied = true
            }
            .padding(2.dp)
            .size(14.dp),
    )
}

/** User prompt — teal bubble, right-aligned, with a copy affordance. */
@Composable
internal fun UserPrompt(message: UiMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (message.content.isNotBlank()) {
                    SelectionContainer {
                        Text(text = message.content, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                DesktopImageAttachmentsGrid(
                    attachments = message.attachments,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // Copy is the only wired affordance — a message "edit"/resend needs
        // conversation-fork support that isn't in place, so it's omitted rather
        // than shown as a dead control.
        if (message.content.isNotBlank()) {
            CopyIconButton(
                text = message.content,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * Inline "thinking" indicator shown in the thread while a send is in flight:
 * the agent's teal sphere avatar + three breathing dots, so the user gets
 * immediate feedback before the response starts streaming.
 */
@Composable
internal fun ThinkingMessageRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgentActivityOrb(size = 40.dp, activity = AgentActivity.Working)
        val transition = rememberInfiniteTransition(label = "thinkingDots")
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { index ->
                val alpha by transition.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 600, delayMillis = index * 160, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "thinkingDot$index",
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

/** Formats a message's ISO timestamp as a local clock label, e.g. "9:41 AM". */
internal fun messageClockLabel(iso: String): String? {
    if (iso.isBlank()) return null
    val zone = java.time.ZoneId.systemDefault()
    val zoned = runCatching { java.time.Instant.parse(iso).atZone(zone) }
        .recoverCatching { java.time.OffsetDateTime.parse(iso).atZoneSameInstant(zone) }
        .recoverCatching { java.time.LocalDateTime.parse(iso).atZone(zone) }
        .getOrNull() ?: return null
    return zoned.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
}

/**
 * The id of the assistant message inside this render item that should be
 * smoothed while a reply is in flight: the newest non-reasoning, non-tool,
 * non-blank assistant narration. Within a [ChatRenderItem.RunBlock] the
 * messages are in chat order (oldest first), so the newest narration is the
 * last matching one. Returns null for user prompts or items with no narration.
 */
internal fun ChatRenderItem.streamingCandidateMessageId(): String? = when (this) {
    is ChatRenderItem.SkillEnvelopeChip -> null
    is ChatRenderItem.Single -> message
        .takeIf { it.role != "user" && !it.isReasoning && it.toolCalls.isNullOrEmpty() && it.content.isNotBlank() }
        ?.id
    is ChatRenderItem.RunBlock -> messages
        .map { it.first }
        .lastOrNull { !it.isReasoning && it.toolCalls.isNullOrEmpty() && it.content.isNotBlank() }
        ?.id
}

/** Plain agent narration text, full width (no bubble), per the detailed board. */
internal data class AgentTextParams(
    val text: String,
    val isError: Boolean,
    val isStreaming: Boolean = false,
)

@Composable
internal fun AgentText(text: String, isError: Boolean, isStreaming: Boolean = false) =
    AgentText(AgentTextParams(text = text, isError = isError, isStreaming = isStreaming))

@Composable
internal fun AgentText(params: AgentTextParams) {
    // While the agent's reply is still landing, reveal the latest assistant
    // message progressively via the shared smoother (the same hook Android
    // uses) instead of snapping in each raw chunk. Settled history renders the
    // full text directly. The smoother continues revealing the buffered tail at
    // its own cadence even after [isStreaming] flips back to false, so the reply
    // still finishes smoothly once the in-flight signal clears.
    val displayText = if (params.isStreaming) {
        rememberSmoothedStreamingText(rawText = params.text, isStreaming = true)
    } else {
        params.text
    }
    com.letta.mobile.ui.markdown.SharedMarkdownText(
        text = displayText,
        textColor = if (params.isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
}

/**
 * Full, collapsible single-tool card matching the Penpot "Tool call (expanded)"
 * board: terminal glyph + name + green outlined success badge + copy/chevron,
 * the command, an inset output block, and an exit-code footer.
 */
@Composable
internal fun ToolCard(toolCall: UiToolCall) {
    var expanded by remember { mutableStateOf(true) }
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
            text = toolCall.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        ToolStatusBadge(toolCall.status ?: "tool call")
        Spacer(Modifier.weight(1f))
        CopyIconButton(text = toolCall.copyPayload())
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
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        toolCall.arguments.takeIf { it.isNotBlank() }?.let { args ->
            SelectionContainer {
                Text(
                    text = "$ ${primaryToolArgument(args)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        toolCall.result?.takeIf { it.isNotBlank() }?.let { ToolOutputBlock(it, isError = isError) }
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

private fun UiToolCall.isErrorStatus(): Boolean =
    status?.let {
        it.equals("error", ignoreCase = true) || it.equals("failed", ignoreCase = true)
    } == true

private fun UiToolCall.copyPayload(): String =
    listOfNotNull(
        arguments.takeIf { it.isNotBlank() },
        result?.takeIf { it.isNotBlank() },
    ).joinToString("\n\n").ifBlank { name }

/**
 * Pull the human-meaningful payload out of a tool-call arguments JSON object
 * (e.g. the shell command / code / query) so the card shows that instead of a
 * raw `{"command":"…"}` dump. Falls back to pretty-printed JSON, then the raw
 * string.
 */
internal fun primaryToolArgument(raw: String): String {
    val obj = parseToolArgumentsObject(raw) ?: return raw
    return preferredToolArgument(obj) ?: prettyToolArguments(obj, fallback = raw)
}

private fun parseToolArgumentsObject(raw: String): JsonObject? =
    runCatching { desktopChatJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()

private fun preferredToolArgument(obj: JsonObject): String? {
    for (key in PREFERRED_TOOL_ARGUMENT_KEYS) {
        val value = obj[key] as? JsonPrimitive ?: continue
        if (value.isString && value.content.isNotBlank()) return value.content
    }
    return null
}

private fun prettyToolArguments(obj: JsonObject, fallback: String): String =
    runCatching { prettyDesktopJson.encodeToString(JsonObject.serializer(), obj) }.getOrDefault(fallback)

private val PREFERRED_TOOL_ARGUMENT_KEYS = listOf(
    "command", "code", "query", "input", "text", "content", "cmd", "script", "expression",
)

private val prettyDesktopJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

@Composable
internal fun GeneratedUiCard(generatedUi: UiGeneratedComponent) {
    ArtifactCard(
        icon = Icons.Outlined.Widgets,
        title = generatedUi.name,
        status = "A2UI",
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
        status = approvalRequest.requestId,
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
        status = approvalResponse.requestId ?: "response",
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
    status: String,
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
internal fun ToolStatusBadge(status: String) {
    val isSuccess = status.equals("success", ignoreCase = true) ||
        status.equals("completed", ignoreCase = true) || status.equals("ok", ignoreCase = true)
    val isError = status.equals("error", ignoreCase = true) || status.equals("failed", ignoreCase = true)
    val color = when {
        isSuccess -> MaterialTheme.customColors.successColor.takeIf { it != Color.Unspecified }
            ?: MaterialTheme.colorScheme.primary
        isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = Color.Transparent,
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f)),
    ) {
        Text(
            text = status,
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

@Composable
internal fun ChatScreenStatus.statusColor(): Color = when (this) {
    is ChatScreenStatus.Ready -> if (isSending) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primary
    }
    is ChatScreenStatus.NoConversations -> MaterialTheme.colorScheme.tertiary
    is ChatScreenStatus.Loading -> MaterialTheme.colorScheme.secondary
    is ChatScreenStatus.ConfigNeeded,
    is ChatScreenStatus.BackendOffline,
    is ChatScreenStatus.SendFailed -> MaterialTheme.colorScheme.error
}

internal fun ChatScreenStatus.statusIcon(): ImageVector = when (this) {
    is ChatScreenStatus.Ready -> Icons.Outlined.CheckCircle
    is ChatScreenStatus.NoConversations -> Icons.Outlined.SmartToy
    is ChatScreenStatus.Loading -> Icons.Outlined.HourglassEmpty
    is ChatScreenStatus.ConfigNeeded,
    is ChatScreenStatus.BackendOffline,
    is ChatScreenStatus.SendFailed -> Icons.Outlined.ErrorOutline
}

internal fun ChatScreenStatus.panelTitle(): String = when (this) {
    is ChatScreenStatus.Loading -> "Connecting"
    is ChatScreenStatus.ConfigNeeded -> "Backend configuration required"
    is ChatScreenStatus.BackendOffline -> "Backend offline"
    is ChatScreenStatus.NoConversations -> "No conversations"
    is ChatScreenStatus.SendFailed -> "Send failed"
    is ChatScreenStatus.Ready -> if (isSending) "Sending" else "Live"
}

internal fun ChatScreenStatus.panelBody(): String = when (this) {
    is ChatScreenStatus.Loading -> "Loading conversations from the configured Letta backend."
    is ChatScreenStatus.ConfigNeeded -> "Set a server URL and token in Settings before connecting."
    is ChatScreenStatus.BackendOffline -> "The configured backend could not be reached."
    is ChatScreenStatus.NoConversations -> "This backend returned no conversations for the active account."
    is ChatScreenStatus.SendFailed -> "The last send failed. You can edit and try again."
    is ChatScreenStatus.Ready -> if (isSending) {
        "Sending your message to the active conversation."
    } else {
        "Connected to the configured backend."
    }
}

internal fun UiMessage.senderLabel(): String = when {
    role == "user" -> "You"
    isReasoning -> "Reasoning"
    isError -> "Error"
    toolCalls?.isNotEmpty() == true -> "Tool"
    approvalRequest != null -> "Approval"
    generatedUi != null -> "Generated UI"
    else -> "Assistant"
}

@Composable
internal fun StepDotIcon.icon(): ImageVector = when (this) {
    StepDotIcon.Reasoning -> Icons.Outlined.Psychology
    StepDotIcon.ToolCall -> Icons.Outlined.Build
    StepDotIcon.Approval -> Icons.Outlined.CheckCircle
    StepDotIcon.AssistantText -> Icons.Outlined.SmartToy
    StepDotIcon.Unknown -> Icons.Outlined.HourglassEmpty
}

@Composable
internal fun StepDotIcon.containerColor(): Color = when (this) {
    StepDotIcon.Reasoning -> MaterialTheme.colorScheme.tertiaryContainer
    StepDotIcon.ToolCall -> MaterialTheme.colorScheme.secondaryContainer
    StepDotIcon.Approval -> MaterialTheme.colorScheme.primaryContainer
    StepDotIcon.AssistantText -> MaterialTheme.colorScheme.surface
    StepDotIcon.Unknown -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
internal fun StepDotIcon.contentColor(): Color = when (this) {
    StepDotIcon.Reasoning -> MaterialTheme.colorScheme.onTertiaryContainer
    StepDotIcon.ToolCall -> MaterialTheme.colorScheme.onSecondaryContainer
    StepDotIcon.Approval -> MaterialTheme.colorScheme.onPrimaryContainer
    StepDotIcon.AssistantText -> MaterialTheme.colorScheme.onSurface
    StepDotIcon.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
}
