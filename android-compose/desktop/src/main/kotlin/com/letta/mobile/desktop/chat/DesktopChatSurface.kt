package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.StepDotIcon
import com.letta.mobile.data.chat.projection.runStepDotIcon
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalResponse
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import java.util.Base64
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun DesktopChatSurface(
    state: DesktopChatSurfaceState,
    onConversationSelected: (String) -> Unit,
    onComposerTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
    onRemoveImageAttachment: (Int) -> Unit,
    onRetryConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        ConversationPane(
            state = state,
            onConversationSelected = onConversationSelected,
            onRetryConnection = onRetryConnection,
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
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
    onRetryConnection: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(328.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
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
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.conversations.isEmpty()) {
                item {
                    ConversationPaneStateCard(
                        state = state,
                        onRetryConnection = onRetryConnection,
                    )
                }
            }
            items(
                items = state.conversations,
                key = { it.id },
            ) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    selected = conversation.id == state.selectedConversationId,
                    onClick = { onConversationSelected(conversation.id) },
                )
            }
        }
    }
}

@Composable
private fun ConversationPaneStateCard(
    state: DesktopChatSurfaceState,
    onRetryConnection: () -> Unit,
) {
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
                    imageVector = state.connectionState.statusIcon(),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = state.connectionState.statusColor(),
                )
                Text(
                    text = state.connectionState.panelTitle(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = state.errorMessage ?: state.connectionState.panelBody(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.connectionState.canRetry()) {
                Button(
                    onClick = onRetryConnection,
                    enabled = !state.isLoading,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: DesktopConversationSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conversation.unreadCount > 0) {
                    CountPill(conversation.unreadCount)
                }
            }
            Text(
                text = conversation.agentName,
                style = MaterialTheme.typography.labelMedium,
                color = content.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = conversation.lastMessagePreview,
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.78f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = conversation.updatedAtLabel,
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.62f),
            )
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
private fun ChatDetailPane(
    state: DesktopChatSurfaceState,
    onComposerTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
    onRemoveImageAttachment: (Int) -> Unit,
    onRetryConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        ChatHeader(state)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        if (state.shouldShowStatePanel) {
            ChatStatePanel(
                state = state,
                onRetryConnection = onRetryConnection,
                modifier = Modifier.weight(1f),
            )
        } else {
            MessageList(
                renderItems = state.renderItems,
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        ComposerBar(
            text = state.composerText,
            pendingImageAttachments = state.pendingImageAttachments,
            enabled = state.canSend,
            onTextChanged = onComposerTextChanged,
            onSend = onSend,
            onAttachImage = onAttachImage,
            onRemoveImageAttachment = onRemoveImageAttachment,
        )
    }
}

@Composable
private fun ChatStatePanel(
    state: DesktopChatSurfaceState,
    onRetryConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = state.connectionState.statusIcon(),
                        contentDescription = null,
                    )
                }
            }
            Text(
                text = state.connectionState.panelTitle(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.errorMessage ?: state.connectionState.panelBody(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.connectionState.canRetry()) {
                Button(
                    onClick = onRetryConnection,
                    enabled = !state.isLoading,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Retry connection")
                }
            }
        }
    }
}

@Composable
private fun ChatHeader(state: DesktopChatSurfaceState) {
    val conversation = state.selectedConversation

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.SmartToy,
                    contentDescription = null,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = conversation?.title ?: "No conversation selected",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = conversation?.agentName ?: "Select a conversation",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusChip("Graph ${state.sessionGraphId}")
    }
}

@Composable
private fun MessageList(
    renderItems: List<ChatRenderItem>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(
            items = renderItems,
            key = { it.key },
        ) { item ->
            when (item) {
                is ChatRenderItem.Single -> DesktopMessageBubble(item.message)
                is ChatRenderItem.RunBlock -> DesktopRunBlock(item)
            }
        }
    }
}

@Composable
private fun DesktopRunBlock(item: ChatRenderItem.RunBlock) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 780.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(MaterialTheme.colorScheme.tertiary)
                Text(
                    text = "Run ${item.runId}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.messages.forEach { (message, _) ->
                RunStepRow(message)
            }
        }
    }
}

