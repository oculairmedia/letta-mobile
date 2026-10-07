package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.chat.surface.timeline.rememberTimelineFadeAlphas
import com.letta.mobile.ui.chat.surface.timeline.timelineFadingEdges
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.pendingUserInputApproval
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_collapsed_needs_input
import com.letta.mobile.sharedui.resources.chat_surface_collapsed_reply
import com.letta.mobile.sharedui.resources.chat_surface_collapsed_reply_unnamed
import com.letta.mobile.sharedui.resources.chat_surface_collapsed_thinking
import com.letta.mobile.sharedui.resources.chat_surface_collapsed_thinking_unnamed
import com.letta.mobile.sharedui.resources.chat_surface_collapsed_working
import com.letta.mobile.sharedui.resources.chat_surface_dock_restore
import com.letta.mobile.sharedui.resources.chat_surface_docked_reply_dismiss
import com.letta.mobile.ui.chat.AgentSphere
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.rememberSmoothedStreamingText
import com.letta.mobile.ui.chat.surface.ambient.ChatAmbient
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.components.movePointerIcon
import com.letta.mobile.ui.markdown.MarkdownPaint
import com.letta.mobile.ui.markdown.SharedMarkdownText
import com.letta.mobile.ui.mascot.MascotSeat
import com.letta.mobile.ui.mascot.MascotSeatVacancy
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.mascotAvailable
import com.letta.mobile.ui.chat.surface.composer.CompanionSeatAnchor
import com.letta.mobile.ui.chat.surface.composer.LocalCompanionSeatAnchors
import com.letta.mobile.ui.theme.ChatMascotDimens
import com.letta.mobile.ui.theme.ChatSurfaceDimens
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/*
 * letta-mobile-bglj6.1: the docked chat minimised. No panel and no header: the agent's mascot
 * stands on the canvas with the prompt bar under it. Send, and the mascot thinks (the agent's
 * presence drives it wherever it stands); the reply arrives as a speech bubble beside it, growing
 * as it streams. The bubble stays until the next prompt or until dismissed; tapping it, or the
 * restore control, opens the panel again at its previous size.
 */

/** What the minimised dock shows; the dock itself owns the gestures. */
@Immutable
internal class CollapsedDockContent(
    val agentId: String?,
    val agentName: String,
    /** Tapping the mascot (the agent pane); null leaves it inert. */
    val openAgent: (() -> Unit)?,
    val editAgent: (() -> Unit)?,
    /** The newest turn as the bubble tells it. */
    val turn: @Composable () -> CollapsedTurn,
    /** What the agent is doing: a halo glows around the mascot and its bubble while it works. */
    val ambient: ChatAmbient = ChatAmbient.Idle,
)

/** The newest turn as the collapsed dock tells it: the reply so far and what the run is doing. */
@Immutable
internal data class CollapsedTurn(
    /** Identifies the turn (its prompt); a dismissed bubble stays dismissed for this turn only. */
    val turnKey: String? = null,
    /** The newest assistant text of the turn; blank before any arrives. */
    val text: String = "",
    val isError: Boolean = false,
    /** [text] is still streaming in. */
    val streaming: Boolean = false,
    /** A tool is running for this turn. */
    val working: Boolean = false,
    /** An approval or a generated form is waiting for the person. */
    val needsInput: Boolean = false,
    /** A run is in flight (or the agent is typing). */
    val busy: Boolean = false,
    /**
     * The turn's question that waits on the person (AskUserQuestion), which the Touch canvas
     * answers in place (letta-mobile-bglj6.1.22); null when there is none.
     */
    val pendingApproval: UiApprovalRequest? = null,
) {
    /** Something to put in the bubble; before this the mascot just thinks. */
    val hasReply: Boolean get() = text.isNotBlank() || working || needsInput

    /**
     * What a dismissal remembers. A conversation with no turn yet (a form waiting before any
     * prompt) has no [turnKey]: it gets one of its own, so "nothing dismissed" (null) never
     * reads as "this was dismissed".
     */
    val dismissKey: String get() = turnKey ?: NO_TURN_DISMISS_KEY

    companion object {
        val None = CollapsedTurn()

        private const val NO_TURN_DISMISS_KEY = "collapsed-turn:none"
    }
}

