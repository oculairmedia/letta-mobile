package com.letta.mobile.ui.shell.pages.schedules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Lucide
import com.letta.mobile.data.schedules.ScheduleDef
import com.letta.mobile.data.schedules.ScheduleProjection
import com.letta.mobile.data.schedules.SchedulesView
import com.letta.mobile.ui.components.LettaListRowSpec
import com.letta.mobile.ui.components.LettaRefreshable
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterListRow
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.components.list.RosterOptions
import com.letta.mobile.ui.components.list.RosterRowSlots
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterSelection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.schedules.HistoryView
import com.letta.mobile.ui.schedules.ScheduleAgendaList
import com.letta.mobile.ui.schedules.ScheduleMonthCalendar
import com.letta.mobile.ui.schedules.ScheduleRailText
import com.letta.mobile.ui.schedules.ScheduleWeekCalendar
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.customColors

/**
 * The current view, or the loading / error / empty state when there is nothing to project. On touch
 * the whole body pulls to refresh; the empty, loading and error states scroll (with nothing to
 * scroll) so the pull reaches it.
 */
@Composable
internal fun SchedulesMainContent(body: SchedulesBody, modifier: Modifier) {
    val library = body.state.library
    val error = library.errorMessage
    LettaRefreshable(refreshing = library.isLoading, onRefresh = body.onRefresh, modifier = modifier) {
        when {
            body.data.defs.isNotEmpty() -> Box(Modifier.fillMaxSize().testTag(SchedulesPageTags.view(body.view))) {
                ActiveScheduleView(body)
            }
            error != null -> SchedulesError(error, library.isLoading, body.onRefresh)
            library.isLoading -> CenteredNote("Loading schedules…")
            else -> SchedulesEmpty(body.state)
        }
    }
}

@Composable
private fun ActiveScheduleView(body: SchedulesBody) {
    when (body.view) {
        SchedulesView.Agenda -> ScheduleAgendaList(body.calendar)
        SchedulesView.Week -> ScheduleWeekCalendar(body.calendar)
        SchedulesView.Month -> ScheduleMonthCalendar(body.calendar)
        SchedulesView.Rules -> SchedulesRulesView(body)
        SchedulesView.History -> HistoryView(body.data.history)
    }
}

/** Every schedule definition: its recurrence and cron, and when it fires next. A row opens its detail. */
@Composable
private fun SchedulesRulesView(body: SchedulesBody) {
    val now = body.data.clock.now
    val sections = remember(body.data.defs) { listOf(RosterSection(SchedulesPageTags.RULES_SECTION, body.data.defs)) }
    RosterList(
        RosterSpec(
            sections = sections,
            key = ScheduleDef::id,
            selection = RosterSelection(
                isSelected = { it.id == body.selectedId },
                onSelect = { body.calendar.actions.onScheduleClick(it.id) },
            ),
            options = RosterOptions(testTag = RosterListTag(SchedulesPageTags.RULES)),
        ),
        modifier = Modifier.fillMaxSize().padding(horizontal = LettaDimens.Space.lg),
    ) { def, row ->
        val cadence = ScheduleRailText.cadence(def, body.data.clock.zone)
        val subtitle = listOfNotNull(cadence, def.cron?.takeIf { it != cadence }).joinToString(" · ")
        RosterListRow(
            spec = LettaListRowSpec(title = def.name, subtitle = subtitle, emphasized = def.active, quiet = !def.active),
            row = row,
            slots = RosterRowSlots(
                end = {
                    Text(
                        if (def.active) {
                            ScheduleRailText.relativeOrDash(now, ScheduleProjection.nextRun(def, now))
                        } else {
                            ScheduleRailText.status(false)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ),
        )
    }
}

@Composable
private fun SchedulesError(message: String, retrying: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LettaDimens.Space.xxl).testTag(SchedulesPageTags.ERROR),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(LettaDimens.Space.sm))
        TextButton(onClick = onRetry, enabled = !retrying) { Text(if (retrying) "Retrying…" else "Retry") }
    }
}

@Composable
private fun SchedulesEmpty(state: SchedulesPageState) {
    val library = state.library
    val hint = when {
        !library.scheduleAdminAvailable && !library.cronMode -> library.emptyMessage
        state.canCreate -> "Use “New schedule” to create one."
        else -> "Schedules created elsewhere will appear here."
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LettaDimens.Space.xl).testTag(SchedulesPageTags.EMPTY),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Lucide.Clock, null, tint = MaterialTheme.customColors.onSurfaceMutedColor, modifier = Modifier.size(LettaDimens.Orb.lg))
        Spacer(Modifier.height(LettaDimens.Space.md))
        Text("No schedules yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.customColors.onSurfaceMutedColor)
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.customColors.onSurfaceMutedColor)
    }
}
