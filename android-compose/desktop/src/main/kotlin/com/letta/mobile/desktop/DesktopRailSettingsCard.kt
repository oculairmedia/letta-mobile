package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Settings card for the agent rail: how far back an agent must have been used to stay on it. */
@Composable
internal fun DesktopRailSettingsCard(
    recencyDays: Int,
    onRecencyDaysChange: (Int) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Agent rail", style = MaterialTheme.typography.titleLarge)
            Text(
                "Show agents used in the last…  Everything else stays one click away in the picker.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RAIL_RECENCY_CHOICES.forEach { (days, label) ->
                    DesktopRadioChip(selected = recencyDays == days, onClick = { onRecencyDaysChange(days) }) {
                        DesktopControlText(label)
                    }
                }
            }
        }
    }
}

private val RAIL_RECENCY_CHOICES: List<Pair<Int, String>> = listOf(
    1 to "Day",
    3 to "3 days",
    7 to "Week",
    14 to "2 weeks",
    30 to "Month",
    0 to "All agents",
)