/**
 * The bubble's view of [newestFirst] (the timeline's render order) and [state]: the newest
 * assistant text of the current turn, whether a tool is running, and whether the turn waits on
 * the person.
 */
internal fun collapsedTurnOf(newestFirst: List<ChatRenderItem>, state: ChatUiState): CollapsedTurn {
    val busy = state.isStreaming || state.isAgentTyping
    val turn = currentTurn(newestFirst)
    if (turn.isEmpty()) return CollapsedTurn(busy = busy, needsInput = state.a2uiSurfaces.isNotEmpty())
    val messages = turn.flatMap { it.messagesInOrder() }
    val replies = messages.filterNot { it.isPrompt() }
    val text = replies.lastOrNull { it.isNarration() }
    val newestReply = replies.lastOrNull()
    val toolRunning = newestReply?.toolCalls.orEmpty().any { it.result == null }
    return CollapsedTurn(
        turnKey = turn.first().key,
        text = text?.content.orEmpty(),
        isError = text?.isError == true,
        streaming = state.isStreaming && text != null && text === newestReply,
        working = busy && (state.pendingTools.isNotEmpty() || toolRunning),
        needsInput = state.a2uiSurfaces.isNotEmpty() || awaitsApproval(messages),
        busy = busy,
        pendingApproval = pendingUserInputApproval(messages),
    )
}

private fun ChatRenderItem.messagesInOrder(): List<UiMessage> = when (this) {
    is ChatRenderItem.Single -> listOf(message)
    is ChatRenderItem.RunBlock -> messages.map { it.first }
}

private fun UiMessage.isPrompt(): Boolean = role == "user" && subagentNotification == null

private fun UiMessage.isNarration(): Boolean =
    content.isNotBlank() && !isReasoning && subagentNotification == null

/** An approval request with no response after it. */
private fun awaitsApproval(messages: List<UiMessage>): Boolean {
    val request = messages.indexOfLast { it.approvalRequest != null }
    return request >= 0 && messages.indexOfLast { it.approvalResponse != null } < request
}

/** The current turn of [params]' conversation, for the collapsed dock. */
@Composable
internal fun rememberCollapsedTurn(params: DockedReplyParams): CollapsedTurn {
    val newestFirst = rememberDockedHistory(params)
    return remember(newestFirst, params.state) { collapsedTurnOf(newestFirst, params.state) }
}

/**
 * The minimised dock above its prompt bar: the mascot (the window's
 * [MascotStage.COMPOSER_COMPANION] seat, so the agent pane can still fly the character away and
 * back) with its bubble. The bar itself is the panel's ([DockedChatPanel] keeps it in place
 * across the fold). Dragging the mascot or the bubble moves the dock; the area around them stays
 * the canvas's.
 */
@Composable
internal fun CollapsedDock(
    state: ChatDockState,
    content: CollapsedDockContent,
    modifier: Modifier = Modifier,
    /** False while the dock opens back into the panel: the companion is on its way to the bar. */
    seated: Boolean = true,
) {
    Column(modifier.testTag(DOCK_COLLAPSED_TAG)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg)) {
            // Even insets on both sides keep the mascot over the bar's centre and the bubble clear
            // of the restore control. The mascot's own motion is the thinking cue: no glow here.
            val sides = LettaDimens.Control.iconButtonLg + LettaDimens.Space.sm
            CollapsedTurnColumn(
                state,
                content,
                seated,
                Modifier.align(Alignment.BottomCenter).padding(horizontal = sides),
            )
            RestoreButton(state, Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** The mascot over the bar's centre and above it the reply bubble (or, while it thinks, only its announcement). */
@Composable
private fun CollapsedTurnColumn(
    state: ChatDockState,
    content: CollapsedDockContent,
    seated: Boolean,
    modifier: Modifier,
) {
    val turn = content.turn()
    // Per turn: the next prompt brings a new turn, and with its reply a new bubble.
    var dismissedTurn by rememberSaveable { mutableStateOf<String?>(null) }
    val showReply = turn.hasReply && turn.dismissKey != dismissedTurn
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            showReply -> ReplyBubble(
                turn = turn,
                agentName = content.agentName,
                actions = BubbleActions(
                    open = state::restore,
                    dismiss = { dismissedTurn = turn.dismissKey },
                ),
                modifier = Modifier.dockDrag(state),
            )
            turn.busy && !turn.hasReply -> ThinkingAnnouncement(content.agentName)
        }
        CollapsedMascot(state, content, seated)
    }
}

