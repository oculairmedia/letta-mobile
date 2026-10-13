package com.letta.mobile.ui.shell.pages.runs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.data.runs.RunDetail
import com.letta.mobile.data.runs.RunFormat
import com.letta.mobile.data.runs.isActive
import com.letta.mobile.data.settings.SettingsConfirmText
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.ui.host.InspectorPane
import com.letta.mobile.ui.settings.ActionButton
import com.letta.mobile.ui.settings.SettingsHeader
import com.letta.mobile.ui.settings.SettingsSection
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags inside the run detail. */
object RunDetailTags {
    const val OPEN_CONVERSATION = "open-conversation"
    const val CANCEL = "cancel-run"
    const val DELETE = "delete-run"
    const val LIMITED = "run-limited"
}

/** The open run: its facts, its steps and usage, and what the host lets the user do with it. */
@Composable
internal fun RunDetailPane(state: RunsPageState, actions: RunsPageActions) {
    InspectorPane(title = DETAIL_TITLE, onClose = actions.onCloseRun, testTag = RunsPageTags.DETAIL) {
        val detail = state.monitor.detail
        if (detail == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(LettaDimens.Space.lg))
            return@InspectorPane
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LettaDimens.Space.lg),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            RunFacts(detail, state)
            RunSteps(detail)
            RunActions(detail, state, actions)
        }
    }
}

@Composable
private fun RunFacts(detail: RunDetail, state: RunsPageState) {
    val run = detail.run
    SettingsSection(SettingsHeader("run-facts", "Run")) {
        runFacts(detail, state.agentName(run.agentId)).forEach { InfoRow(it) }
    }
}

/** The run's facts as rows, leaving out what the host did not report. */
internal fun runFacts(detail: RunDetail, agentName: String): List<SettingsRow.Info> {
    val run = detail.run
    val usage = detail.usage
    return listOfNotNull(
        SettingsRow.Info("run-id", "Run", run.id),
        SettingsRow.Info("run-agent", "Agent", agentName),
        run.conversationId?.let { SettingsRow.Info("run-conversation", "Conversation", it) },
        SettingsRow.Info("run-status", "Status", run.status ?: "unknown"),
        run.stopReason?.let { SettingsRow.Info("run-stop", "Stop reason", it) },
        run.createdAt?.let { SettingsRow.Info("run-created", "Started", it) },
        run.completedAt?.let { SettingsRow.Info("run-completed", "Completed", it) },
        RunFormat.duration(run.totalDurationNs)?.let { SettingsRow.Info("run-duration", "Duration", it) },
        RunFormat.duration(run.ttftNs)?.let { SettingsRow.Info("run-ttft", "First token", it) },
        usage?.totalTokens?.let { SettingsRow.Info("run-tokens", "Tokens", it.toString()) },
    )
}

@Composable
private fun RunSteps(detail: RunDetail) {
    SettingsSection(SettingsHeader("run-steps", "Steps · ${detail.steps.size}")) {
        if (detail.steps.isEmpty()) {
            NoteRow(SettingsRow.Note("run-steps-empty", SettingsStatus("This run reported no steps.")))
        }
        detail.steps.forEachIndexed { index, step ->
            InfoRow(SettingsRow.Info("run-step-${step.id}", "Step ${index + 1}", RunFormat.stepLine(step)))
        }
    }
}

@Composable
private fun RunActions(detail: RunDetail, state: RunsPageState, actions: RunsPageActions) {
    val run = detail.run
    val conversationId = run.conversationId
    val open = actions.onOpenConversation?.takeIf { conversationId != null }
    val manage = detail.full && state.canManageRuns
    if (open == null && !manage) {
        LimitedNote()
        return
    }
    SettingsSection(SettingsHeader("run-actions", "Actions")) {
        val buttons = listOfNotNull(
            open?.let { openConversation ->
                ActionButton(RunDetailTags.OPEN_CONVERSATION, "Open conversation", { openConversation(run.agentId, conversationId.orEmpty()) }, primary = true)
            },
            ActionButton(RunDetailTags.CANCEL, "Cancel run", { actions.onCancelRun(run.id) }).takeIf { manage && run.isActive },
        )
        if (buttons.isNotEmpty()) ActionRow("run-buttons", buttons)
        if (manage) {
            LinkRow(
                SettingsRow.Link(
                    key = RunDetailTags.DELETE,
                    title = "Delete run",
                    destructive = true,
                    confirm = SettingsConfirmText("Delete run?", "The run and its steps are removed from the host.", "Delete"),
                    onClick = { actions.onDeleteRun(run.id) },
                ),
            )
        } else {
            NoteRow(SettingsRow.Note(RunDetailTags.LIMITED, SettingsStatus(LIMITED_TEXT)))
        }
    }
}

@Composable
private fun LimitedNote() {
    SettingsSection(SettingsHeader("run-actions", "Actions")) {
        NoteRow(SettingsRow.Note(RunDetailTags.LIMITED, SettingsStatus(LIMITED_TEXT)))
    }
}

private const val DETAIL_TITLE = "Run"
private const val LIMITED_TEXT = "This host lists runs and their steps only: cancel, delete and usage are not available over Iroh."
