package com.letta.mobile.ui.dreaming

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.dreaming.DreamDetailState
import com.letta.mobile.data.dreaming.DreamHistoryState
import com.letta.mobile.data.dreaming.DreamRun
import com.letta.mobile.data.dreaming.DreamRunNowState
import com.letta.mobile.data.dreaming.DreamTarget
import com.letta.mobile.data.dreaming.DreamingActions
import com.letta.mobile.data.dreaming.DreamingController
import com.letta.mobile.data.dreaming.DreamingState
import com.letta.mobile.data.dreaming.DreamTrigger
import com.letta.mobile.data.home.relativeAgeLabel
import com.letta.mobile.ui.shell.pages.memfs.MemfsFileDiffView
import com.letta.mobile.ui.theme.LettaDimens

object DreamingTags {
    const val PANEL = "dreaming_panel"
    const val UNSUPPORTED = "dreaming_unsupported"
    const val STATUS = "dreaming_status"
    const val PROGRESS = "dreaming_progress"
    const val AUTOMATIC = "dreaming_automatic"
    const val REVIEW = "dreaming_review"
    const val INSTRUCTIONS = "dreaming_review_instructions"
    const val STEPS = "dreaming_steps"
    const val STEPS_UP = "dreaming_steps_up"
    const val STEPS_DOWN = "dreaming_steps_down"
    const val RUN_NOW = "dreaming_run_now"
    const val RUN_RESULT = "dreaming_run_result"
    const val HISTORY = "dreaming_history"
    const val DETAIL = "dreaming_detail"
    const val WINDOWS_ALLOW = "dreaming_windows_allow"
    const val NOTIFY = "dreaming_notify"

    fun trigger(trigger: DreamTrigger) = "dreaming_trigger_${trigger.wire}"

    fun run(sha: String) = "dreaming_run_$sha"
}

/**
 * What only the platform knows about dreaming on this host. [windowsAutoBlocked]: the desktop's
 * bundled letta-code runs on Windows, where it starts no automatic dream unless the runtime was
 * launched with automatic reflection allowed; [onAllowWindowsAuto] lets the user allow it.
 * [notifyOnFinish]: the per-agent "tell me when a dream finishes" opt-in, null where the platform
 * delivers no such notification.
 */
@Immutable
data class DreamingHostNotes(
    val windowsAutoBlocked: Boolean = false,
    val windowsAutoAllowed: Boolean = false,
    val onAllowWindowsAuto: (() -> Unit)? = null,
    val notifyOnFinish: Boolean? = null,
    val onNotifyChange: ((Boolean) -> Unit)? = null,
)

/**
 * The one Dreaming surface on phone and desktop for the agent [target] names:
 * the platform passes the session's [controller] and its [notes].
 */
@Composable
fun DreamingPanelHost(controller: DreamingController, target: DreamTarget, modifier: Modifier = Modifier, notes: DreamingHostNotes = DreamingHostNotes()) {
    LaunchedEffect(controller, target) { controller.focus(target) }
    val state by controller.state.collectAsState()
    DreamingPanel(state = state, actions = controller, modifier = modifier, notes = notes)
}

/**
 * The Dreaming surface: what is happening now and when the next dream comes, the settings, a
 * Dream now button, and the dreams that changed memory, each opening its diff. Rows that change
 * text keep their height, so a run starting or ending never moves the page.
 */
@Composable
fun DreamingPanel(state: DreamingState, actions: DreamingActions, modifier: Modifier = Modifier, notes: DreamingHostNotes = DreamingHostNotes()) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LettaDimens.Space.lg).testTag(DreamingTags.PANEL),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        val unsupported = state.unsupported
        val detail = state.detail
        when {
            unsupported != null -> Text(
                DreamingStrings.unsupported(unsupported),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(DreamingTags.UNSUPPORTED),
            )
            detail != null -> DreamDetail(detail, actions::closeRun)
            else -> DreamingOverview(state, actions, notes)
        }
    }
}

@Composable
private fun DreamingOverview(state: DreamingState, actions: DreamingActions, notes: DreamingHostNotes) {
    Hint(DreamingStrings.INTRO)
    DreamStatus(state, notes)
    HorizontalDivider()
    DreamingSettingsSection(state.settings, actions::save)
    val notify = notes.notifyOnFinish
    val onNotify = notes.onNotifyChange
    if (notify != null && onNotify != null) SwitchRow(DreamingStrings.NOTIFY, notify, DreamingTags.NOTIFY, onNotify)
    HorizontalDivider()
    RunNow(state.runNow, state.runNow.unsupported?.let(DreamingStrings::unsupported), actions::runNow)
    HorizontalDivider()
    DreamHistory(state.history, actions::openRun)
}

