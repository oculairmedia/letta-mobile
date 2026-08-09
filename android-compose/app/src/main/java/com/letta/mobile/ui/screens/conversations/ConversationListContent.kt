package com.letta.mobile.ui.screens.conversations

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.DateSeparator
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.motion.StaggeredListItem
import com.letta.mobile.ui.screens.agentlist.LocalLettaCodeCreateReadiness
import com.letta.mobile.ui.theme.sectionTitle
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.preview.LettaPreviewFrame
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class ConversationListContentState(
    val conversations: List<ConversationDisplay>,
    val isRefreshing: Boolean,
    val isSearchActive: Boolean,
    val showFirstRunOnboarding: Boolean,
    val localReadiness: LocalLettaCodeCreateReadiness,
    val onCreateFirstAgent: () -> Unit,
    val onOpenLocalSettings: () -> Unit,
)

internal fun ConversationDisplay.routeAgentName(): String? =
    agentName.takeIf { it.isNotBlank() && it != conversation.agentId.value.take(8) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationListContent(
    state: ConversationListContentState,
    actions: ConversationListActions,
    modifier: Modifier = Modifier,
) {
    if (state.conversations.isEmpty()) {
        ConversationListEmptyContent(
            state = state,
            modifier = modifier,
        )
        return
    }

    ConversationListRefreshableContent(
        state = state,
        actions = actions,
        modifier = modifier,
    )
}

@Composable
private fun ConversationListEmptyContent(
    state: ConversationListContentState,
    modifier: Modifier = Modifier,
) {
    if (state.showFirstRunOnboarding) {
        FirstRunWelcomeCard(
            localReadiness = state.localReadiness,
            onCreateFirstAgent = state.onCreateFirstAgent,
            onOpenLocalSettings = state.onOpenLocalSettings,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    EmptyState(
        icon = LettaIcons.ChatOutline,
        message = stringResource(
            if (state.isSearchActive) R.string.screen_conversations_search_empty
            else R.string.screen_conversations_empty,
        ),
        modifier = modifier.fillMaxSize(),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ConversationListRefreshableContent(
    state: ConversationListContentState,
    actions: ConversationListActions,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = {
            HapticEffects.confirm(haptic, view)
            actions.onRefresh()
        },
        modifier = modifier.fillMaxSize(),
    ) {
        ConversationListSections(
            conversations = state.conversations,
            actions = actions,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationListSections(
    conversations: List<ConversationDisplay>,
    actions: ConversationListActions,
) {
    val sections = remember(conversations) {
        buildConversationSections(conversations)
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var runningIndex = 0
        sections.forEach { section ->
            val sectionBaseIndex = runningIndex
            item(key = section.key) {
                ConversationSectionHeader(section = section)
            }
            itemsIndexed(
                items = section.items,
                key = { index, display -> "${section.key}:${display.conversation.id}:$index" },
            ) { index, display ->
                StaggeredListItem(index = sectionBaseIndex + index) {
                    SwipeableConversationCard(
                        display = display,
                        callbacks = ConversationCardCallbacks(
                            onClick = { actions.onConversationClick(display) },
                            onOpenAdmin = { actions.onOpenAdmin(display) },
                            onDelete = { actions.onDeleteConversation(display) },
                            onRename = { newName -> actions.onRenameConversation(display, newName) },
                            onTogglePinned = { actions.onTogglePinned(display) },
                            onFork = { actions.onForkConversation(display) },
                            onArchiveToggle = { actions.onArchiveToggle(display) },
                        ),
                    )
                }
            }
            runningIndex += section.items.size
        }
    }
}

/**
 * Wraps [ConversationCard] in a [SwipeToDismissBox]: swipe right (StartToEnd) archives the
 * conversation, swipe left (EndToStart) opens a delete-confirm dialog. Non-gesture equivalents
 * (Archive/Delete in the long-press [ActionSheet][com.letta.mobile.ui.components.ActionSheet])
 * remain on [ConversationCard] itself for TalkBack users, since swipe actions are unreachable by
 * assistive tech.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION") // The non-deprecated rememberSwipeToDismissBoxState overload in
// material3 1.5.0-alpha17 has no confirmValueChange parameter at all — only the
// ConfirmValueChangeDeprecated-flagged overload keeps it. There is no non-deprecated way to veto
// a pending commit before its settle animation runs, which pinned-row resistance and the
// delete-confirm-first flow both require.
@Composable
internal fun SwipeableConversationCard(
    display: ConversationDisplay,
    callbacks: ConversationCardCallbacks,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    var showDeleteConfirm by remember(display.conversation.id) { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (display.isPinned) {
                        // Elastic resistance, not a dead gesture: let the drag/commit-color
                        // animation happen, then veto the settle so it springs back to Settled.
                        HapticEffects.reject(haptic, view)
                        false
                    } else {
                        callbacks.onArchiveToggle()
                        true
                    }
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    // Never let this direction actually dismiss the row: that would animate it
                    // away and leave a ghost if the user cancels. Open the confirm dialog and
                    // veto so the row snaps back; real removal flows through onDelete on confirm.
                    showDeleteConfirm = true
                    false
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )

    // dismissState.targetValue is derived purely from the drag offset vs. the anchor
    // positions (see AnchoredDraggableState.targetValue) — it is unaffected by
    // confirmValueChange, so it flips Settled -> StartToEnd/EndToStart exactly when the
    // positional threshold is crossed, and flips back if the user drags back below it before
    // releasing. Comparing against the last-seen value in a LaunchedEffect keyed on the value
    // itself gives a one-shot cue per transition regardless of how many frames recompose while
    // the drag lingers on one side of the threshold.
    var lastTargetValue by remember(display.conversation.id) {
        mutableStateOf(SwipeToDismissBoxValue.Settled)
    }
    LaunchedEffect(dismissState.targetValue) {
        val current = dismissState.targetValue
        if (current == lastTargetValue) return@LaunchedEffect
        if (current == SwipeToDismissBoxValue.Settled) {
            HapticEffects.segmentTick(haptic, view)
        } else {
            HapticEffects.gestureThreshold(haptic, view)
        }
        lastTargetValue = current
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            ConversationSwipeBackground(
                dismissDirection = dismissState.dismissDirection,
                targetValue = dismissState.targetValue,
                archiveBlocked = display.isPinned,
            )
        },
    ) {
        ConversationCard(display = display, callbacks = callbacks)
    }

    ConfirmDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.screen_conversations_dialog_delete_title),
        message = stringResource(R.string.screen_conversations_dialog_delete_confirm),
        confirmText = stringResource(R.string.action_delete),
        dismissText = stringResource(R.string.action_cancel),
        destructive = true,
        onConfirm = {
            showDeleteConfirm = false
            callbacks.onDelete()
        },
        onDismiss = { showDeleteConfirm = false },
    )
}

private val ConversationSwipeBackgroundShape = RoundedCornerShape(12.dp)

/** Material's disabled-content alpha, used for the archive icon on rows that refuse the swipe. */
private const val BlockedSwipeIconAlpha = 0.38f

/** Visual outcome for a single side of a conversation card swipe. */
private enum class SwipeSide { Archive, Delete }

/**
 * Resolved color pair for a given swipe state. Pulling the three-branch `when`s out of the
 * composable keeps the body's cyclomatic complexity flat — each branch of the original
 * selection now lives in exactly one place.
 */
private data class SwipeBackgroundColors(val background: Color, val onBackground: Color)

/**
 * The [SwipeToDismissBox] background: a rounded rect (matching [ConversationCard]'s own
 * `RoundedCornerShape(12.dp)`, so color doesn't leak into the corner gaps) that stays neutral
 * below the swipe threshold and commits to its action color once [targetValue] crosses it. The
 * icon stays visible in both states (not just the color) so the outcome isn't colorblind-only
 * signalling.
 *
 * When [archiveBlocked] is set (pinned rows, which refuse the archive swipe), the archive
 * direction never reaches the committed look: the background holds its neutral color and the
 * icon dims to the disabled alpha. The background's whole job is to promise the outcome before
 * release, so it must not flash "will archive" green on a row that is about to spring back —
 * the muted state pairs with the REJECT haptic fired from `confirmValueChange`.
 *
 * Extracted to a standalone composable — taking plain enum params instead of a live
 * [SwipeToDismissBoxState] — specifically so it previews directly: seeding
 * `rememberSwipeToDismissBoxState(initialValue = StartToEnd/EndToStart)` for a "mid-swipe"
 * preview of the full [SwipeableConversationCard] depends on [SwipeToDismissBoxState] resolving
 * real anchor pixel widths from a measure pass, which this project has no way to render/inspect
 * (no Android Studio preview renderer available in this environment) to confirm actually paints
 * instead of silently rendering blank. This composable has no such dependency.
 */
@Composable
internal fun ConversationSwipeBackground(
    dismissDirection: SwipeToDismissBoxValue,
    targetValue: SwipeToDismissBoxValue,
    archiveBlocked: Boolean = false,
) {
    if (dismissDirection == SwipeToDismissBoxValue.Settled) {
        // At rest: nothing is being dragged, so there is no side to paint or an icon to show.
        return
    }
    val side = if (dismissDirection == SwipeToDismissBoxValue.StartToEnd) SwipeSide.Archive else SwipeSide.Delete
    val colors = swipeColors(
        side = side,
        committed = targetValue != SwipeToDismissBoxValue.Settled,
        blocked = archiveBlocked,
    )
    val backgroundColor by animateColorAsState(
        targetValue = colors.background,
        label = "conversationSwipeBackgroundColor",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(ConversationSwipeBackgroundShape)
            .background(backgroundColor)
            .padding(horizontal = 24.dp),
        contentAlignment = if (side == SwipeSide.Archive) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Icon(
            imageVector = if (side == SwipeSide.Archive) LettaIcons.Archive else LettaIcons.Delete,
            contentDescription = null,
            tint = colors.onBackground,
        )
    }
}

/**
 * Resolves the background + icon tint pair for a swipe side. [blocked] is the pinned-row muted
 * state, applied only on the archive side — the delete side has no muted counterpart because the
 * delete swipe never actually dismisses (the confirm dialog vetoes it in `confirmValueChange`).
 */
@Composable
private fun swipeColors(side: SwipeSide, committed: Boolean, blocked: Boolean): SwipeBackgroundColors {
    val scheme = MaterialTheme.colorScheme
    val archivedAndBlocked = side == SwipeSide.Archive && blocked
    return when {
        committed && side == SwipeSide.Archive && !archivedAndBlocked ->
            SwipeBackgroundColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
        committed && side == SwipeSide.Delete ->
            SwipeBackgroundColors(scheme.errorContainer, scheme.onErrorContainer)
        archivedAndBlocked ->
            SwipeBackgroundColors(
                scheme.surfaceVariant,
                scheme.onSurfaceVariant.copy(alpha = BlockedSwipeIconAlpha),
            )
        else ->
            SwipeBackgroundColors(scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
}

@Composable
private fun ConversationSectionHeader(section: ConversationSection) {
    when {
        section.isPinned -> ConversationPinnedHeader()
        section.date != null -> DateSeparator(date = section.date)
    }
}

@Composable
private fun ConversationPinnedHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = LettaIcons.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(LettaIconSizing.Inline),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Pinned",
            style = MaterialTheme.typography.sectionTitle,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private data class ConversationSection(
    val key: String,
    val date: LocalDate? = null,
    val isPinned: Boolean = false,
    val items: List<ConversationDisplay>,
)

private fun buildConversationSections(conversations: List<ConversationDisplay>): List<ConversationSection> {
    if (conversations.isEmpty()) return emptyList()

    val deduped = conversations.distinctBy { it.conversation.id }
    val pinned = deduped.filter { it.isPinned }
    val regular = deduped.filterNot { it.isPinned }

    val sections = mutableListOf<ConversationSection>()
    if (pinned.isNotEmpty()) {
        sections += ConversationSection(
            key = "pinned",
            isPinned = true,
            items = pinned,
        )
    }

    regular
        .groupBy { conversationLocalDate(it.conversation) }
        .forEach { (date, items) ->
            sections += ConversationSection(
                key = "date_$date",
                date = date,
                items = items,
            )
        }

    return sections
}

private fun conversationLocalDate(conversation: com.letta.mobile.data.model.Conversation): LocalDate {
    val timestamp = conversation.lastMessageAt ?: conversation.createdAt ?: Instant.EPOCH.toString()
    return runCatching {
        Instant.parse(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    }.getOrDefault(LocalDate.now())
}

// region Previews

private fun previewListConversation(id: String, summary: String, pinned: Boolean = false): ConversationDisplay =
    ConversationDisplay(
        conversation = com.letta.mobile.data.model.Conversation(
            id = ConversationId(id),
            agentId = AgentId("agent-1"),
            summary = summary,
            createdAt = "2026-08-01T09:00:00Z",
            lastMessageAt = "2026-08-07T18:30:00Z",
        ),
        agentName = "General Assistant",
        isPinned = pinned,
    )

private fun previewConversationListContentState(conversations: List<ConversationDisplay>) =
    ConversationListContentState(
        conversations = conversations,
        isRefreshing = false,
        isSearchActive = false,
        showFirstRunOnboarding = false,
        localReadiness = LocalLettaCodeCreateReadiness(),
        onCreateFirstAgent = {},
        onOpenLocalSettings = {},
    )

private fun previewConversationListActions() = ConversationListActions(
    onConversationClick = {},
    onOpenAdmin = {},
    onDeleteConversation = {},
    onRenameConversation = { _, _ -> },
    onTogglePinned = {},
    onForkConversation = {},
    onArchiveToggle = {},
    onRefresh = {},
)

@PreviewLightDark
@Composable
private fun ConversationListContentPreview() {
    // Renders the sectioned list directly: the layoutlib preview renderer
    // cannot execute PullToRefreshBox (NoSuchMethodError), so we bypass
    // ConversationListRefreshableContent here.
    LettaPreviewFrame {
        ConversationListSections(
            conversations = listOf(
                previewListConversation("conv-1", "Weekly planning check-in", pinned = true),
                previewListConversation("conv-2", "Release triage"),
                previewListConversation("conv-3", "Research digest"),
            ),
            actions = previewConversationListActions(),
        )
    }
}

@PreviewLightDark
@Composable
private fun ConversationListContentEmptyPreview() {
    LettaPreviewFrame {
        ConversationListContent(
            state = previewConversationListContentState(emptyList()),
            actions = previewConversationListActions(),
        )
    }
}

private fun previewConversationCardCallbacksForList() = ConversationCardCallbacks(
    onClick = {},
    onOpenAdmin = {},
    onDelete = {},
    onRename = {},
    onTogglePinned = {},
    onFork = {},
    onArchiveToggle = {},
)

@PreviewLightDark
@Composable
private fun SwipeableConversationCardIdlePreview() {
    // At rest (Settled): no seeding needed, so this goes through the real
    // SwipeableConversationCard/SwipeToDismissBox composition — unlike the mid-swipe previews
    // below, there's no anchor-offset dependency here to worry about not resolving.
    LettaPreviewFrame {
        SwipeableConversationCard(
            display = previewListConversation("conv-1", "Weekly planning check-in"),
            callbacks = previewConversationCardCallbacksForList(),
        )
    }
}

@PreviewLightDark
@Composable
private fun SwipeableConversationCardMidArchivePreview() {
    // Seeding rememberSwipeToDismissBoxState(initialValue = StartToEnd) on the full
    // SwipeableConversationCard would only show a mid-swipe offset once a real measure/drag
    // pass resolves the anchor's pixel width, and this project has no way to render/inspect an
    // Android Studio preview here to confirm that actually happens instead of rendering blank.
    // Preview the extracted background directly instead — see ConversationSwipeBackground's doc.
    LettaPreviewFrame {
        Box(modifier = Modifier.fillMaxWidth().height(88.dp)) {
            ConversationSwipeBackground(
                dismissDirection = SwipeToDismissBoxValue.StartToEnd,
                targetValue = SwipeToDismissBoxValue.StartToEnd,
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun SwipeableConversationCardMidDeletePreview() {
    // See SwipeableConversationCardMidArchivePreview: same reasoning, opposite direction.
    LettaPreviewFrame {
        Box(modifier = Modifier.fillMaxWidth().height(88.dp)) {
            ConversationSwipeBackground(
                dismissDirection = SwipeToDismissBoxValue.EndToStart,
                targetValue = SwipeToDismissBoxValue.EndToStart,
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun SwipeableConversationCardArchiveBlockedPreview() {
    // A pinned row past the archive threshold: the background deliberately stays neutral with a
    // dimmed icon rather than committing to tertiaryContainer, because the swipe is about to be
    // vetoed and spring back. Same inputs as the mid-archive preview apart from archiveBlocked.
    LettaPreviewFrame {
        Box(modifier = Modifier.fillMaxWidth().height(88.dp)) {
            ConversationSwipeBackground(
                dismissDirection = SwipeToDismissBoxValue.StartToEnd,
                targetValue = SwipeToDismissBoxValue.StartToEnd,
                archiveBlocked = true,
            )
        }
    }
}

// endregion
