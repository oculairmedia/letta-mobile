package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.PointerMatcher
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.onClick
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.StepDotIcon
import com.letta.mobile.data.chat.projection.runStepDotIcon
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
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopControlText
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.desktop.DesktopIconButton
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
import androidx.compose.foundation.interaction.collectIsDraggedAsState

@Composable
fun DesktopChatSurface(
    state: DesktopChatSurfaceState,
    onConversationSelected: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onComposerTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
    onRemoveImageAttachment: (Int) -> Unit,
    onRetryConnection: () -> Unit,
    onSettingsSelected: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val showConversationPane = state.conversations.isNotEmpty()
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (showConversationPane) {
            ConversationPane(
                state = state,
                onConversationSelected = onConversationSelected,
                onDeleteConversation = onDeleteConversation,
                onRetryConnection = onRetryConnection,
                onSettingsSelected = onSettingsSelected,
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
        ChatDetailPane(
            state = state,
            onComposerTextChanged = onComposerTextChanged,
            onSend = onSend,
            onAttachImage = onAttachImage,
            onRemoveImageAttachment = onRemoveImageAttachment,
            onRetryConnection = onRetryConnection,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ConversationPane(
    state: DesktopChatSurfaceState,
    onConversationSelected: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onRetryConnection: () -> Unit,
    onSettingsSelected: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .width(292.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Conversations",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.backendLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            state.statusMessage?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.isRemoteBacked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.conversations.isEmpty()) {
                item {
                    ConversationPaneStateCard(
                        state = state,
                        onRetryConnection = onRetryConnection,
                    )
                }
            }
            state.conversationGroups.forEach { group ->
                item(key = "agent-${group.key}") {
                    ConversationGroupHeader(group)
                }
                items(
                    items = group.conversations,
                    key = { it.id },
                ) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.id == state.selectedConversationId,
                        onClick = { onConversationSelected(conversation.id) },
                        onDelete = { onDeleteConversation(conversation.id) },
                    )
                }
            }
        }

        if (onSettingsSelected != null) {
            Spacer(Modifier.height(6.dp))
            ConversationSidebarSettingsRow(onClick = onSettingsSelected)
        }
    }
}

@Composable
private fun ConversationSidebarSettingsRow(
    onClick: () -> Unit,
) {
    DesktopTooltip(text = "Settings") {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            shape = MaterialTheme.shapes.small,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun ConversationGroupHeader(group: DesktopConversationGroup) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.agentName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (group.unreadCount > 0) {
            CountPill(group.unreadCount)
        }
    }
}

