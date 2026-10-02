package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.timeline_close_a2ui
import com.letta.mobile.sharedui.resources.timeline_goal_clear
import com.letta.mobile.sharedui.resources.timeline_goal_complete
import com.letta.mobile.sharedui.resources.timeline_goal_continue
import com.letta.mobile.sharedui.resources.timeline_goal_refresh
import com.letta.mobile.sharedui.resources.timeline_goal_pause
import com.letta.mobile.sharedui.resources.timeline_goal_resume
import com.letta.mobile.sharedui.resources.timeline_goal_title
import com.letta.mobile.sharedui.resources.timeline_goal_title_status
import com.letta.mobile.sharedui.resources.timeline_goal_usage
import com.letta.mobile.sharedui.resources.timeline_goal_usage_budget
import com.letta.mobile.ui.a2ui.A2uiSurfaceRenderer
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.render.A2uiActionSnackbarUi
import com.letta.mobile.ui.chat.render.ChatSnackbarDuration
import com.letta.mobile.ui.chat.render.GoalStatusUi
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatTimelineDimens
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.collections.immutable.ImmutableMap
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.widthIn

/**
 * letta-mobile-bglj6.1: the A2UI surfaces stacked at the bottom of the timeline (Android
 * ChatScreenContent A2uiSurfaceStack), ordered by surface id so the stack is stable.
 */
@Composable
internal fun A2uiSurfaceStack(
    surfaces: ImmutableMap<String, A2uiSurfaceState>,
    resolvedActionCounters: ImmutableMap<String, Int>,
    actions: ChatActions,
    modifier: Modifier = Modifier,
) {
    if (surfaces.isEmpty()) return
    val ordered = remember(surfaces) { surfaces.values.sortedBy(A2uiSurfaceState::surfaceId) }
    Column(
        modifier = modifier.widthIn(max = ChatColumnMaxWidth).testTag(ChatTimelineTags.A2UI_STACK),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        ordered.forEach { surface ->
            key(surface.surfaceId) {
                DismissibleA2uiSurface(surface.surfaceId, actions::dismissA2uiSurface) {
                    A2uiSurfaceRenderer(
                        surface = surface,
                        modifier = Modifier.fillMaxWidth(),
                        onAction = actions::submitA2uiAction,
                        actionResolutionToken = resolvedActionCounters[surface.surfaceId] ?: 0,
                    )
                }
            }
        }
    }
}

