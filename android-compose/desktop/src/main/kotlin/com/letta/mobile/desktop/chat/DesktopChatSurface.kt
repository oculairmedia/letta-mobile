package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letta.mobile.data.chat.runtime.ChatScreenStatus
import com.letta.mobile.data.context.ContextWindowUsageState
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.chat.runtime.isConnectionRetryable
import com.letta.mobile.data.composer.Mentionable
import com.letta.mobile.data.onboarding.AgentOnboarding
import com.letta.mobile.data.onboarding.OnboardingTask
import com.letta.mobile.data.onboarding.OnboardingTaskKind
import com.letta.mobile.desktop.DesktopButtonContent
import com.letta.mobile.desktop.DesktopDefaultButton
import com.letta.mobile.ui.ambient.AmbientMotion
import com.letta.mobile.ui.ambient.AmbientMotionStatus
import com.letta.mobile.ui.theme.customColors
import kotlinx.coroutines.delay

import kotlin.time.Duration.Companion.milliseconds
import com.letta.mobile.ui.chat.AgentSphere
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.theme.LettaDimens
/** Surface + composer catalog inputs for [ChatDetailPane]. */
internal data class ChatDetailPaneState(
    val surface: DesktopChatSurfaceState,
    val isThinking: Boolean,
    val canonicalPresentation: com.letta.mobile.data.timeline.CanonicalTimelinePresentation? = null,
    val canonicalStatus: String? = null,
    val isStreamingReply: Boolean = false,
    val modelOptions: List<Pair<String, String>>,
    val commands: List<ComposerCommand>,
    val mentionables: List<Mentionable> = emptyList(),
    val composerPlaceholder: String = "Message…",
    /** Approval request ids whose answer/dismiss is currently in flight. */
    val submittingApprovalRequestIds: Set<String> = emptySet(),
    val agentNamesById: Map<String, String> = emptyMap(),
    /** Each agent's mascot identity (shape + colour) for the hero and, in P3, every orb. */
    val agentIdentitiesById: Map<String, com.letta.mobile.avatar.core.MascotIdentity> = emptyMap(),
    val contextUsage: ContextWindowUsageState = ContextWindowUsageState(),
    /**
     * letta-mobile folder-settings #2: the SELECTED conversation's working
     * directory (the folder the agent's tools — bash, file edits — actually
     * operate in), when the active gateway supports reading/changing it
     * (the bundled local runtime only — see `DesktopWorkingDirectoryController`).
     * Null while unsupported/unknown; [workingDirectorySupported] tells them apart.
     */
    val workingDirectory: String? = null,
    val workingDirectorySupported: Boolean = false,
    val workingDirectoryLoading: Boolean = false,
    /** letta-mobile-1n5py: the selected conversation's messages waiting behind its turn. */
    val sendQueue: com.letta.mobile.data.chat.send.ConversationSendQueue =
        com.letta.mobile.data.chat.send.ConversationSendQueue(),
)

/** Interaction callbacks for [ChatDetailPane]. */
internal data class ChatDetailPaneActions(
    val onComposerTextChanged: (String) -> Unit,
    val onSend: () -> Unit,
    val onAttachImage: () -> Unit,
    val onOpenCanvas: (() -> Unit)? = null,
    val onRemoveImageAttachment: (Int) -> Unit,
    val onRetryConnection: () -> Unit,
    val onModelSelected: (String) -> Unit,
    val onOpenModelPicker: (() -> Unit)? = null,
    val onOnboardingTask: ((OnboardingTaskKind) -> Unit)? = null,
    /** Answer / dismiss a parked approval: (requestId, toolCallIds, approve, reason). */
    val onSubmitApproval: ((String, List<String>, Boolean, String?) -> Unit)? = null,
    val onOpenAgent: (String) -> Unit = {},
    /** The composer companion was clicked: show the selected agent's pane (the sidebar). */
    val onOpenAgentPane: () -> Unit = {},
    /** The pencil on any mascot: open the selected agent's editor. */
    val onEditAgent: () -> Unit = {},
    val onA2uiAction: (A2uiAction) -> Unit = {},
    /** Change the selected conversation's working directory (folder picker result). */
    val onChangeWorkingDirectory: ((String) -> Unit)? = null,
    /** letta-mobile-1n5py: queued-send controls (cancel one, push one through, resume). */
    val queue: QueuedSendActions = QueuedSendActions(),
)

/** letta-mobile-1n5py: what the queued-sends panel above the composer can do. */
internal data class QueuedSendActions(
    val onCancel: (otid: String) -> Unit = {},
    val onSendNow: (otid: String) -> Unit = {},
    val onResume: () -> Unit = {},
)

