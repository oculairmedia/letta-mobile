package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CloudOff
import com.composables.icons.lucide.Lucide
import com.letta.mobile.data.chat.approval.ApprovalStepDecision
import com.letta.mobile.data.chat.approval.ApprovalStepper
import com.letta.mobile.data.chat.approval.ApprovalSteps
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_approval_queued
import com.letta.mobile.sharedui.resources.rows_approval_step
import com.letta.mobile.sharedui.resources.rows_approval_step_back
import com.letta.mobile.sharedui.resources.rows_approval_step_next
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bzvro.14 (F14, G47): the page's stepper over the approvals waiting on the person.
 * With more than one, the card is drawn on one request at a time with a "1 of N" header; each
 * decision is held ([ApprovalStepper]) and the batch goes to [ChatActions.submitApproval] once the
 * last one is decided, in the order the requests were made.
 */
@Stable
internal class ApprovalStepperState(actions: ChatActions) {
    var steps: ApprovalSteps by mutableStateOf(ApprovalSteps())
        private set

    private var actions: ChatActions = actions

    fun bind(actions: ChatActions) {
        this.actions = actions
    }

    fun reconcile(messages: List<UiMessage>) {
        steps = ApprovalStepper.reconcile(steps, ApprovalStepper.pendingIn(messages))
    }

    /** Whether [requestId]'s card is drawn: always without stepping, else only for the current step. */
    fun shows(requestId: String): Boolean = !steps.stepping || steps.currentRequestId == requestId

    /** Routes [answer] through the stepper while stepping; true when it took it. */
    fun decide(answer: ChatApprovalAnswer): Boolean {
        if (!steps.stepping) return false
        val (next, batch) = ApprovalStepper.take(ApprovalStepper.decide(steps, answer.toDecision()))
        steps = next
        batch.forEach { actions.submitApproval(it.toAnswer()) }
        return true
    }

    fun back() {
        steps = ApprovalStepper.back(steps)
    }

    fun forward() {
        steps = ApprovalStepper.forward(steps)
    }
}

internal val LocalApprovalStepper = staticCompositionLocalOf<ApprovalStepperState?> { null }

/** The stepper for [messages], reconciled whenever they change. */
@Composable
internal fun rememberApprovalStepper(messages: List<UiMessage>, actions: ChatActions): ApprovalStepperState {
    val current by rememberUpdatedState(actions)
    val stepper = remember { ApprovalStepperState(current) }
    stepper.bind(current)
    LaunchedEffect(messages) { stepper.reconcile(messages) }
    return stepper
}

/** "Approval 2 of 3" with back and next, over the current step's card. */
@Composable
internal fun ApprovalStepHeader(steps: ApprovalSteps, onBack: () -> Unit, onForward: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.APPROVAL_STEP_HEADER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        IconButton(onClick = onBack, enabled = steps.canGoBack(), modifier = Modifier.testTag(ChatRowTestTags.APPROVAL_STEP_BACK)) {
            Icon(Lucide.ChevronLeft, contentDescription = stringResource(Res.string.rows_approval_step_back))
        }
        Text(
            text = stringResource(Res.string.rows_approval_step, steps.position, steps.total),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ChatRowTestTags.APPROVAL_STEP_COUNTER),
        )
        IconButton(onClick = onForward, enabled = steps.canGoForward(), modifier = Modifier.testTag(ChatRowTestTags.APPROVAL_STEP_NEXT)) {
            Icon(Lucide.ChevronRight, contentDescription = stringResource(Res.string.rows_approval_step_next))
        }
    }
}

private fun ChatApprovalAnswer.toDecision() = ApprovalStepDecision(
    requestId = requestId,
    toolCallIds = toolCallIds,
    approve = approve,
    reason = reason,
    selectedSuggestionIds = selectedSuggestionIds,
    suggestionBinding = suggestionBinding,
)

private fun ApprovalStepDecision.toAnswer() = ChatApprovalAnswer(
    requestId = requestId,
    toolCallIds = toolCallIds,
    approve = approve,
    reason = reason,
    selectedSuggestionIds = selectedSuggestionIds,
    suggestionBinding = suggestionBinding,
)

/** letta-mobile-bzvro.14: the answer was made while the connection was down and waits in the outbox. */
@Composable
internal fun ApprovalQueuedNote() {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.APPROVAL_QUEUED),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Icon(
            Lucide.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(LettaDimens.Control.iconSm),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.rows_approval_queued),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