/** The agent itself: its mascot, or a sphere for an agent without one. Tap opens the agent pane. */
@Composable
private fun CollapsedMascot(state: ChatDockState, content: CollapsedDockContent, seated: Boolean) {
    Box(
        Modifier
            .size(ChatMascotDimens.composerCompanion)
            .pointerHoverIcon(movePointerIcon())
            .dockDrag(state)
            .testTag(DOCK_COLLAPSED_MASCOT_TAG),
        contentAlignment = Alignment.Center,
    ) {
        // On the chat page the page's one companion seat stands here (it glides over from the
        // open panel's badge as the dock folds); this spot only tells it where.
        val anchors = LocalCompanionSeatAnchors.current
        if (anchors != null && mascotAvailable(content.agentId)) {
            if (seated) CompanionSeatAnchor(anchors)
        } else {
            CollapsedOwnSeat(content)
        }
    }
}

/** Off the chat page, or for an agent without a mascot: a seat of its own, with the sphere stand-in. */
@Composable
private fun CollapsedOwnSeat(content: CollapsedDockContent) {
    MascotSeat(
        agentId = content.agentId,
        stage = MascotStage.COMPOSER_COMPANION,
        size = ChatMascotDimens.composerCompanion,
        onClick = content.openAgent,
        onEdit = content.editAgent,
    ) { vacancy ->
        if (vacancy == MascotSeatVacancy.NO_MASCOT) {
            val open = content.openAgent
            AgentSphere(
                size = ChatMascotDimens.collapsedFallbackSphere,
                modifier = if (open != null) Modifier.clip(CircleShape).clickable(onClick = open) else Modifier,
            )
        }
    }
}

/** The bubble's intents. */
@Immutable
private class BubbleActions(val open: () -> Unit, val dismiss: () -> Unit)

/**
 * The reply beside the mascot: the newest assistant text (markdown, smoothed while it streams,
 * scrolling once it outgrows the bubble), a "working" line while a tool runs and a chip when the
 * turn needs the person. Tapping it opens the panel; the x hides it until the next prompt.
 */
