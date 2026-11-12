package com.letta.mobile.ui.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.schedules.ScheduleFormat
import com.letta.mobile.data.schedules.leases.Lease
import com.letta.mobile.data.schedules.leases.LeaseState
import com.letta.mobile.ui.components.row.LettaListRow
import com.letta.mobile.ui.components.row.LettaListRowSpec
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.datetime.TimeZone

internal val LeaseState.badgeLabel: String get() = when (this) {
    LeaseState.ACTIVE -> "Active"
    LeaseState.DONE -> "Done"
    LeaseState.EXPIRED -> "Expired"
    LeaseState.RECOVERED -> "Recovered"
    LeaseState.UNKNOWN -> "Unknown"
}

@Composable
internal fun leaseStateAccent(state: LeaseState) = when (state) {
    LeaseState.ACTIVE -> MaterialTheme.colorScheme.primary
    LeaseState.DONE -> MaterialTheme.colorScheme.secondary
    LeaseState.EXPIRED -> MaterialTheme.colorScheme.error
    LeaseState.RECOVERED -> MaterialTheme.colorScheme.tertiary
    LeaseState.UNKNOWN -> MaterialTheme.colorScheme.outline
}

@Composable
internal fun LeaseCalendarBlock(lease: Lease, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = when (lease.state) {
        LeaseState.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
        LeaseState.DONE -> MaterialTheme.colorScheme.secondaryContainer
        LeaseState.EXPIRED -> MaterialTheme.colorScheme.errorContainer
        LeaseState.RECOVERED -> MaterialTheme.colorScheme.tertiaryContainer
        LeaseState.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (lease.state) {
        LeaseState.ACTIVE -> MaterialTheme.colorScheme.onPrimaryContainer
        LeaseState.DONE -> MaterialTheme.colorScheme.onSecondaryContainer
        LeaseState.EXPIRED -> MaterialTheme.colorScheme.onErrorContainer
        LeaseState.RECOVERED -> MaterialTheme.colorScheme.onTertiaryContainer
        LeaseState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier.testTag("lease-${lease.state.badgeLabel.lowercase()}-${lease.id}")
            .background(color, MaterialTheme.shapes.small).clickable(onClick = onClick).padding(LettaDimens.Space.xs),
    ) {
        Text(
            lease.label,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            lease.state.badgeLabel,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
internal fun LeaseAgendaRow(lease: Lease, zone: TimeZone, onClick: () -> Unit) {
    LettaListRow(
        spec = LettaListRowSpec(
            title = lease.label,
            subtitle = "${lease.owner} · due ${ScheduleFormat.timeOfDay(lease.dueAt, zone)}",
            trailing = lease.state.badgeLabel,
        ),
        onClick = onClick,
        modifier = Modifier.testTag("lease-row-${lease.state.badgeLabel.lowercase()}-${lease.id}"),
    )
}