@Composable
private fun DismissibleA2uiSurface(surfaceId: String, onDismiss: (String) -> Unit, content: @Composable () -> Unit) {
    val closeLabel = stringResource(Res.string.timeline_close_a2ui)
    Box(
        modifier = Modifier.fillMaxWidth().semantics {
            customActions = listOf(CustomAccessibilityAction(closeLabel) { onDismiss(surfaceId); true })
        },
    ) {
        content()
        IconButton(onClick = { onDismiss(surfaceId) }, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(LettaIcons.Close, contentDescription = closeLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Shows each A2UI action outcome once, then reports it shown. A retryable failure offers its retry,
 * which resubmits the action (Android's A2UI snackbar).
 */
@Composable
internal fun A2uiSnackbarEffect(
    snackbar: A2uiActionSnackbarUi?,
    hostState: SnackbarHostState,
    actions: ChatActions,
) {
    val currentActions by rememberUpdatedState(actions)
    LaunchedEffect(snackbar?.id) {
        val shown = snackbar ?: return@LaunchedEffect
        val result = hostState.showSnackbar(
            message = shown.message,
            actionLabel = shown.actionLabel,
            duration = shown.duration.toMaterial(),
        )
        currentActions.markA2uiSnackbarShown(shown.id)
        val retry: A2uiAction? = shown.retryAction
        if (result == SnackbarResult.ActionPerformed && retry != null) currentActions.submitA2uiAction(retry)
    }
}

/** A transient chat error: shown once, then cleared with the owner. */
@Composable
internal fun ErrorSnackbarEffect(error: String?, hostState: SnackbarHostState, actions: ChatActions) {
    val currentActions by rememberUpdatedState(actions)
    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        hostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        currentActions.clearError()
    }
}

private fun ChatSnackbarDuration.toMaterial(): SnackbarDuration = when (this) {
    ChatSnackbarDuration.Short -> SnackbarDuration.Short
    ChatSnackbarDuration.Indefinite -> SnackbarDuration.Indefinite
}

/**
 * What the goal card's controls do. Pause/resume/done/clear are `/goal` commands sent through
 * [onCommand]; refresh and continue are owner actions, null (hidden) when the owner has none.
 */
@Immutable
internal class GoalCardActions(
    val onCommand: (String) -> Unit,
    val onRefresh: (() -> Unit)? = null,
    val onContinue: (() -> Unit)? = null,
) {
    companion object {
        fun of(actions: ChatActions, capabilities: ChatSurfaceCapabilities): GoalCardActions = GoalCardActions(
            onCommand = actions::sendText,
            onRefresh = if (capabilities.goals) actions::refreshGoalStatus else null,
            onContinue = if (capabilities.goals) actions::continueGoal else null,
        )
    }
}

/** letta-mobile-bglj6.1: the active goal (Android GoalStatusCard). */
@Composable
internal fun GoalStatusCard(goal: GoalStatusUi, loading: Boolean, actions: GoalCardActions, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .widthIn(max = ChatColumnMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm)
            .testTag(ChatTimelineTags.GOAL),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ChatTimelineDimens.Alpha.goalCard),
        shape = MaterialTheme.shapes.large,
        tonalElevation = LettaDimens.Space.xs,
    ) {
        Column(
            modifier = Modifier.padding(LettaDimens.Space.md),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            GoalHeader(goal, loading, actions.onRefresh)
            GoalDetails(goal)
            GoalActions(goal, actions)
        }
    }
}

@Composable
private fun GoalHeader(goal: GoalStatusUi, loading: Boolean, onRefresh: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (loading) {
                stringResource(Res.string.timeline_goal_title)
            } else {
                stringResource(Res.string.timeline_goal_title_status, goal.status)
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        onRefresh?.let { refresh -> TextButton(onClick = refresh) { Text(stringResource(Res.string.timeline_goal_refresh)) } }
    }
}

@Composable
private fun GoalDetails(goal: GoalStatusUi) {
    Text(text = goal.objective, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    val budget = goal.tokenBudget
    Text(
        text = if (budget != null) {
            stringResource(Res.string.timeline_goal_usage_budget, goal.tokensUsed, budget, goal.activeTimeSeconds)
        } else {
            stringResource(Res.string.timeline_goal_usage, goal.tokensUsed, goal.activeTimeSeconds)
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The `/goal` command each control sends. Public for tests. */
internal object GoalCommands {
    const val PAUSE = "/goal pause"
    const val RESUME = "/goal resume"
    const val COMPLETE = "/goal complete"
    const val CLEAR = "/goal clear"
}

@Composable
private fun GoalActions(goal: GoalStatusUi, actions: GoalCardActions) {
    val onCommand = actions.onCommand
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        if (goal.status == "complete") {
            TextButton(onClick = { onCommand(GoalCommands.CLEAR) }) { Text(stringResource(Res.string.timeline_goal_clear)) }
            return@Row
        }
        actions.onContinue?.let { proceed ->
            Button(onClick = proceed, enabled = goal.status == "active") { Text(stringResource(Res.string.timeline_goal_continue)) }
        }
        if (goal.status == "paused") {
            TextButton(onClick = { onCommand(GoalCommands.RESUME) }) { Text(stringResource(Res.string.timeline_goal_resume)) }
        } else {
            TextButton(onClick = { onCommand(GoalCommands.PAUSE) }, enabled = goal.status == "active") {
                Text(stringResource(Res.string.timeline_goal_pause))
            }
        }
        TextButton(onClick = { onCommand(GoalCommands.COMPLETE) }) { Text(stringResource(Res.string.timeline_goal_complete)) }
        TextButton(onClick = { onCommand(GoalCommands.CLEAR) }) { Text(stringResource(Res.string.timeline_goal_clear)) }
    }
}