@Composable
internal fun ChatDetailPane(
    state: ChatDetailPaneState,
    actions: ChatDetailPaneActions,
    modifier: Modifier = Modifier,
) {
    val surface = state.surface
    val approvalHandler = actions.onSubmitApproval?.let { onDecision ->
        DesktopApprovalDecisionHandler(
            onDecision = onDecision,
            submittingRequestIds = state.submittingApprovalRequestIds,
        )
    }
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
                // Held for exactly as long as the shared table says the
                // Completed decay runs: a shorter hold cancels the decay
                // mid-flight and Idle animates the envelope back UP, which
                // reads as a rebound rather than an afterglow.
                delay(AmbientMotion.holdMillis(AmbientMotionStatus.Completed).milliseconds)
                hadActiveRun = false
                ambientStatus = DesktopAmbientStatus.Idle
            }
            else -> ambientStatus = DesktopAmbientStatus.Idle
        }
    }
    // No pane edge drawn here. The boundary between this pane and whatever sits
    // to its left (rail, or sidebar when open) is already drawn by RailDivider,
    // and this stroke landed immediately beside it — two 1px lines a pixel
    // apart, reading as one thick, slightly wrong border. One owner per
    // boundary: the divider.
    CompositionLocalProvider(
        LocalDesktopApprovalDecision provides approvalHandler,
        LocalDesktopAgentMessageContext provides DesktopAgentMessageContext(
            resolveName = state.agentNamesById::get,
            onAgentClick = actions.onOpenAgent,
        ),
        LocalDesktopA2uiActionHandler provides actions.onA2uiAction,
    ) {
        DesktopAmbientChatBackground(
            status = ambientStatus,
            modifier = modifier
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background),
        ) {
            ChatDetailBody(
                surface = surface,
                state = state,
                actions = actions,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ChatDetailBody(
    surface: DesktopChatSurfaceState,
    state: ChatDetailPaneState,
    actions: ChatDetailPaneActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (state.workingDirectorySupported && surface.selectedConversation != null) {
            DesktopWorkingDirectoryRow(
                path = state.workingDirectory,
                loading = state.workingDirectoryLoading,
                onChangeDirectory = actions.onChangeWorkingDirectory,
            )
        }
        val companion = rememberComposerCompanion(surface, state, onClick = actions.onOpenAgentPane, onEdit = actions.onEditAgent)
        val transport = com.letta.mobile.ui.mascot.LocalMascotTransport.current
        val companionPresent = surface.selectedConversation?.agentId
            ?.let { transport.activeStage(it) == com.letta.mobile.ui.mascot.MascotStage.COMPOSER_COMPANION } ?: false
        ChatDetailContent(surface, state, actions, showThinkingRow = companion == null, modifier = Modifier.weight(1f))
        com.letta.mobile.ui.chat.QueuedSendsPanel(
            queue = state.sendQueue,
            onCancel = actions.queue.onCancel,
            onSendNow = actions.queue.onSendNow,
            onResume = actions.queue.onResume,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.xxl),
        )
        ComposerBar(
            companion = companion,
            companionPresent = companionPresent,
            state = ComposerBarState(
                text = surface.composerText,
                pendingImageAttachments = surface.pendingImageAttachments,
                enabled = surface.canSend,
                modelLabel = surface.composerModelLabel,
                modelOptions = state.modelOptions,
                commands = state.commands,
                mentionables = state.mentionables,
                placeholder = state.composerPlaceholder,
                contextUsage = state.contextUsage,
            ),
            actions = ComposerBarActions(
                onModelSelected = actions.onModelSelected,
                onOpenModelPicker = actions.onOpenModelPicker,
                onTextChanged = actions.onComposerTextChanged,
                onSend = actions.onSend,
                onAttachImage = actions.onAttachImage,
                onOpenCanvas = actions.onOpenCanvas,
                onRemoveImageAttachment = actions.onRemoveImageAttachment,
            ),
        )
    }
}