@Composable
internal fun ConversationPaneStateCard(
    state: DesktopChatSurfaceState,
    onRetryConnection: () -> Unit,
) {
    val screenStatus = state.chatScreenStatus
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = screenStatus.statusIcon(),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = screenStatus.statusColor(),
                )
                Text(
                    text = screenStatus.panelTitle(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = state.errorMessage ?: screenStatus.panelBody(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (screenStatus.isConnectionRetryable) {
                DesktopDefaultButton(
                    onClick = onRetryConnection,
                    enabled = !state.isLoading,
                ) {
                    DesktopButtonContent(
                        text = "Retry",
                        icon = Icons.Outlined.Refresh,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationRow(
    conversation: DesktopConversationSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    DesktopTooltip(text = "${conversation.title} - ${conversation.updatedAtLabel}") {
        Box {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { showMenu = true },
                    )
                    .onClick(
                        matcher = PointerMatcher.mouse(PointerButton.Secondary),
                        onClick = { showMenu = true },
                    ),
                shape = MaterialTheme.shapes.small,
                color = container,
                contentColor = content,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (conversation.unreadCount > 0) {
                        CountPill(conversation.unreadCount)
                    }
                    Text(
                        text = conversation.updatedAtLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (showMenu) {
                JewelPopupMenu(
                    onDismissRequest = {
                        showMenu = false
                        true
                    },
                    horizontalAlignment = Alignment.End,
                ) {
                    selectableItem(
                        selected = false,
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                    ) {
                        DesktopControlText("Delete conversation")
                    }
                }
            }
        }
    }
}

@Composable
private fun CountPill(count: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
internal fun ChatDetailPane(
    state: DesktopChatSurfaceState,
    isThinking: Boolean = false,
    onComposerTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
    onRemoveImageAttachment: (Int) -> Unit,
    onRetryConnection: () -> Unit,
    modelOptions: List<Pair<String, String>> = emptyList(),
    onModelSelected: (String) -> Unit = {},
    commands: List<ComposerCommand> = emptyList(),
    modifier: Modifier = Modifier,
) {
    // Drive the ambient glow off the thinking state: a teal breath while the
    // agent works, a brief "completed" settle afterward, error tint on failure.
    var ambientStatus by remember { mutableStateOf(DesktopAmbientStatus.Idle) }
    var hadActiveRun by remember { mutableStateOf(false) }
    LaunchedEffect(isThinking, state.errorMessage) {
        when {
            state.errorMessage != null -> ambientStatus = DesktopAmbientStatus.Failed
            isThinking -> {
                hadActiveRun = true
                ambientStatus = DesktopAmbientStatus.Running
            }
            hadActiveRun -> {
                ambientStatus = DesktopAmbientStatus.Completed
                delay(1400)
                hadActiveRun = false
                ambientStatus = DesktopAmbientStatus.Idle
            }
            else -> ambientStatus = DesktopAmbientStatus.Idle
        }
    }
    DesktopAmbientChatBackground(
        status = ambientStatus,
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.shouldShowStatePanel) {
                ChatStatePanel(
                    state = state,
                    onRetryConnection = onRetryConnection,
                    modifier = Modifier.weight(1f),
                )
            } else if (state.renderItems.isEmpty() && !isThinking) {
                NewConversationWelcome(
                    agentName = state.selectedConversation?.agentName,
                    modifier = Modifier.weight(1f),
                )
            } else {
                MessageList(
                    conversationId = state.selectedConversationId,
                    renderItems = state.renderItems,
                    isSending = isThinking,
                    modifier = Modifier.weight(1f),
                )
            }
            ComposerBar(
                text = state.composerText,
                pendingImageAttachments = state.pendingImageAttachments,
                enabled = state.canSend,
                modelLabel = state.composerModelLabel,
                modelOptions = modelOptions,
                onModelSelected = onModelSelected,
                commands = commands,
                onTextChanged = onComposerTextChanged,
                onSend = onSend,
                onAttachImage = onAttachImage,
                onRemoveImageAttachment = onRemoveImageAttachment,
            )
        }
    }
}

/**
 * Empty-state shown for a fresh conversation (no messages yet): the agent's
 * gradient sphere, a greeting, and a hint — matching the Penpot
 * "Desktop · New conversation" board.
 */
@Composable
private fun NewConversationWelcome(
    agentName: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 40.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AgentSphere(size = 72.dp)
            Text(
                text = agentName?.takeIf { it.isNotBlank() }?.let { "Chat with $it" } ?: "New conversation",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Ask a question, paste an error, or point me at a repo — I can read code, run tools, and help you ship.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 460.dp),
            )
            Text(
                text = "@ to add files   ·   / for commands   ·   ⏎ to send",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ChatStatePanel(
    state: DesktopChatSurfaceState,
    onRetryConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenStatus = state.chatScreenStatus
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 40.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 680.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "LETTA DESKTOP",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontFamily = FontFamily.Serif,
                    color = MaterialTheme.colorScheme.onSurface,
                    letterSpacing = 0.sp,
                ),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.errorMessage ?: screenStatus.heroBody(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 520.dp),
            )
            if (screenStatus.isConnectionRetryable) {
                DesktopDefaultButton(
                    onClick = onRetryConnection,
                    enabled = !state.isLoading,
                ) {
                    DesktopButtonContent(
                        text = "Retry connection",
                        icon = Icons.Outlined.Refresh,
                    )
                }
            }
        }
    }
}

private fun ChatScreenStatus.heroBody(): String = when (this) {
    is ChatScreenStatus.ConfigNeeded -> "Configure a backend, then ask questions, inspect tools, and continue work across sessions."
    is ChatScreenStatus.BackendOffline -> "The configured backend could not be reached. Check the gateway, then retry the connection."
    is ChatScreenStatus.NoConversations -> "Ask a question, paste an error, or point me at a repo. I can read code, run tools, and help you ship."
    is ChatScreenStatus.Loading -> "Loading conversations from the configured Letta backend."
    is ChatScreenStatus.SendFailed -> "The last send failed. You can edit the message and try again."
    is ChatScreenStatus.Ready -> if (isSending) {
        "Sending your message to the active conversation."
    } else {
        "Connected to the configured backend."
    }
}

@Composable
private fun MessageList(
    conversationId: String?,
    renderItems: List<ChatRenderItem>,
    isSending: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val isUserScrolling by listState.interactionSource.collectIsDraggedAsState()
    val scope = rememberCoroutineScope()
    var followLatest by remember(conversationId) { mutableStateOf(true) }
    val latestItemKey = renderItems.lastOrNull()?.key

    LaunchedEffect(conversationId) {
        followLatest = true
        if (renderItems.isNotEmpty()) {
            listState.scrollToItem(ChatViewportFollowPolicy.latestIndex(renderItems.size))
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

    LaunchedEffect(latestItemKey, renderItems.size) {
        if (ChatViewportFollowPolicy.shouldAutoFollow(followLatest, renderItems.size)) {
            listState.scrollToItem(ChatViewportFollowPolicy.latestIndex(renderItems.size))
        }
    }

    // When a send starts, snap to the bottom so the thinking row is visible.
    LaunchedEffect(isSending) {
        if (isSending) {
            followLatest = true
            listState.animateScrollToItem(renderItems.size + 1)
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 24.dp),
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
                Box(modifier = Modifier.widthIn(max = ChatColumnMaxWidth).fillMaxWidth()) {
                    when (item) {
                        is ChatRenderItem.Single -> DesktopMessageBubble(item.message)
                        is ChatRenderItem.RunBlock -> DesktopRunBlock(item)
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

        if (ChatViewportFollowPolicy.shouldShowScrollToLatest(listState.toChatViewportSnapshot(isUserScrolling))) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .size(44.dp)
                    .clickable {
                        followLatest = true
                        scope.launch {
                            listState.animateScrollToItem(ChatViewportFollowPolicy.latestIndex(renderItems.size))
                        }
                    },
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

private fun LazyListState.toChatViewportSnapshot(isUserScrolling: Boolean): ChatViewportSnapshot =
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
private fun DesktopRunBlock(item: ChatRenderItem.RunBlock) {
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
        narration.forEach { AgentText(it.content, it.isError) }
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
private enum class StepState { Done, Running, Error }

private fun UiToolCall.stepState(): StepState = when (status?.lowercase()) {
    "completed", "success", "ok" -> StepState.Done
    "failed", "error" -> StepState.Error
    else -> StepState.Done
}

/** "Ran ./gradlew …" / "Read Foo.kt" — a friendly verb + short target. */
private fun UiToolCall.stepLabel(): String {
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
private fun UiToolCall.stepSummary(): String {
    val resultLine = result?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotBlank() }
    return when {
        !resultLine.isNullOrBlank() && resultLine.length <= 28 -> resultLine
        executionTimeMs != null -> "${executionTimeMs} ms"
        else -> ""
    }
}

@Composable
private fun ReasoningRow(text: String) {
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
private fun RunStepsCard(toolCalls: List<UiToolCall>) {
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
private fun ToolStepRow(toolCall: UiToolCall) {
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
private fun StepStatusCircle(state: StepState) {
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
private fun ToolOutputBlock(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        SelectionContainer {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                text.trim().lineSequence().take(40).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = outputLineColor(line),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun outputLineColor(line: String): Color {
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
private fun DesktopMessageBubble(message: UiMessage) {
    if (message.role == "user") {
        UserPrompt(message)
        return
    }
    // Assistant message (standalone): reasoning / text / tool cards, full-width.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (message.isReasoning && message.content.isNotBlank()) {
            ReasoningRow(message.content)
        } else if (message.content.isNotBlank()) {
            AgentText(message.content, message.isError)
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

/** User prompt — teal bubble, right-aligned, with faint copy/edit affordances. */
@Composable
private fun UserPrompt(message: UiMessage) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "Copy",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "Edit",
                modifier = Modifier.size(14.dp),
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
private fun ThinkingMessageRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AgentSphere(size = 28.dp)
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

/** Plain agent narration text, full width (no bubble), per the detailed board. */
@Composable
private fun AgentText(text: String, isError: Boolean) {
    SelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Full, collapsible single-tool card matching the Penpot "Tool call (expanded)"
 * board: terminal glyph + name + green outlined success badge + copy/chevron,
 * the command, an inset output block, and an exit-code footer.
 */
@Composable
private fun ToolCard(toolCall: UiToolCall) {
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
                    text = toolCall.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                ToolStatusBadge(toolCall.status ?: "tool call")
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = "Copy",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    toolCall.result?.takeIf { it.isNotBlank() }?.let { ToolOutputBlock(it) }
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
private fun primaryToolArgument(raw: String): String {
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
private fun GeneratedUiCard(generatedUi: UiGeneratedComponent) {
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
private fun ApprovalRequestCard(approvalRequest: UiApprovalRequest) {
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
private fun ApprovalResponseCard(approvalResponse: UiApprovalResponse) {
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
private fun ArtifactCard(
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
private fun ToolStatusBadge(status: String) {
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
private fun CodeBlock(text: String) {
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
private fun ComposerBar(
    text: String,
    pendingImageAttachments: List<MessageContentPart.Image>,
    enabled: Boolean,
    modelLabel: String,
    modelOptions: List<Pair<String, String>>,
    onModelSelected: (String) -> Unit,
    commands: List<ComposerCommand>,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
    onRemoveImageAttachment: (Int) -> Unit,
) {
    val canSend = enabled && (text.isNotBlank() || pendingImageAttachments.isNotEmpty())
    val slashQuery = text.trimStart().takeIf { it.startsWith("/") }?.drop(1)
    val matchedCommands = if (slashQuery != null && commands.isNotEmpty()) {
        commands.filter { it.label.contains(slashQuery, ignoreCase = true) }
    } else {
        emptyList()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, top = 4.dp, end = 28.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (matchedCommands.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    matchedCommands.take(8).forEach { command ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onTextChanged("")
                                    command.run()
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "/${command.label}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = command.description,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (pendingImageAttachments.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        pendingImageAttachments.forEachIndexed { index, image ->
                            ComposerAttachmentChip(
                                image = image,
                                onRemove = { onRemoveImageAttachment(index) },
                            )
                        }
                    }
                }
                DesktopTextArea(
                    value = text,
                    onValueChange = onTextChanged,
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 28.dp, max = 120.dp)
                        .onPreviewKeyEvent { event ->
                            // Enter sends; Shift+Enter inserts a newline. Only an
                            // app-action command intercepts Enter — server slash
                            // commands (fillsComposer) let the message send.
                            if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                                !event.isShiftPressed
                            ) {
                                val actionCommand = matchedCommands.firstOrNull { !it.fillsComposer }
                                if (actionCommand != null) {
                                    onTextChanged("")
                                    actionCommand.run()
                                    true
                                } else if (canSend) {
                                    onSend()
                                    true
                                } else {
                                    false
                                }
                            } else {
                                false
                            }
                        },
                    maxLines = 5,
                    placeholder = "Message Meridian…",
                    undecorated = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DesktopIconButton(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "Attach",
                        onClick = onAttachImage,
                        enabled = enabled,
                        iconModifier = Modifier.size(18.dp),
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val modelDisplay = modelOptions.firstOrNull { it.second == modelLabel }?.first
                        ?: modelLabel.ifBlank { "Model" }
                    ComposerDropdownChip(
                        label = modelDisplay,
                        options = modelOptions.map { it.first },
                        emptyHint = "No models available",
                        onSelect = { selected ->
                            modelOptions.firstOrNull { it.first == selected }?.let { onModelSelected(it.second) }
                        },
                    )
                    var safety by remember { mutableStateOf("Unrestricted") }
                    ComposerDropdownChip(
                        label = safety,
                        options = listOf("Unrestricted", "Standard", "Strict"),
                        onSelect = { safety = it },
                        leadingIcon = Icons.Outlined.Security,
                        contentColor = MaterialTheme.customColors.runningColor
                            .takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.tertiary,
                    )
                    var effort by remember { mutableStateOf("Medium") }
                    ComposerDropdownChip(
                        label = effort,
                        options = listOf("Low", "Medium", "High"),
                        onSelect = { effort = it },
                    )
                    Spacer(Modifier.weight(1f))
                    Surface(
                        modifier = Modifier
                            .size(38.dp)
                            .clickable(enabled = canSend, onClick = onSend),
                        shape = CircleShape,
                        color = if (canSend) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        contentColor = if (canSend) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.ArrowUpward,
                                contentDescription = "Send message",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .padding(start = 4.dp),
        ) {
            Text(
                text = "@ to add files   ·   / for commands   ·   ⏎ to send",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

/**
 * A functional pill selector in the composer control row (model / safety /
 * effort): click opens a popup of [options]; selecting one fires [onSelect].
 */
@Composable
private fun ComposerDropdownChip(
    label: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    leadingIcon: ImageVector? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    emptyHint: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = contentColor,
            modifier = Modifier.clickable { open = !open },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = contentColor,
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (open) {
            JewelPopupMenu(
                onDismissRequest = {
                    open = false
                    true
                },
                horizontalAlignment = Alignment.Start,
            ) {
                if (options.isEmpty() && emptyHint != null) {
                    selectableItem(selected = false, onClick = { open = false }) {
                        DesktopControlText(emptyHint)
                    }
                }
                options.forEach { option ->
                    selectableItem(
                        selected = option == label,
                        onClick = {
                            open = false
                            onSelect(option)
                        },
                    ) {
                        DesktopControlText(option)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerAttachmentChip(
    image: MessageContentPart.Image,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DesktopAttachmentImage(
                attachment = UiImageAttachment(base64 = image.base64, mediaType = image.mediaType),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "image",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Remove attachment",
                modifier = Modifier
                    .size(14.dp)
                    .clickable(onClick = onRemove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DesktopImageAttachmentsGrid(
    attachments: List<UiImageAttachment>,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val cellHeight = if (attachments.size == 1) 220.dp else 128.dp
        attachments.take(4).forEach { attachment ->
            DesktopAttachmentImage(
                attachment = attachment,
                modifier = Modifier
                    .weight(1f)
                    .height(cellHeight),
            )
        }
    }
}

@Composable
private fun DesktopAttachmentImage(
    attachment: UiImageAttachment,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(attachment.base64) {
        runCatching {
            val bytes = Base64.getDecoder().decode(attachment.base64)
            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun StatusChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(9.dp)
            .background(color, CircleShape),
    )
}

// ---------------------------------------------------------------------------
// Desktop-side UI mappings for ChatScreenStatus
// Icon, colour, copy, and retry affordance are all platform concerns kept
// here — only the MEANING of which state we are in moves to shared code.
// ---------------------------------------------------------------------------

@Composable
private fun ChatScreenStatus.statusColor(): Color = when (this) {
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

private fun ChatScreenStatus.statusIcon(): ImageVector = when (this) {
    is ChatScreenStatus.Ready -> Icons.Outlined.CheckCircle
    is ChatScreenStatus.NoConversations -> Icons.Outlined.SmartToy
    is ChatScreenStatus.Loading -> Icons.Outlined.HourglassEmpty
    is ChatScreenStatus.ConfigNeeded,
    is ChatScreenStatus.BackendOffline,
    is ChatScreenStatus.SendFailed -> Icons.Outlined.ErrorOutline
}

private fun ChatScreenStatus.panelTitle(): String = when (this) {
    is ChatScreenStatus.Loading -> "Connecting"
    is ChatScreenStatus.ConfigNeeded -> "Backend configuration required"
    is ChatScreenStatus.BackendOffline -> "Backend offline"
    is ChatScreenStatus.NoConversations -> "No conversations"
    is ChatScreenStatus.SendFailed -> "Send failed"
    is ChatScreenStatus.Ready -> if (isSending) "Sending" else "Live"
}

private fun ChatScreenStatus.panelBody(): String = when (this) {
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

private fun UiMessage.senderLabel(): String = when {
    role == "user" -> "You"
    isReasoning -> "Reasoning"
    isError -> "Error"
    toolCalls?.isNotEmpty() == true -> "Tool"
    approvalRequest != null -> "Approval"
    generatedUi != null -> "Generated UI"
    else -> "Assistant"
}

@Composable
private fun StepDotIcon.icon(): ImageVector = when (this) {
    StepDotIcon.Reasoning -> Icons.Outlined.Psychology
    StepDotIcon.ToolCall -> Icons.Outlined.Build
    StepDotIcon.Approval -> Icons.Outlined.CheckCircle
    StepDotIcon.AssistantText -> Icons.Outlined.SmartToy
    StepDotIcon.Unknown -> Icons.Outlined.HourglassEmpty
}

@Composable
private fun StepDotIcon.containerColor(): Color = when (this) {
    StepDotIcon.Reasoning -> MaterialTheme.colorScheme.tertiaryContainer
    StepDotIcon.ToolCall -> MaterialTheme.colorScheme.secondaryContainer
    StepDotIcon.Approval -> MaterialTheme.colorScheme.primaryContainer
    StepDotIcon.AssistantText -> MaterialTheme.colorScheme.surface
    StepDotIcon.Unknown -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun StepDotIcon.contentColor(): Color = when (this) {
    StepDotIcon.Reasoning -> MaterialTheme.colorScheme.onTertiaryContainer
    StepDotIcon.ToolCall -> MaterialTheme.colorScheme.onSecondaryContainer
    StepDotIcon.Approval -> MaterialTheme.colorScheme.onPrimaryContainer
    StepDotIcon.AssistantText -> MaterialTheme.colorScheme.onSurface
    StepDotIcon.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
}
