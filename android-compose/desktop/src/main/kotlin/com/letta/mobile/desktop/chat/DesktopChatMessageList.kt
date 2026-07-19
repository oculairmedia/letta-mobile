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
internal fun MessageList(
    conversationId: String?,
    renderItems: List<ChatRenderItem>,
    isSending: Boolean,
    isStreamingReply: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val isUserScrolling by listState.interactionSource.collectIsDraggedAsState()
    val scope = rememberCoroutineScope()
    var followLatest by remember(conversationId) { mutableStateOf(true) }
    val latestItemKey = renderItems.lastOrNull()?.key

    // The single assistant message currently being revealed: the newest
    // assistant narration of the tail (bottom-most) render item, but only while
    // the reply is actively streaming ([isStreamingReply], which spans the whole
    // stream — unlike [isSending]/"thinking", which clears at the first token).
    // Everything else is settled history.
    val streamingMessageId = if (isStreamingReply) renderItems.lastOrNull()?.streamingCandidateMessageId() else null

    // The LazyColumn is laid out as [ "__today__" header, ...renderItems,
    // ("__thinking__" while sending)? ]. The leading header offsets every render
    // row by one, so the last row is at index renderItems.size (not size - 1),
    // and the thinking row at size + 1. The scroll targets below must use this
    // header-aware index — latestIndex(renderItems.size) landed one row short,
    // which is why a fresh prompt/reply needed a manual nudge to the bottom.
    val chatBottomIndex = (renderItems.size + if (isSending) 1 else 0).coerceAtLeast(0)
    // Length of the tail render item's text, so the follow effect re-fires as the
    // streamed reply grows (its key/count stay fixed while only content changes).
    val tailContentLength = renderItems.lastOrNull()?.let { item ->
        when (item) {
            is ChatRenderItem.Single -> item.message.content.length
            is ChatRenderItem.RunBlock -> item.messages.sumOf { it.first.content.length }
            is ChatRenderItem.SkillEnvelopeChip -> item.rawContent.length
        }
    } ?: 0

    LaunchedEffect(conversationId) {
        followLatest = true
        if (renderItems.isNotEmpty()) {
            listState.scrollToItem(chatBottomIndex)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.toChatViewportSnapshot(isUserScrolling) }
            .distinctUntilChanged()
            .collect { snapshot ->
                followLatest = ChatViewportFollowPolicy.nextFollowModeAfterScroll(
                    currentFollowMode = followLatest,
                    snapshot = snapshot,
                )
            }
    }

    LaunchedEffect(latestItemKey, renderItems.size, tailContentLength, isSending) {
        if (ChatViewportFollowPolicy.shouldAutoFollow(followLatest, renderItems.size)) {
            listState.scrollToItem(chatBottomIndex)
        }
    }

    // When a send starts, always jump to the bottom (the thinking row, then the
    // streaming reply) regardless of where the user was reading — followLatest is
    // forced so the auto-follow effect above keeps tracking the reply as it lands.
    LaunchedEffect(isSending) {
        if (isSending) {
            followLatest = true
            listState.animateScrollToItem(chatBottomIndex)
        }
    }

    // Soft gradient fades at the top/bottom of the list so content dissolves
    // into the background instead of hard-clipping where it meets the title bar
    // and the composer (mirrors the mobile chat fading edges). Normal
    // (non-reversed) layout: the top fades when there's scrolled-up content
    // (canScrollBackward), the bottom when more is below (canScrollForward).
    // Animated + gated so a fade only appears when there's content to fade into.
    val showTopFade by remember(listState) { derivedStateOf { listState.canScrollBackward } }
    val showBottomFade by remember(listState) { derivedStateOf { listState.canScrollForward } }
    val topFadeAlpha by animateFloatAsState(
        targetValue = if (showTopFade) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "topFadeAlpha",
    )
    val bottomFadeAlpha by animateFloatAsState(
        targetValue = if (showBottomFade) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "bottomFadeAlpha",
    )

    Box(modifier = modifier.fillMaxWidth()) {
        // The fade wraps ONLY the list (not the scroll-to-latest button, which is
        // a sibling below) so the button is never dimmed by the bottom fade.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .chatFadingEdges(
                    topFadeAlpha = topFadeAlpha,
                    bottomFadeAlpha = bottomFadeAlpha,
                    fadeLength = 44.dp,
                ),
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            // Vertical breathing room as CONTENT padding, not a viewport inset, so
            // the scroll area itself runs to the top/bottom edges. Content then
            // clips exactly where the fade reaches full transparency — no faint
            // line from content ending mid-gradient.
            contentPadding = PaddingValues(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "__today__") {
                Text(
                    text = "Today",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            items(
                items = renderItems,
                key = { it.key },
            ) { item ->
                Column(modifier = Modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth()) {
                    // Message text is selectable (SelectionContainer), which is the
                    // copy path. The floating hover copy toolbar was removed — it
                    // popped over the content on every hover and read as noise while
                    // reading/scrolling.
                    when (item) {
                        is ChatRenderItem.Single -> DesktopMessageBubble(item.message, streamingMessageId)
                        is ChatRenderItem.RunBlock -> DesktopRunBlock(item, streamingMessageId)
                        is ChatRenderItem.SkillEnvelopeChip -> DesktopSkillEnvelopeChip(item)
                    }
                    // Per-message clock timestamp (Penpot "Grouping + timestamps"),
                    // aligned to the sender side.
                    messageClockLabel(item.boundaryTimestamp)?.let { clock ->
                        val isUser = (item as? ChatRenderItem.Single)?.message?.role == "user"
                        Text(
                            text = clock,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            textAlign = if (isUser) TextAlign.End else TextAlign.Start,
                            modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 2.dp),
                        )
                    }
                }
            }
            if (isSending) {
                item(key = "__thinking__") {
                    Box(modifier = Modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth()) {
                        ThinkingMessageRow()
                    }
                }
            }
        }
        }

        if (ChatViewportFollowPolicy.shouldShowScrollToLatest(listState.toChatViewportSnapshot(isUserScrolling))) {
            Surface(
                onClick = {
                    followLatest = true
                    scope.launch {
                        listState.animateScrollToItem(chatBottomIndex)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "Scroll to latest message",
                    )
                }
            }
        }
    }
}

