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

/** Surface + composer catalog inputs for [ChatDetailPane]. */
internal data class ChatDetailPaneState(
    val surface: DesktopChatSurfaceState,
    val isThinking: Boolean,
    val isStreamingReply: Boolean = false,
    val modelOptions: List<Pair<String, String>>,
    val commands: List<ComposerCommand>,
    val mentionables: List<Mentionable> = emptyList(),
    val composerPlaceholder: String = "Message…",
)

/** Interaction callbacks for [ChatDetailPane]. */
internal data class ChatDetailPaneActions(
    val onComposerTextChanged: (String) -> Unit,
    val onSend: () -> Unit,
    val onAttachImage: () -> Unit,
    val onRemoveImageAttachment: (Int) -> Unit,
    val onRetryConnection: () -> Unit,
    val onModelSelected: (String) -> Unit,
    val onOpenModelPicker: (() -> Unit)? = null,
    val onOnboardingTask: ((OnboardingTaskKind) -> Unit)? = null,
)

@Composable
internal fun ChatDetailPane(
    state: ChatDetailPaneState,
    actions: ChatDetailPaneActions,
    modifier: Modifier = Modifier,
) {
    val surface = state.surface
    // Drive the ambient glow off the thinking state: a teal breath while the
    // agent works, a brief "completed" settle afterward, error tint on failure.
    var ambientStatus by remember { mutableStateOf(DesktopAmbientStatus.Idle) }
    var hadActiveRun by remember { mutableStateOf(false) }
    LaunchedEffect(state.isThinking, surface.errorMessage) {
        when {
            surface.errorMessage != null -> ambientStatus = DesktopAmbientStatus.Failed
            state.isThinking -> {
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
            if (surface.shouldShowStatePanel) {
                ChatStatePanel(
                    state = surface,
                    onRetryConnection = actions.onRetryConnection,
                    modifier = Modifier.weight(1f),
                )
            } else if (surface.renderItems.isEmpty() && !state.isThinking) {
                NewConversationWelcome(
                    agentName = surface.selectedConversation?.agentName,
                    onStarterPrompt = actions.onComposerTextChanged,
                    onOnboardingTask = actions.onOnboardingTask,
                    modifier = Modifier.weight(1f),
                )
            } else {
                MessageList(
                    params = MessageListParams(
                        conversationId = surface.selectedConversationId,
                        renderItems = surface.renderItems,
                        isSending = state.isThinking,
                        isStreamingReply = state.isStreamingReply,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
            ComposerBar(
                state = ComposerBarState(
                    text = surface.composerText,
                    pendingImageAttachments = surface.pendingImageAttachments,
                    enabled = surface.canSend,
                    modelLabel = surface.composerModelLabel,
                    modelOptions = state.modelOptions,
                    commands = state.commands,
                    mentionables = state.mentionables,
                    placeholder = state.composerPlaceholder,
                ),
                actions = ComposerBarActions(
                    onModelSelected = actions.onModelSelected,
                    onOpenModelPicker = actions.onOpenModelPicker,
                    onTextChanged = actions.onComposerTextChanged,
                    onSend = actions.onSend,
                    onAttachImage = actions.onAttachImage,
                    onRemoveImageAttachment = actions.onRemoveImageAttachment,
                ),
            )
        }
    }
}

/**
 * First-run / empty-state for a fresh conversation (Penpot "Desktop · New agent
 * first-run"): the agent's gradient sphere, a greeting, a setup checklist
 * (persona / channel / skills), and "or just start chatting" starter prompts.
 * Copy + tasks come from the shared [AgentOnboarding].
 */
@Composable
private fun NewConversationWelcome(
    agentName: String?,
    onStarterPrompt: (String) -> Unit,
    onOnboardingTask: ((OnboardingTaskKind) -> Unit)?,
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(max = 620.dp),
        ) {
            AgentSphere(size = 72.dp)
            Text(
                text = AgentOnboarding.greeting(agentName),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = AgentOnboarding.SUBTITLE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 480.dp),
            )
            // First-run 2×2 action grid (Phase 5), category-colored. Each card
            // pre-fills the composer so a fresh agent has an obvious first move.
            FirstRunActionGrid(onAction = onStarterPrompt)
            if (onOnboardingTask != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column {
                        AgentOnboarding.tasks(agentName).forEachIndexed { index, task ->
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                )
                            }
                            OnboardingTaskRow(task = task, onClick = { onOnboardingTask(task.kind) })
                        }
                    }
                }
            }
            Text(
                text = AgentOnboarding.STARTER_HEADER,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentOnboarding.starterPrompts.forEach { prompt ->
                    Surface(
                        onClick = { onStarterPrompt(prompt) },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * First-run 2×2 action grid (Phase 5 / Penpot "Desktop · New agent first-run"):
 * four category-colored cards — Start a conversation (primary), Seed a memory
 * (human), Connect a tool (project), Schedule a task (onboarding) — each
 * pre-filling the composer with a sensible opener.
 */
@Composable
private fun FirstRunActionGrid(onAction: (String) -> Unit) {
    val cc = MaterialTheme.customColors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FirstRunCard("Start a conversation", "Just say hello", MaterialTheme.colorScheme.primary, Modifier.weight(1f)) {
                onAction("Hi! Let's get started.")
            }
            FirstRunCard("Seed a memory", "Tell me about you", cc.categoryHumanColor, Modifier.weight(1f)) {
                onAction("Remember this about me: ")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FirstRunCard("Connect a tool", "See what I can use", cc.categoryProjectColor, Modifier.weight(1f)) {
                onAction("What tools can you use?")
            }
            FirstRunCard("Schedule a task", "Automate a routine", cc.categoryOnboardingColor, Modifier.weight(1f)) {
                onAction("Schedule a daily summary for me")
            }
        }
    }
}

@Composable
private fun FirstRunCard(
    title: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.customColors.onSurfaceMutedColor,
                )
            }
        }
    }
}

@Composable
private fun OnboardingTaskRow(task: OnboardingTask, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (task.kind) {
                OnboardingTaskKind.SetPersona -> Icons.Outlined.Edit
                OnboardingTaskKind.ConnectChannel -> Icons.Outlined.Hub
                OnboardingTaskKind.AddSkills -> Icons.Outlined.Build
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = task.subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "Set up",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
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