/** "Opening conversation..." as the agent getting ready: its mascot in the loading orbit beside the status line. */
@Composable
private fun CanonicalStatusRow(status: String, agentId: String?, modifier: Modifier) {
    Row(
        modifier = modifier.padding(horizontal = LettaDimens.Space.xxl, vertical = LettaDimens.Space.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        com.letta.mobile.ui.mascot.MascotLoading(agentId)
        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The thread area above the composer: whichever of the canonical list, status, state panel, welcome or message list applies. */
@Composable
private fun ChatDetailContent(
    surface: DesktopChatSurfaceState,
    state: ChatDetailPaneState,
    actions: ChatDetailPaneActions,
    showThinkingRow: Boolean,
    modifier: Modifier,
) {
    when {
        state.canonicalPresentation != null ->
            DesktopCanonicalMessageList(state.canonicalPresentation, surface.selectedConversation?.agentId, modifier)
        state.canonicalStatus != null -> CanonicalStatusRow(state.canonicalStatus, surface.selectedConversation?.agentId, modifier)
        surface.shouldShowStatePanel -> ChatStatePanel(
            state = surface,
            onRetryConnection = actions.onRetryConnection,
            modifier = modifier,
        )
        surface.isFreshConversation(state) -> NewConversationWelcome(
            agentName = surface.selectedConversation?.agentName,
            agentId = surface.selectedConversation?.agentId,
            identity = surface.selectedConversation?.agentId?.let { state.agentIdentitiesById[it] },
            onStarterPrompt = actions.onComposerTextChanged,
            onOnboardingTask = actions.onOnboardingTask,
            onEditAgent = actions.onEditAgent,
            modifier = modifier,
        )
        else -> MessageList(
            params = MessageListParams(
                conversationId = surface.selectedConversationId,
                renderItems = surface.renderItems,
                isSending = state.isThinking,
                isStreamingReply = state.isStreamingReply,
                showThinkingRow = showThinkingRow,
            ),
            modifier = modifier,
        )
    }
}

private fun DesktopChatSurfaceState.isFreshConversation(state: ChatDetailPaneState): Boolean =
    renderItems.isEmpty() && !state.isThinking

/**
 * The agent keeps the user company at the prompt: its live mascot beside the text box, persistent
 * across the whole conversation, thinking/listening/speaking where the user types. Null when the
 * selected agent has no identity or the host has no renderer (the composer then stands alone).
 */
@Composable
private fun rememberComposerCompanion(
    surface: DesktopChatSurfaceState,
    state: ChatDetailPaneState,
    onClick: () -> Unit,
    onEdit: () -> Unit,
): (@Composable () -> Unit)? {
    val agentId = surface.selectedConversation?.agentId ?: return null
    val identity = state.agentIdentitiesById[agentId] ?: return null
    if (!com.letta.mobile.ui.mascot.mascotAvailable(agentId)) return null
    // The mascot's rest seat. The transport layer draws the character here (and carries it away to
    // the agent pane or the editor); clicking it opens the agent's pane - the mascot is the way in.
    return {
        com.letta.mobile.ui.mascot.MascotSeat(
            agentId = agentId,
            stage = com.letta.mobile.ui.mascot.MascotStage.COMPOSER_COMPANION,
            size = ComposerCompanionSize,
            onClick = onClick,
            onEdit = onEdit,
            empty = {},
        )
    }
}

/** The composer companion's live size; the body spans ~60 % of it. */
private val ComposerCompanionSize = 120.dp

/**
 * letta-mobile folder-settings #2: compact row showing the SELECTED
 * conversation's working directory — the folder the agent's tools (bash,
 * file edits, git) actually operate in — with a folder picker to change it.
 * Only rendered when the active gateway supports this (bundled local
 * runtime); see [ChatDetailPaneState.workingDirectorySupported].
 */
@Composable
private fun DesktopWorkingDirectoryRow(
    path: String?,
    loading: Boolean,
    onChangeDirectory: ((String) -> Unit)?,
) {
    val pickerLauncher = io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher(
        dialogSettings = io.github.vinceglb.filekit.dialogs.FileKitDialogSettings(
            title = "Choose working directory",
        ),
    ) { directory ->
        directory?.let { onChangeDirectory?.invoke(it.file.absolutePath) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onChangeDirectory != null && !loading) { pickerLauncher.launch() }
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = "Working directory",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(LettaDimens.Control.icon),
        )
        Text(
            text = when {
                loading -> "Loading working directory…"
                path != null -> path
                else -> "Working directory unknown"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onChangeDirectory != null) {
            Text(
                text = "Change…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewConversationWelcome(
    agentName: String?,
    agentId: String?,
    identity: com.letta.mobile.avatar.core.MascotIdentity?,
    onStarterPrompt: (String) -> Unit,
    onEditAgent: () -> Unit,
    onOnboardingTask: ((OnboardingTaskKind) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = LettaDimens.Orb.lg, vertical = LettaDimens.Space.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
            modifier = Modifier.widthIn(max = ChatColumnMaxWidth),
        ) {
            // The agent itself, at hero size - the mascot's rest seat while the greeting shows (it
            // slides down to the composer the moment the conversation starts). While the character
            // stands elsewhere (the agent pane is open) the seat is simply empty - no stand-in - and
            // the gradient sphere appears only when this agent has no mascot to draw at all.
            com.letta.mobile.ui.mascot.MascotSeat(
                agentId = agentId,
                stage = com.letta.mobile.ui.mascot.MascotStage.WELCOME_HERO,
                size = 220.dp,
                onEdit = onEditAgent,
            ) { vacancy ->
                if (vacancy == com.letta.mobile.ui.mascot.MascotSeatVacancy.NO_MASCOT) AgentSphere(size = 96.dp)
            }
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
                modifier = Modifier.widthIn(max = ChatProseMaxWidth),
            )
            // First-run 2×2 action grid (Phase 5), category-colored. Each card
            // pre-fills the composer so a fresh agent has an obvious first move.
            FirstRunActionGrid(onAction = onStarterPrompt)
            // Setup tasks as a row of chips: the same three actions, a fifth of the height.
            if (onOnboardingTask != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AgentOnboarding.tasks(agentName).forEach { task ->
                        Surface(
                            onClick = { onOnboardingTask(task.kind) },
                            shape = RoundedCornerShape(LettaDimens.Radius.sm),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
                            ) {
                                Icon(
                                    imageVector = when (task.kind) {
                                        OnboardingTaskKind.SetPersona -> Icons.Outlined.Edit
                                        OnboardingTaskKind.ConnectChannel -> Icons.Outlined.Hub
                                        else -> Icons.Outlined.Build
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(LettaDimens.Control.icon),
                                )
                                Text(task.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
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
    // One row of four: the mascot is the page now, the moves are a strip under it.
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md), modifier = Modifier.fillMaxWidth()) {
            FirstRunCard("Start a conversation", "Just say hello", MaterialTheme.colorScheme.primary, Modifier.weight(1f)) {
                onAction("Hi! Let's get started.")
            }
            FirstRunCard("Seed a memory", "Tell me about you", cc.categoryHumanColor, Modifier.weight(1f)) {
                onAction("Remember this about me: ")
            }
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
        shape = RoundedCornerShape(LettaDimens.Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        // One line: the title is the move; the subtitle rides in the tooltip-sized muted text after it.
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            Box(Modifier.size(LettaDimens.Space.sm).clip(CircleShape).background(accent))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            @Suppress("UNUSED_EXPRESSION") subtitle
        }
    }
}

@Composable
private fun OnboardingTaskRow(task: OnboardingTask, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.lg),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
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
            modifier = Modifier.size(LettaDimens.Control.icon),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
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
internal fun ChatStatePanel(
    state: DesktopChatSurfaceState,
    onRetryConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenStatus = state.chatScreenStatus
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Same ground as the message list and the welcome pane (both paint
            // `background`): this hero sits over the ambient glow beside them,
            // and `surface` made the connect/error state a shade off its own pane.
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = LettaDimens.Orb.lg, vertical = LettaDimens.Space.xxl),
        contentAlignment = Alignment.Center,
    ) {
        val failureHeadline = failureHeadline(screenStatus, state.errorMessage)
        Column(
            modifier = Modifier.widthIn(max = ChatColumnMaxWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
        ) {
            // The wordmark is a welcome, not a diagnosis. When the pane is here
            // because something BROKE, a display-size brand lockup on top pushes
            // the one line that says what happened into second place — so a
            // failure leads with its own headline and drops the wordmark.
            if (failureHeadline != null) {
                Text(
                    text = failureHeadline,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            } else {
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
            }
            Text(
                text = state.errorMessage ?: screenStatus.heroBody(),
                style = MaterialTheme.typography.bodyLarge,
                color = if (failureHeadline != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = ChatProseMaxWidth),
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


/**
 * The headline for a pane that is showing a FAILURE, or null when the pane is
 * simply idle/loading and the brand wordmark is the right thing to show.
 *
 * A carried [errorMessage] means something failed even when the status itself
 * reads as ordinary, so it counts as a failure regardless of [status].
 */
internal fun failureHeadline(status: ChatScreenStatus, errorMessage: String?): String? = when {
    status is ChatScreenStatus.BackendOffline -> "Can't reach the backend"
    status is ChatScreenStatus.SendFailed -> "Message wasn't sent"
    errorMessage != null -> "Something went wrong"
    else -> null
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