/**
 * Softly dissolves the top/bottom [fadeLength] of the wrapped content to
 * transparent, so the message list grades into the background instead of
 * hard-clipping at the title bar / composer — the desktop port of the mobile
 * chat fading edges. A [BlendMode.DstIn] vertical-gradient mask over an
 * offscreen layer (DstIn keeps the already-drawn content only where the mask is
 * opaque, so a transparent→opaque ramp makes each edge fade out). The mask
 * colour is irrelevant; only its alpha drives the fade. No-ops (and skips the
 * offscreen layer) when both alphas are 0, i.e. the list isn't scrollable.
 */
internal fun Modifier.chatFadingEdges(
    topFadeAlpha: Float,
    bottomFadeAlpha: Float,
    fadeLength: Dp,
): Modifier {
    if (topFadeAlpha <= 0f && bottomFadeAlpha <= 0f) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadePx = fadeLength.toPx()
            if (fadePx <= 0f) return@drawWithContent
            if (topFadeAlpha > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 1f - topFadeAlpha), Color.Black),
                        startY = 0f,
                        endY = fadePx.coerceAtMost(size.height),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (bottomFadeAlpha > 0f) {
                val len = fadePx.coerceAtMost(size.height / 2f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black, Color.Black.copy(alpha = 1f - bottomFadeAlpha)),
                        startY = size.height - len,
                        endY = size.height,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}

internal fun LazyListState.toChatViewportSnapshot(isUserScrolling: Boolean): ChatViewportSnapshot =
    ChatViewportSnapshot(
        totalItems = layoutInfo.totalItemsCount,
        lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index,
        isUserScrolling = isUserScrolling,
    )

/**
 * A run (reasoning + tool steps + narration) rendered to match the Penpot
 * "Conversation (detailed)" board: an optional "Thought" row, a compact
 * "Run · N steps" card (one row per tool call), then the agent's narration.
 */
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
    val trimmed = line.trimStart()
    val l = line.lowercase()
    return when {
        // Unified-diff lines: + added (green), - removed (red). Ignore +++/--- headers.
        trimmed.startsWith("+") && !trimmed.startsWith("+++") -> success
        trimmed.startsWith("-") && !trimmed.startsWith("---") -> MaterialTheme.colorScheme.error
        l.contains("build successful") || l.contains("success") || l.contains("passed") -> success
        l.contains("error") || l.contains("failed") || l.contains("exception") || l.contains("fatal") ->
            MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private val ChatColumnMaxWidth = 760.dp

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

@Composable
internal fun DesktopMessageBubble(message: UiMessage, streamingMessageId: String? = null) {
    if (message.role == "user") {
        UserPrompt(message)
        return
    }
    // Assistant message (standalone): reasoning / text / tool cards, full-width.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (message.isReasoning && message.content.isNotBlank()) {
            ReasoningRow(message.content)
        } else if (message.content.isNotBlank()) {
            AgentText(message.content, message.isError, isStreaming = message.id == streamingMessageId)
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
@Composable
internal fun AgentText(text: String, isError: Boolean, isStreaming: Boolean = false) {
    // While the agent's reply is still landing, reveal the latest assistant
    // message progressively via the shared smoother (the same hook Android
    // uses) instead of snapping in each raw chunk. Settled history renders the
    // full text directly. The smoother continues revealing the buffered tail at
    // its own cadence even after [isStreaming] flips back to false, so the reply
    // still finishes smoothly once the in-flight signal clears.
    val displayText = if (isStreaming) {
        rememberSmoothedStreamingText(rawText = text, isStreaming = true)
    } else {
        text
    }
    com.letta.mobile.ui.markdown.SharedMarkdownText(
        text = displayText,
        textColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
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
    val isError = toolCall.status?.let {
        it.equals("error", ignoreCase = true) || it.equals("failed", ignoreCase = true)
    } == true
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
                    text = toolCall.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                ToolStatusBadge(toolCall.status ?: "tool call")
                Spacer(Modifier.weight(1f))
                CopyIconButton(
                    text = listOfNotNull(
                        toolCall.arguments.takeIf { it.isNotBlank() },
                        toolCall.result?.takeIf { it.isNotBlank() },
                    ).joinToString("\n\n").ifBlank { toolCall.name },
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
        }
    }
}

/**
 * Pull the human-meaningful payload out of a tool-call arguments JSON object
 * (e.g. the shell command / code / query) so the card shows that instead of a
 * raw `{"command":"…"}` dump. Falls back to pretty-printed JSON, then the raw
 * string.
 */
internal fun primaryToolArgument(raw: String): String {
    val obj = runCatching { desktopChatJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: return raw
    val preferredKeys = listOf("command", "code", "query", "input", "text", "content", "cmd", "script", "expression")
    for (key in preferredKeys) {
        val value = obj[key]
        if (value is JsonPrimitive && value.isString && value.content.isNotBlank()) {
            return value.content
        }
    }
    return runCatching { prettyDesktopJson.encodeToString(JsonObject.serializer(), obj) }.getOrDefault(raw)
}

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
