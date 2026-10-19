package com.letta.mobile.ui.chat.subagents

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.ui.host.InspectorPane
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion

@Immutable
data class SubagentTodoSheetTarget(
    val toolCallId: String,
    val description: String,
    /**
     * The correlated subagent agent id (`agent-local-*`)
     * for the "view conversation" affordance in the sheet. Null when the shim
     * has not yet correlated the dispatch to a concrete run — the affordance
     * is then hidden ([canViewConversation] is false).
     */
    val subagentAgentId: String? = null,
    val subagentConversationId: String? = null,
) {
    /** Whether the sheet should offer the "view conversation" affordance. */
    val canViewConversation: Boolean get() = !subagentAgentId.isNullOrBlank()

    val subagentNavigationConversationId: String?
        get() = subagentConversationId?.takeIf { it.isNotBlank() }
}

/** The sheet a ring (or a dispatch row) opens. */
fun ActiveSubagent.sheetTarget(): SubagentTodoSheetTarget = SubagentTodoSheetTarget(
    toolCallId = id,
    description = description,
    subagentAgentId = subagentAgentId,
    subagentConversationId = subagentConversationId,
)

val LocalSubagentTodoSheetOpener = staticCompositionLocalOf<(SubagentTodoSheetTarget) -> Unit> {
    {}
}

/**
 * Pure, UI-agnostic state model for the tap-to-todolist
 * sheet. Kept free of Compose types so the load -> map -> render pipeline (and
 * the progress meter math) is unit-testable without a Compose harness.
 */
sealed interface SubagentTodoSheetState {
    /** The `subagent_todos` round-trip is in flight. */
    data object Loading : SubagentTodoSheetState

    /** The round-trip failed; [message] is a short, user-facing reason. */
    data class Error(val message: String) : SubagentTodoSheetState

    /** Resolved but the subagent has not written any TodoWrite items yet. */
    data object Empty : SubagentTodoSheetState

    /**
     * Resolved with a non-empty checklist. [items] preserves the shim's
     * order; [progress] is the derived progress meter.
     */
    data class Content(
        val items: List<SubagentTodoItem>,
        val progress: SubagentTodoProgress,
    ) : SubagentTodoSheetState
}

/** One rendered checklist row: the label to show + its normalized status. */
data class SubagentTodoItem(
    val label: String,
    val status: SubagentTodoStatus,
)

/**
 * Normalized TodoWrite status. Mirrors the tool vocabulary
 * (`pending | in_progress | completed`); any unknown wire value degrades to
 * [PENDING] so a forward-compat shim status still renders a row.
 */
enum class SubagentTodoStatus { PENDING, IN_PROGRESS, COMPLETED }

/** Progress meter: how many items are done out of the total. */
data class SubagentTodoProgress(val completed: Int, val total: Int) {
    /** Display label, e.g. "3/5 done". */
    val label: String get() = "$completed/$total done"

    /** Fraction in [0f, 1f]; 0f when there are no items (avoids NaN). */
    val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total.toFloat()
}

/**
 * Map one wire [SubagentTodo] to its display row. While in progress the
 * [SubagentTodo.activeForm] (e.g. "Running tests") is the more informative
 * label; otherwise the canonical [SubagentTodo.content] is used. Falls back
 * across the two so a row is never blank.
 */
internal fun SubagentTodo.toItem(): SubagentTodoItem {
    val normalized = status.toSubagentTodoStatus()
    val label = when (normalized) {
        SubagentTodoStatus.IN_PROGRESS -> activeForm.ifBlank { content }
        else -> content.ifBlank { activeForm }
    }.ifBlank { "Untitled todo" }
    return SubagentTodoItem(label = label, status = normalized)
}

internal fun String.toSubagentTodoStatus(): SubagentTodoStatus = when (trim().lowercase()) {
    "completed" -> SubagentTodoStatus.COMPLETED
    "in_progress" -> SubagentTodoStatus.IN_PROGRESS
    else -> SubagentTodoStatus.PENDING
}

/**
 * Reduce a resolved todos result into the sheet state. Centralizes the
 * empty/error/content fork and the progress-count math so it can be unit
 * tested directly (no Compose needed).
 */
fun subagentTodoSheetStateFrom(result: Result<List<SubagentTodo>>): SubagentTodoSheetState =
    result.fold(
        onSuccess = { todos ->
            if (todos.isEmpty()) {
                SubagentTodoSheetState.Empty
            } else {
                val items = todos.map { it.toItem() }
                val completed = items.count { it.status == SubagentTodoStatus.COMPLETED }
                SubagentTodoSheetState.Content(
                    items = items,
                    progress = SubagentTodoProgress(completed = completed, total = items.size),
                )
            }
        },
        onFailure = { e ->
            SubagentTodoSheetState.Error(e.message?.takeIf { it.isNotBlank() } ?: "Couldn't load todos")
        },
    )

/** Where an open sheet's checklist comes from: one round-trip per opened target. */
fun interface SubagentTodoLoader {
    suspend fun todos(target: SubagentTodoSheetTarget): Result<List<SubagentTodo>>
}

/**
 * An open [target]'s sheet, the same on every client: it loads the
 * target's todos once through [loader] (Loading until they arrive) and draws [SubagentTodoSheet].
 */
@Composable
fun SubagentTodoSheetHost(
    target: SubagentTodoSheetTarget,
    loader: SubagentTodoLoader,
    onDismiss: () -> Unit,
    onViewConversation: (() -> Unit)? = null,
    roster: (@Composable () -> Unit)? = null,
) {
    var state by remember(target.toolCallId) { mutableStateOf<SubagentTodoSheetState>(SubagentTodoSheetState.Loading) }
    LaunchedEffect(loader, target.toolCallId) {
        state = subagentTodoSheetStateFrom(loader.todos(target))
    }
    SubagentTodoSheet(
        description = target.description,
        state = state,
        onDismiss = onDismiss,
        onViewConversation = onViewConversation,
        roster = roster,
    )
}

