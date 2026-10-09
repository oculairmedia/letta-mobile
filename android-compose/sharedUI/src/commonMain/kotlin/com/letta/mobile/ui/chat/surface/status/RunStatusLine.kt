package com.letta.mobile.ui.chat.surface.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.runtime.CommandActivity
import com.letta.mobile.data.runtime.CommandState
import com.letta.mobile.data.runtime.LiveCompaction
import com.letta.mobile.data.runtime.LiveNotice
import com.letta.mobile.data.runtime.LiveRetry
import com.letta.mobile.data.runtime.LoopPhase
import com.letta.mobile.data.runtime.NoticeLevel
import com.letta.mobile.data.runtime.RuntimeLiveStatus
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.run_status_approval
import com.letta.mobile.sharedui.resources.run_status_command
import com.letta.mobile.sharedui.resources.run_status_command_dismiss
import com.letta.mobile.sharedui.resources.run_status_command_failed
import com.letta.mobile.sharedui.resources.run_status_command_running
import com.letta.mobile.sharedui.resources.run_status_command_succeeded
import com.letta.mobile.sharedui.resources.run_status_compacted
import com.letta.mobile.sharedui.resources.run_status_compacted_counts
import com.letta.mobile.sharedui.resources.run_status_compacting
import com.letta.mobile.sharedui.resources.run_status_processing
import com.letta.mobile.sharedui.resources.run_status_retry_in
import com.letta.mobile.sharedui.resources.run_status_retry_now
import com.letta.mobile.sharedui.resources.run_status_retry_provider
import com.letta.mobile.sharedui.resources.run_status_retrying
import com.letta.mobile.sharedui.resources.run_status_sending
import com.letta.mobile.sharedui.resources.run_status_tool
import com.letta.mobile.sharedui.resources.run_status_waiting
import com.letta.mobile.sharedui.resources.run_status_working
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** letta-mobile-bzvro.7/.8: test tags of the live status line. */
object RunStatusTestTags {
    const val LINE = "run-status-line"
    const val PHASE = "run-status-phase"
    const val NOTICE = "run-status-notice"
    const val COMMAND = "run-status-command"
    const val COMMAND_OUTPUT = "run-status-command-output"
    const val COMMAND_DISMISS = "run-status-command-dismiss"
    const val COMPACTION = "run-status-compaction"
}

/**
 * letta-mobile-bzvro.7 (F07) / .8 (F08): one line above the composer saying what the agent is
 * doing right now ("Waiting for the model…", "Retrying (2/5) in 4 s"), the server's latest status
 * message, and a card per server-side command (running, then its output until dismissed).
 *
 * When [companionShowing] (the composer companion already says "Thinking…" with the elapsed time and
 * running tool) the plain phase label is dropped as a duplicate; the retry countdown, notices and
 * command cards, which the companion does not carry, stay.
 *
 * Draws nothing when [status] has nothing to say, so it costs no space between turns.
 */
@Composable
fun RunStatusLine(
    status: RuntimeLiveStatus,
    modifier: Modifier = Modifier,
    companionShowing: Boolean = false,
    now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    var dismissed by remember { mutableStateOf(emptySet<String>()) }
    val commands = status.commands.filter { it.running || it.commandId !in dismissed }
    val phase = phaseLabel(status).takeUnless { companionShowing && it is PhaseLabel.Fixed }
    if (phase == null && commands.isEmpty() && status.notice == null && status.compaction == null) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.xs)
            .testTag(RunStatusTestTags.LINE),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        commands.forEach { command ->
            CommandCard(command, onDismiss = { dismissed = dismissed + command.commandId })
        }
        status.compaction?.let { CompactionRow(it) }
        if (phase != null) PhaseRow(phase, now)
        status.notice?.let { NoticeText(it) }
    }
}

/** What the phase row says, before any retry countdown is filled in; null when nothing runs. */
internal sealed interface PhaseLabel {
    data class Fixed(val text: StringResource) : PhaseLabel

    data class Retry(val retry: LiveRetry) : PhaseLabel
}

internal fun phaseLabel(status: RuntimeLiveStatus): PhaseLabel? {
    status.retry?.let { return PhaseLabel.Retry(it) }
    val text = when (status.phase ?: return null) {
        LoopPhase.SendingRequest -> Res.string.run_status_sending
        LoopPhase.WaitingForResponse -> Res.string.run_status_waiting
        LoopPhase.Retrying -> Res.string.run_status_retrying
        LoopPhase.ProcessingResponse -> Res.string.run_status_processing
        LoopPhase.ExecutingClientTool -> Res.string.run_status_tool
        LoopPhase.ExecutingCommand -> Res.string.run_status_command
        LoopPhase.WaitingOnApproval -> Res.string.run_status_approval
        LoopPhase.WaitingOnInput -> return null
        LoopPhase.Unknown -> Res.string.run_status_working
    }
    return PhaseLabel.Fixed(text)
}