@Composable
private fun DreamStatus(state: DreamingState, notes: DreamingHostNotes) {
    val live = state.live.firstOrNull()
    Column(Modifier.fillMaxWidth().testTag(DreamingTags.STATUS), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        Text(live?.let(DreamingStrings::live) ?: DreamingStrings.NOW_IDLE, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // The bar's slot is always there: a dream starting or ending changes only what it draws.
        Box(Modifier.fillMaxWidth().height(LettaDimens.Space.xs)) {
            if (live != null) LinearProgressIndicator(Modifier.fillMaxWidth().testTag(DreamingTags.PROGRESS))
        }
        state.lastRun?.let { Hint(DreamingStrings.lastDream(it.summary, ageText(it.timestamp))) }
        state.settings.settings?.let { Hint(DreamingStrings.triggerSentence(it.trigger, it.stepCount)) }
        if (notes.windowsAutoBlocked && state.settings.settings?.automatic == true) WindowsNotice(notes)
    }
}

@Composable
private fun WindowsNotice(notes: DreamingHostNotes) {
    if (notes.windowsAutoAllowed) {
        Hint(DreamingStrings.WINDOWS_ALLOWED)
        return
    }
    Text(DreamingStrings.WINDOWS_BLOCKED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    notes.onAllowWindowsAuto?.let { allow ->
        TextButton(onClick = allow, modifier = Modifier.testTag(DreamingTags.WINDOWS_ALLOW)) { Text(DreamingStrings.WINDOWS_ALLOW) }
    }
}

@Composable
private fun RunNow(state: DreamRunNowState, unsupported: String?, onRun: (String?) -> Unit) {
    var focus by remember { mutableStateOf("") }
    if (unsupported != null) {
        Hint(unsupported)
        return
    }
    OutlinedTextField(value = focus, onValueChange = { focus = it }, label = { Text(DreamingStrings.FOCUS) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
        Button(onClick = { onRun(focus.trim().ifEmpty { null }) }, enabled = !state.starting, modifier = Modifier.testTag(DreamingTags.RUN_NOW)) {
            Text(if (state.starting) DreamingStrings.STARTING else DreamingStrings.RUN_NOW)
        }
    }
    val result = state.error?.let(DreamingStrings::failure) ?: state.lastStart?.let { DreamingStrings.start(it.status, it.output) }
    // One line of room whatever the answer, so the history below never shifts when it arrives.
    Text(
        result.orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        minLines = 2,
        modifier = Modifier.fillMaxWidth().testTag(DreamingTags.RUN_RESULT),
    )
}

@Composable
private fun DreamHistory(history: DreamHistoryState, onOpen: (DreamRun) -> Unit) {
    Text(DreamingStrings.HISTORY, style = MaterialTheme.typography.titleSmall)
    Column(Modifier.fillMaxWidth().testTag(DreamingTags.HISTORY)) {
        when (history) {
            DreamHistoryState.Idle -> Hint(DreamingStrings.HISTORY_UNAVAILABLE)
            DreamHistoryState.Loading -> Hint(DreamingStrings.HISTORY_LOADING)
            is DreamHistoryState.Failed -> Hint(history.message)
            is DreamHistoryState.Loaded ->
                if (history.runs.isEmpty()) Hint(DreamingStrings.HISTORY_EMPTY) else history.runs.forEach { DreamRow(it, onOpen) }
        }
    }
}

@Composable
private fun DreamRow(run: DreamRun, onOpen: (DreamRun) -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = LettaDimens.Control.iconButtonLg).clickable { onOpen(run) }
            .padding(vertical = LettaDimens.Space.sm).testTag(DreamingTags.run(run.sha)),
    ) {
        Text(run.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Hint(listOf(DreamingStrings.kind(run.kind), ageText(run.timestamp), run.shortSha).joinToString(" · "))
    }
}

@Composable
private fun DreamDetail(detail: DreamDetailState, onBack: () -> Unit) {
    TextButton(onClick = onBack) { Text(DreamingStrings.BACK) }
    Column(Modifier.fillMaxWidth().testTag(DreamingTags.DETAIL), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
        Text(detail.run.summary, style = MaterialTheme.typography.titleSmall)
        Hint(listOf(DreamingStrings.kind(detail.run.kind), ageText(detail.run.timestamp), detail.run.shortSha).joinToString(" · "))
        when (detail) {
            is DreamDetailState.Loading -> Hint(DreamingStrings.DETAIL_LOADING)
            is DreamDetailState.Failed -> Hint(detail.message)
            is DreamDetailState.Loaded -> {
                Hint(DreamingStrings.stats(detail.detail.added, detail.detail.removed))
                if (detail.detail.files.isEmpty()) Hint(DreamingStrings.NO_FILES)
                detail.detail.files.forEach { MemfsFileDiffView(it) }
            }
        }
    }
}

/** "5m ago", "just now", or the raw stamp when it is not an instant. */
private fun ageText(timestamp: String): String {
    val age = relativeAgeLabel(timestamp)
    return when {
        age == timestamp -> timestamp
        age == NOW -> "just now"
        else -> "$age ago"
    }
}

private const val NOW = "now"