@Composable
private fun RunStepRow(message: UiMessage) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = message.runStepDotIcon().containerColor(),
            contentColor = message.runStepDotIcon().contentColor(),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = message.runStepDotIcon().icon(),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        DesktopMessageContent(
            message = message,
            compact = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DesktopMessageBubble(message: UiMessage) {
    val isUser = message.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 720.dp),
            shape = MaterialTheme.shapes.medium,
            color = messageBubbleColor(message),
            contentColor = messageBubbleContentColor(message),
            border = BorderStroke(1.dp, messageBubbleBorderColor(message)),
        ) {
            DesktopMessageContent(
                message = message,
                compact = false,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}

@Composable
private fun DesktopMessageContent(
    message: UiMessage,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (message.role == "user") Icons.Outlined.Person else Icons.Outlined.SmartToy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message.senderLabel(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (message.isPending) {
                StatusChip("Queued")
            }
            if (message.latencyMs != null) {
                StatusChip("${message.latencyMs} ms")
            }
        }

        if (message.content.isNotBlank()) {
            SelectionContainer {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        DesktopImageAttachmentsGrid(
            attachments = message.attachments,
            modifier = Modifier.fillMaxWidth(),
        )

        message.toolCalls.orEmpty().forEach { toolCall ->
            ToolCallCard(toolCall)
        }
        message.generatedUi?.let { generatedUi ->
            GeneratedUiCard(generatedUi)
        }
        message.approvalRequest?.let { approvalRequest ->
            ApprovalRequestCard(approvalRequest)
        }
        message.approvalResponse?.let { approvalResponse ->
            ApprovalResponseCard(approvalResponse)
        }
    }
}

@Composable
private fun ToolCallCard(toolCall: UiToolCall) {
    ArtifactCard(
        icon = Icons.Outlined.Build,
        title = toolCall.name,
        status = toolCall.status ?: "tool call",
    ) {
        Text(
            text = toolCall.arguments,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        toolCall.result?.takeIf { it.isNotBlank() }?.let { result ->
            Text(
                text = result,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        toolCall.executionTimeMs?.let { duration ->
            StatusChip("$duration ms")
        }
        DesktopImageAttachmentsGrid(
            attachments = toolCall.generatedImageAttachments,
            modifier = Modifier.fillMaxWidth(),
        )
    }
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
    icon: ImageVector,
    title: String,
    status: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusChip(status)
            }
            content()
        }
    }
}

@Composable
private fun ComposerBar(
    text: String,
    pendingImageAttachments: List<MessageContentPart.Image>,
    enabled: Boolean,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
    onRemoveImageAttachment: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (pendingImageAttachments.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pendingImageAttachments.forEachIndexed { index, image ->
                    PendingAttachmentThumbnail(
                        image = image,
                        onRemove = { onRemoveImageAttachment(index) },
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                IconButton(
                    onClick = onAttachImage,
                    enabled = enabled,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddPhotoAlternate,
                        contentDescription = "Attach image",
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp, max = 132.dp),
                placeholder = { Text("Message") },
                minLines = 1,
                maxLines = 5,
            )
            Button(
                onClick = onSend,
                enabled = enabled && (text.isNotBlank() || pendingImageAttachments.isNotEmpty()),
                modifier = Modifier.height(56.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Send message",
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Send")
            }
        }
    }
}

@Composable
private fun PendingAttachmentThumbnail(
    image: MessageContentPart.Image,
    onRemove: () -> Unit,
) {
    Box(modifier = Modifier.size(68.dp)) {
        DesktopAttachmentImage(
            attachment = UiImageAttachment(base64 = image.base64, mediaType = image.mediaType),
            modifier = Modifier.size(64.dp),
        )
        Surface(
            modifier = Modifier
                .size(22.dp)
                .align(Alignment.TopEnd),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(22.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Remove attachment",
                    modifier = Modifier.size(14.dp),
                )
            }
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

@Composable
private fun DesktopChatConnectionState.statusColor(): Color = when (this) {
    DesktopChatConnectionState.Live,
    DesktopChatConnectionState.Sending -> MaterialTheme.colorScheme.primary
    DesktopChatConnectionState.Demo,
    DesktopChatConnectionState.NoConversations -> MaterialTheme.colorScheme.tertiary
    DesktopChatConnectionState.Loading -> MaterialTheme.colorScheme.secondary
    DesktopChatConnectionState.ConfigNeeded,
    DesktopChatConnectionState.Offline,
    DesktopChatConnectionState.StreamDisconnected,
    DesktopChatConnectionState.SendFailed -> MaterialTheme.colorScheme.error
}

private fun DesktopChatConnectionState.statusIcon(): ImageVector = when (this) {
    DesktopChatConnectionState.Live,
    DesktopChatConnectionState.Sending -> Icons.Outlined.CheckCircle
    DesktopChatConnectionState.Demo,
    DesktopChatConnectionState.NoConversations -> Icons.Outlined.SmartToy
    DesktopChatConnectionState.Loading -> Icons.Outlined.HourglassEmpty
    DesktopChatConnectionState.ConfigNeeded,
    DesktopChatConnectionState.Offline,
    DesktopChatConnectionState.StreamDisconnected,
    DesktopChatConnectionState.SendFailed -> Icons.Outlined.ErrorOutline
}

private fun DesktopChatConnectionState.panelTitle(): String = when (this) {
    DesktopChatConnectionState.Demo -> "Demo preview"
    DesktopChatConnectionState.Loading -> "Connecting"
    DesktopChatConnectionState.ConfigNeeded -> "Backend configuration required"
    DesktopChatConnectionState.Offline -> "Backend offline"
    DesktopChatConnectionState.NoConversations -> "No conversations"
    DesktopChatConnectionState.Live -> "Live"
    DesktopChatConnectionState.Sending -> "Sending"
    DesktopChatConnectionState.StreamDisconnected -> "Stream disconnected"
    DesktopChatConnectionState.SendFailed -> "Send failed"
}

private fun DesktopChatConnectionState.panelBody(): String = when (this) {
    DesktopChatConnectionState.Demo -> "Sample data is shown only in the explicit demo preview."
    DesktopChatConnectionState.Loading -> "Loading conversations from the configured Letta backend."
    DesktopChatConnectionState.ConfigNeeded -> "Set a server URL and token in Settings before connecting."
    DesktopChatConnectionState.Offline -> "The configured backend could not be reached."
    DesktopChatConnectionState.NoConversations -> "This backend returned no conversations for the active account."
    DesktopChatConnectionState.Live -> "Connected to the configured backend."
    DesktopChatConnectionState.Sending -> "Sending your message to the active conversation."
    DesktopChatConnectionState.StreamDisconnected -> "The conversation stream disconnected. Existing messages remain visible."
    DesktopChatConnectionState.SendFailed -> "The last send failed. You can edit and try again."
}

private fun DesktopChatConnectionState.canRetry(): Boolean = when (this) {
    DesktopChatConnectionState.ConfigNeeded,
    DesktopChatConnectionState.Offline,
    DesktopChatConnectionState.StreamDisconnected -> true
    DesktopChatConnectionState.Demo,
    DesktopChatConnectionState.Loading,
    DesktopChatConnectionState.NoConversations,
    DesktopChatConnectionState.Live,
    DesktopChatConnectionState.Sending,
    DesktopChatConnectionState.SendFailed -> false
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
private fun messageBubbleColor(message: UiMessage): Color = when {
    message.isError -> MaterialTheme.colorScheme.errorContainer
    message.role == "user" -> MaterialTheme.colorScheme.primaryContainer
    message.isReasoning -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f)
    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
}

@Composable
private fun messageBubbleContentColor(message: UiMessage): Color = when {
    message.isError -> MaterialTheme.colorScheme.onErrorContainer
    message.role == "user" -> MaterialTheme.colorScheme.onPrimaryContainer
    message.isReasoning -> MaterialTheme.colorScheme.onTertiaryContainer
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun messageBubbleBorderColor(message: UiMessage): Color = when {
    message.isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
    message.role == "user" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    message.isReasoning -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.26f)
    else -> MaterialTheme.colorScheme.outlineVariant
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