/** Whole seconds until [retry] fires, rounded up, never negative. */
internal fun retrySecondsLeft(retry: LiveRetry, nowMs: Long): Int {
    val remaining = retry.retryAtEpochMs - nowMs
    if (remaining <= 0L) return 0
    return ((remaining + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
}

@Composable
private fun PhaseRow(phase: PhaseLabel, now: () -> Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(SPINNER_SIZE), strokeWidth = SPINNER_STROKE)
        Text(
            text = phaseText(phase, now),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(RunStatusTestTags.PHASE),
        )
    }
}

@Composable
private fun phaseText(phase: PhaseLabel, now: () -> Long): String = when (phase) {
    is PhaseLabel.Fixed -> stringResource(phase.text)
    is PhaseLabel.Retry -> retryText(phase.retry, now)
}

@Composable
private fun retryText(retry: LiveRetry, now: () -> Long): String {
    val seconds by produceState(retrySecondsLeft(retry, now()), retry) {
        while (value > 0) {
            delay(COUNTDOWN_TICK_MS)
            value = retrySecondsLeft(retry, now())
        }
    }
    val counted = if (seconds > 0) {
        stringResource(Res.string.run_status_retry_in, retry.attempt, retry.maxAttempts, seconds)
    } else {
        stringResource(Res.string.run_status_retry_now, retry.attempt, retry.maxAttempts)
    }
    val provider = retry.provider?.takeIf { it.isNotBlank() } ?: return counted
    return counted + " " + stringResource(Res.string.run_status_retry_provider, provider)
}

/** letta-mobile-kr39h: "Compacting the conversation…", then what it came to. */
@Composable
private fun CompactionRow(compaction: LiveCompaction) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        modifier = Modifier.testTag(RunStatusTestTags.COMPACTION),
    ) {
        if (compaction.running) CircularProgressIndicator(Modifier.size(SPINNER_SIZE), strokeWidth = SPINNER_STROKE)
        Text(
            text = compactionText(compaction),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun compactionText(compaction: LiveCompaction): String {
    if (compaction.running) return stringResource(Res.string.run_status_compacting)
    val counts = compaction.messageCounts ?: return stringResource(Res.string.run_status_compacted)
    return stringResource(Res.string.run_status_compacted_counts, counts.first, counts.second)
}

@Composable
private fun NoticeText(notice: LiveNotice) {
    val color = when (notice.level) {
        NoticeLevel.Warning -> MaterialTheme.colorScheme.error
        NoticeLevel.Success -> MaterialTheme.colorScheme.primary
        NoticeLevel.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = notice.message,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag(RunStatusTestTags.NOTICE),
    )
}

@Composable
private fun CommandCard(command: CommandActivity, onDismiss: () -> Unit) {
    val failed = command.state == CommandState.Failed
    Surface(
        shape = RoundedCornerShape(LettaDimens.Space.sm),
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag(RunStatusTestTags.COMMAND),
    ) {
        Column(Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
                if (command.running) CircularProgressIndicator(Modifier.size(SPINNER_SIZE), strokeWidth = SPINNER_STROKE)
                Text(
                    text = stringResource(command.headline(), command.input.ifBlank { "command" }),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!command.running) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag(RunStatusTestTags.COMMAND_DISMISS)) {
                        Text(stringResource(Res.string.run_status_command_dismiss))
                    }
                }
            }
            command.output?.takeIf { it.isNotBlank() }?.let { CommandOutput(it, command) }
        }
    }
}

@Composable
private fun CommandOutput(output: String, command: CommandActivity) {
    SelectionContainer {
        Text(
            text = output,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (command.preformatted) FontFamily.Monospace else null,
            maxLines = OUTPUT_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .alpha(if (command.dimOutput) DIM_ALPHA else 1f)
                .testTag(RunStatusTestTags.COMMAND_OUTPUT),
        )
    }
}

private fun CommandActivity.headline(): StringResource = when (state) {
    CommandState.Running -> Res.string.run_status_command_running
    CommandState.Succeeded -> Res.string.run_status_command_succeeded
    CommandState.Failed -> Res.string.run_status_command_failed
}

private const val MILLIS_PER_SECOND = 1_000L
private const val COUNTDOWN_TICK_MS = 250L
private const val OUTPUT_MAX_LINES = 12
private const val DIM_ALPHA = 0.6f
private val SPINNER_SIZE = 12.dp
private val SPINNER_STROKE = 1.5.dp