/**
 * The tap-to-todolist sheet. Opens when a ring is tapped. Renders the tapped
 * subagent's [description] as the header plus its TodoWrite checklist (per-item status icon) and a
 * "N/M done" progress meter, with explicit loading / error / empty states.
 *
 * Perf / motion notes (do NOT regress rmzmo):
 *  - The sheet is only composed while open (the call site gates on a nullable
 *    target), so there is zero cost on the hot chat/streaming path when
 *    closed. The todos fetch is a one-shot point-in-time round-trip kicked
 *    off by the host on open — no live tail, no per-frame work.
 *  - Reduced-motion: the state [Crossfade] is collapsed to an instant swap
 *    (zero-duration tween) and the in-progress row icon does not animate.
 */
@Composable
fun SubagentTodoSheet(
    description: String,
    state: SubagentTodoSheetState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // letta-mobile-vo9y1: when non-null, the sheet shows a "view conversation"
    // action in the header that jumps to the subagent's transcript. Null hides
    // the affordance (no correlated agent id).
    onViewConversation: (() -> Unit)? = null,
    // letta-mobile-ulcg6 (PR4): every subagent of this conversation, below this
    // one's todos - the phone's view of the shared roster the desktop pane shows.
    roster: (@Composable () -> Unit)? = null,
) {
    val reducedMotion = LocalReducedMotion.current

    // letta-mobile-ckmub.3.15 (S13): the window's inspector, docked when wide and a sheet when
    // compact; it draws the description as its title, with "view conversation" beside close.
    InspectorPane(
        title = description.ifBlank { "Subagent" },
        onClose = onDismiss,
        modifier = modifier,
        testTag = SubagentTodoSheetTags.SHEET,
        actions = {
            if (onViewConversation != null) {
                ViewConversationButton(
                    description = description,
                    onClick = onViewConversation,
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = LettaDimens.Space.lg,
                    end = LettaDimens.Space.lg,
                    bottom = LettaDimens.Space.xxl,
                ),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            Crossfade(
                targetState = state,
                animationSpec = tween(durationMillis = if (reducedMotion) 0 else STATE_FADE_MS),
                label = "subagentTodoSheetState",
            ) { current ->
                when (current) {
                    SubagentTodoSheetState.Loading -> SubagentTodoLoading()
                    is SubagentTodoSheetState.Error -> SubagentTodoMessage(
                        text = current.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                    SubagentTodoSheetState.Empty -> SubagentTodoMessage(
                        text = "No todos yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is SubagentTodoSheetState.Content -> SubagentTodoContent(current)
                }
            }
            roster?.let { section ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // The roster is a list of its own: bounded, so the todos above stay in view.
                Box(Modifier.fillMaxWidth().heightIn(max = SheetListMaxHeight)) { section() }
            }
        }
    }
}

/** Test tags of the todo sheet. */
object SubagentTodoSheetTags {
    const val SHEET = "subagent-todo-sheet"
}

/** The checklist and the roster each scroll inside the sheet past this height. */
private val SheetListMaxHeight = LettaDimens.Pane.editorMinHeight * 2
private const val STATE_FADE_MS = 200

/**
 * Header action that jumps from the todo sheet to the
 * subagent's full conversation/transcript. Uses the external-link glyph +
 * label so the affordance reads clearly as "leave this sheet, open that
 * agent's chat".
 */
@Composable
private fun ViewConversationButton(
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(LettaDimens.Radius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs)
            .semantics { contentDescription = "View conversation: $description" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Icon(
            imageVector = LettaIcons.ExternalLink,
            contentDescription = null,
            modifier = Modifier.size(LettaDimens.Control.icon),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "View chat",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

@Composable
private fun SubagentTodoLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LettaDimens.Space.xl),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(LettaDimens.Space.xl),
            strokeWidth = LettaDimens.Space.hair,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SubagentTodoMessage(text: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LettaDimens.Space.xl),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

@Composable
private fun SubagentTodoContent(content: SubagentTodoSheetState.Content) {
    // Progress meter: "N/M done" + a thin bar.
    val progress = content.progress
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Progress: ${progress.label}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                text = progress.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = SheetListMaxHeight),
            contentPadding = PaddingValues(vertical = LettaDimens.Space.xs),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            items(items = content.items) { item ->
                SubagentTodoRow(item)
            }
        }
    }
}

@Composable
private fun SubagentTodoRow(item: SubagentTodoItem) {
    val (icon, tint, accessibilityStatus) = item.status.rowVisuals()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$accessibilityStatus: ${item.label}" },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .padding(top = LettaDimens.Space.hair)
                .size(LettaDimens.Space.lg),
            tint = tint,
        )
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.status == SubagentTodoStatus.COMPLETED) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textDecoration = if (item.status == SubagentTodoStatus.COMPLETED) {
                TextDecoration.LineThrough
            } else {
                null
            },
        )
    }
}

/** Per-status icon + tint + accessibility label. Composable for theme colors. */
@Composable
private fun SubagentTodoStatus.rowVisuals(): Triple<ImageVector, Color, String> =
    when (this) {
        SubagentTodoStatus.COMPLETED -> Triple(
            LettaIcons.CheckCircle,
            MaterialTheme.colorScheme.primary,
            "Completed",
        )
        SubagentTodoStatus.IN_PROGRESS -> Triple(
            LettaIcons.AccessTime,
            MaterialTheme.colorScheme.primary,
            "In progress",
        )
        SubagentTodoStatus.PENDING -> Triple(
            LettaIcons.Circle,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Pending",
        )
    }