@Composable
private fun ReplyBubble(turn: CollapsedTurn, agentName: String, actions: BubbleActions, modifier: Modifier) {
    val restoreLabel = stringResource(Res.string.chat_surface_dock_restore)
    val working = stringResource(Res.string.chat_surface_collapsed_working)
    val reply = if (agentName.isBlank()) {
        stringResource(Res.string.chat_surface_collapsed_reply_unnamed, turn.text)
    } else {
        stringResource(Res.string.chat_surface_collapsed_reply, agentName, turn.text)
    }
    val announcement = listOfNotNull(reply.takeIf { turn.text.isNotBlank() }, working.takeIf { turn.working })
        .joinToString(" ")
    BubbleSurface(modifier.widthIn(max = ChatSurfaceDimens.collapsedBubbleMaxWidth)) {
        Box {
            Column(
                Modifier
                    .clickable(onClickLabel = restoreLabel, role = Role.Button, onClick = actions.open)
                    .semantics {
                        contentDescription = announcement
                        // Announced once it settles, not on every streamed token.
                        if (!turn.streaming) liveRegion = LiveRegionMode.Polite
                    }
                    .testTag(DOCK_COLLAPSED_BUBBLE_TAG)
                    .padding(
                        start = LettaDimens.Space.md,
                        end = LettaDimens.Space.md + LettaDimens.Control.iconButtonSm,
                        top = LettaDimens.Space.sm,
                        bottom = LettaDimens.Space.sm + ChatSurfaceDimens.collapsedBubbleTailHeight,
                    ),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            ) {
                if (turn.text.isNotBlank()) BubbleText(turn)
                if (turn.working) WorkingLine(working)
                if (turn.needsInput) NeedsInputChip(actions.open)
            }
            IconButton(
                onClick = actions.dismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(LettaDimens.Space.xs)
                    .size(LettaDimens.Control.iconButtonSm)
                    .testTag(DOCK_COLLAPSED_DISMISS_TAG),
            ) {
                Icon(
                    Lucide.X,
                    contentDescription = stringResource(Res.string.chat_surface_docked_reply_dismiss),
                    modifier = Modifier.size(LettaDimens.Control.iconSm),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The reply's markdown, following the newest line while it streams. */
@Composable
internal fun BubbleText(
    turn: CollapsedTurn,
    maxHeight: Dp = ChatSurfaceDimens.collapsedBubbleMaxHeight,
    /** The body's type role; null keeps the timeline's. */
    textStyle: TextStyle? = null,
    /** With a length, the text dissolves at whichever edge has more to scroll to (the timeline's fades). */
    fadeLength: Dp? = null,
) {
    val shown = if (turn.streaming) rememberSmoothedStreamingText(rawText = turn.text, isStreaming = true) else turn.text
    val scroll = rememberScrollState()
    LaunchedEffect(scroll, turn.streaming) {
        if (turn.streaming) snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
    }
    val fade = if (fadeLength == null) {
        Modifier
    } else {
        val fades = rememberTimelineFadeAlphas(
            canScrollTowardOlder = scroll.canScrollBackward,
            canScrollTowardNewer = scroll.canScrollForward,
            promptPinned = false,
        )
        Modifier.timelineFadingEdges(fades, fadeLength, fadeLength)
    }
    Box(
        Modifier
            .heightIn(max = maxHeight)
            .then(fade)
            .verticalScroll(scroll)
            // The bubble announces the whole reply itself.
            .clearAndSetSemantics { },
    ) {
        SharedMarkdownText(
            text = shown,
            // Retaining the previous AST across a reshaped update can crash Compose Desktop.
            retainState = false,
            paint = MarkdownPaint(
                textColor = if (turn.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                textStyle = textStyle,
            ),
        )
    }
}

/** Tool activity, summarised (the full cards are in the panel); the halo is its animation. */
@Composable
internal fun WorkingLine(label: String) {
    Text(
        label,
        modifier = Modifier.clearAndSetSemantics { },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** An approval or a form is waiting: open the panel, where it can be answered. */
@Composable
internal fun NeedsInputChip(onOpen: () -> Unit) {
    AssistChip(
        onClick = onOpen,
        label = { Text(stringResource(Res.string.chat_surface_collapsed_needs_input)) },
        modifier = Modifier.testTag(DOCK_COLLAPSED_NEEDS_INPUT_TAG),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        border = null,
    )
}

/**
 * Sent, nothing back yet: the halo behind the mascot shows it thinking, so there is no bubble to
 * see. This only says so to a screen reader.
 */
@Composable
internal fun ThinkingAnnouncement(agentName: String) {
    val label = if (agentName.isBlank()) {
        stringResource(Res.string.chat_surface_collapsed_thinking_unnamed)
    } else {
        stringResource(Res.string.chat_surface_collapsed_thinking, agentName)
    }
    Spacer(
        Modifier
            .semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            }
            .testTag(DOCK_COLLAPSED_THINKING_TAG),
    )
}

/** The bubble itself: the chat's neutral surface, lifted by its shadow, tail down to the mascot. */
@Composable
private fun BubbleSurface(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = BubbleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = ChatSurfaceDimens.dockedReplyElevation,
        border = BorderStroke(
            LettaDimens.Stroke.hairline,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline),
        ),
        content = content,
    )
}

/** Opens the panel again at its previous size. */
@Composable
private fun RestoreButton(state: ChatDockState, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        shadowElevation = ChatSurfaceDimens.dockedReplyElevation,
        border = BorderStroke(
            LettaDimens.Stroke.hairline,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline),
        ),
    ) {
        IconButton(
            onClick = state::restore,
            modifier = Modifier.size(LettaDimens.Control.iconButtonLg).testTag(DOCK_RESTORE_TAG),
        ) {
            DisclosureChevron(
                expanded = false,
                contentDescription = stringResource(Res.string.chat_surface_dock_restore),
                opensUpward = true,
            )
        }
    }
}

/**
 * A rounded rectangle with a tail at its bottom-left corner, reaching down and out towards the
 * mascot standing to its left. The tail takes [tailWidth] of the shape's width.
 */
internal class SpeechBubbleShape(
    private val radius: Dp,
    private val tailWidth: Dp,
    private val tailHeight: Dp,
    /** The tail reaches out of the right edge instead of the left (towards a speaker on the right). */
    private val tailAtEnd: Boolean = false,
    /** The tail leaves from the top corner instead of the bottom one. */
    private val tailAtTop: Boolean = false,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }
        val tw = with(density) { tailWidth.toPx() }
        val th = with(density) { tailHeight.toPx() }
        val body = Path().apply {
            addRoundRect(RoundRect(if (tailAtEnd) 0f else tw, 0f, if (tailAtEnd) size.width - tw else size.width, size.height, CornerRadius(r)))
        }
        // Drawn for a bottom-left tail, then mirrored into place.
        val x: (Float) -> Float = { if (tailAtEnd) size.width - it else it }
        val y: (Float) -> Float = { if (tailAtTop) size.height - it else it }
        val tail = Path().apply {
            // A short bubble (the thinking dots) keeps the tail in its lower part.
            moveTo(x(tw), y((size.height - r - th).coerceAtLeast(size.height * TAIL_MIN_TOP_FRACTION)))
            lineTo(x(0f), y(size.height))
            lineTo(x(tw + r), y(size.height))
            close()
        }
        return Outline.Generic(Path().apply { op(body, tail, PathOperation.Union) })
    }
}

/** A rounded rectangle with a short tail from the middle of its bottom edge, down to the mascot below. */
internal class BottomTailBubbleShape(private val radius: Dp, private val tailWidth: Dp, private val tailHeight: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }
        val tw = with(density) { tailWidth.toPx() }
        val th = with(density) { tailHeight.toPx() }
        val bottom = size.height - th
        val body = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, bottom, CornerRadius(r))) }
        val mid = size.width / 2f
        val tail = Path().apply {
            moveTo(mid - tw, bottom - 1f)
            lineTo(mid, size.height)
            lineTo(mid + tw, bottom - 1f)
            close()
        }
        return Outline.Generic(Path().apply { op(body, tail, PathOperation.Union) })
    }
}

private val BubbleShape = BottomTailBubbleShape(
    radius = LettaDimens.Radius.lg,
    tailWidth = ChatSurfaceDimens.collapsedBubbleTailWidth,
    tailHeight = ChatSurfaceDimens.collapsedBubbleTailHeight,
)

private const val TAIL_MIN_TOP_FRACTION = 0.45f

internal const val DOCK_COLLAPSED_TAG = "chat-dock-collapsed"
internal const val DOCK_COLLAPSED_MASCOT_TAG = "chat-dock-collapsed-mascot"
internal const val DOCK_COLLAPSED_BUBBLE_TAG = "chat-dock-collapsed-bubble"
internal const val DOCK_COLLAPSED_DISMISS_TAG = "chat-dock-collapsed-dismiss"
internal const val DOCK_COLLAPSED_THINKING_TAG = "chat-dock-collapsed-thinking"
internal const val DOCK_COLLAPSED_NEEDS_INPUT_TAG = "chat-dock-collapsed-needs-input"
