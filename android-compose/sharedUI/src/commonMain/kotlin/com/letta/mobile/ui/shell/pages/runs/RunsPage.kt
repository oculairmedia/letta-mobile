package com.letta.mobile.ui.shell.pages.runs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.model.Run
import com.letta.mobile.data.runs.RunFormat
import com.letta.mobile.data.runs.RunMonitorController
import com.letta.mobile.data.runs.RunMonitorState
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.components.LettaEmptyHint
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.LettaRefreshAction
import com.letta.mobile.ui.components.LettaRefreshable
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterListRow
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.components.list.RosterOptions
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterSelection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageSlots
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags of the Runs page. */
object RunsPageTags {
    const val PAGE = "runs-page"
    const val SEARCH = "runs-page-search"
    const val REFRESH = "runs-page-refresh"
    const val ALL = "runs-page-all"
    const val ACTIVE_ONLY = "runs-page-active-only"
    const val SUMMARY = "runs-page-summary"
    const val ERROR = "runs-page-error"
    const val RETRY = "runs-page-retry"
    const val OPERATION_ERROR = "runs-page-operation-error"
    const val DETAIL = "runs-page-detail"
    val LIST = RosterListTag("runs-page-list")
}

/** What the Runs page shows: the monitor's state and the agents' names for its rows. */
@Immutable
data class RunsPageState(
    val monitor: RunMonitorState = RunMonitorState(),
    val agentNames: Map<String, String> = emptyMap(),
    /** Cancel and delete exist on this host (not over Iroh). */
    val canManageRuns: Boolean = false,
) {
    internal fun agentName(agentId: String): String = agentNames[agentId] ?: agentId
}

/** What the page reports back. A null [onOpenConversation] hides the detail's "Open conversation". */
@Immutable
data class RunsPageActions(
    val onRefresh: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onActiveOnlyChange: (Boolean) -> Unit = {},
    val onOpenRun: (String) -> Unit = {},
    val onCloseRun: () -> Unit = {},
    val onCancelRun: (String) -> Unit = {},
    val onDeleteRun: (String) -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onOpenConversation: ((agentId: String, conversationId: String) -> Unit)? = null,
)

/**
 * The page's actions over the shared [RunMonitorController]: every host binds
 * the monitor the same way and only says where a run's conversation opens.
 */
fun RunMonitorController.runsPageActions(
    onOpenConversation: ((agentId: String, conversationId: String) -> Unit)?,
) = RunsPageActions(
    onRefresh = ::refresh,
    onQueryChange = ::setQuery,
    onActiveOnlyChange = ::setActiveOnly,
    onOpenRun = ::open,
    onCloseRun = ::closeDetail,
    onCancelRun = ::cancel,
    onDeleteRun = ::delete,
    onDismissError = ::clearOperationError,
    onOpenConversation = onOpenConversation,
)

/**
 *  (hard cut S17): the run monitor shared by every host. A search and the
 * All / Active filter over one [RosterList] of runs (pulled down on touch to reload, a refresh
 * button on a pointer); a run opens its detail in an inspector. [modifier] carries the host frame's
 * insets; [slots] are the host's search field and refresh control.
 */
@Composable
fun RunsPage(
    state: RunsPageState,
    actions: RunsPageActions,
    modifier: Modifier = Modifier,
    slots: ChannelsPageSlots = ChannelsPageSlots(),
) {
    val monitor = state.monitor
    Column(modifier.fillMaxSize().testTag(RunsPageTags.PAGE).padding(horizontal = LettaDimens.Space.md)) {
        RunsHeader(monitor, actions, slots)
        monitor.operationError?.let { RunsOperationError(it, actions.onDismissError) }
        val error = monitor.error
        if (error != null && monitor.runs.isEmpty()) {
            RunsLoadError(error, actions.onRefresh)
        } else {
            RunsRoster(state, actions)
        }
    }
    if (monitor.detail != null || monitor.detailLoading) RunDetailPane(state, actions)
}

@Composable
private fun RunsHeader(monitor: RunMonitorState, actions: RunsPageActions, slots: ChannelsPageSlots) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = LettaDimens.Space.sm),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        slots.searchField.Content(
            query = monitor.query,
            onQueryChange = actions.onQueryChange,
            placeholder = SEARCH_PLACEHOLDER,
            modifier = Modifier.weight(1f).testTag(RunsPageTags.SEARCH),
        )
        LettaRefreshAction(monitor.refreshing, actions.onRefresh, Modifier.testTag(RunsPageTags.REFRESH)) { onRefresh, modifier ->
            slots.refreshAction.Content(onRefresh, modifier)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = LettaDimens.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LettaChipTab(ALL, !monitor.activeOnly, { actions.onActiveOnlyChange(false) }, Modifier.testTag(RunsPageTags.ALL))
        LettaChipTab(ACTIVE, monitor.activeOnly, { actions.onActiveOnlyChange(true) }, Modifier.testTag(RunsPageTags.ACTIVE_ONLY))
        Spacer(Modifier.weight(1f))
        val summary = monitor.summary
        Text(
            "${summary.total} runs · ${summary.active} active · ${summary.failed} failed",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(RunsPageTags.SUMMARY),
        )
    }
}

@Composable
private fun ColumnScope.RunsRoster(state: RunsPageState, actions: RunsPageActions) {
    val monitor = state.monitor
    val runs = monitor.visibleRuns
    LettaRefreshable(monitor.refreshing, actions.onRefresh, Modifier.fillMaxWidth().weight(1f)) {
        RosterList(
            RosterSpec(
                sections = listOf(RosterSection(key = "runs", items = runs)),
                key = Run::id,
                selection = RosterSelection(isSelected = { it.id == monitor.detail?.run?.id }, onSelect = { actions.onOpenRun(it.id) }),
                options = RosterOptions(loading = monitor.loading && monitor.runs.isEmpty(), testTag = RunsPageTags.LIST),
                empty = { LettaEmptyHint(emptyHint(monitor), Modifier.padding(LettaDimens.Space.md)) },
            ),
            modifier = Modifier.fillMaxSize(),
        ) { run, row ->
            RosterListRow(
                LettaListRowSpec(
                    title = state.agentName(run.agentId),
                    subtitle = RunFormat.summaryLine(run),
                    trailing = RunFormat.shortId(run.id),
                    emphasized = monitor.detail?.run?.id == run.id,
                ),
                row,
            )
        }
    }
}

private fun emptyHint(monitor: RunMonitorState): String = when {
    monitor.query.isNotBlank() -> "No runs match \"${monitor.query.trim()}\""
    monitor.activeOnly -> "No active runs"
    else -> "No runs yet"
}

@Composable
private fun RunsLoadError(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(LettaDimens.Space.lg).testTag(RunsPageTags.ERROR),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry, modifier = Modifier.testTag(RunsPageTags.RETRY)) { Text(RETRY) }
    }
}

@Composable
private fun RunsOperationError(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag(RunsPageTags.OPERATION_ERROR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text(DISMISS) }
    }
}

private const val SEARCH_PLACEHOLDER = "Search runs"
private const val ALL = "All"
private const val ACTIVE = "Active"
private const val RETRY = "Retry"
private const val DISMISS = "Dismiss"
