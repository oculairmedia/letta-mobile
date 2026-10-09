package com.letta.mobile.ui.screens.runs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import ca.oculair.meridian.R
import com.letta.mobile.data.model.Run
import com.letta.mobile.ui.components.StatusChip
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.theme.LettaCodeFont
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.listItemMetadata
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.util.formatRelativeTime
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

@Composable
internal fun RunCard(
    run: Run,
    onInspect: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val active = isActiveRunStatus(run.status)
    val containerColor = runCardContainerColor(run.status)

    val startEpochMs = remember(run.createdAt) { parseInstantMillis(run.createdAt) }
    val frozenDuration = remember(run.totalDurationNs, run.completedAt, startEpochMs) {
        val totalNs = run.totalDurationNs
        val completedAt = run.completedAt
        when {
            totalNs != null -> formatElapsedDuration(totalNs / 1_000_000L)
            startEpochMs != null && completedAt != null -> {
                val end = parseInstantMillis(completedAt)
                if (end != null) formatElapsedDuration(end - startEpochMs) else "--:--"
            }
            else -> "--:--"
        }
    }
    var liveDuration by remember(run.id, run.status) { mutableStateOf(frozenDuration) }
    if (active && startEpochMs != null) {
        LaunchedEffect(run.id, startEpochMs) {
            while (true) {
                liveDuration = formatElapsedDuration(System.currentTimeMillis() - startEpochMs)
                delay(1.seconds)
            }
        }
    }
    val durationText = if (active && startEpochMs != null) liveDuration else frozenDuration

    Card(
        onClick = onInspect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(LettaDimens.Space.lg),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            // Row 1 — primary: status chip + live/frozen duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    run.status?.let { status -> StatusChip(status = status) }
                    if (run.background == true) {
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.screen_runs_background_chip)) },
                        )
                    }
                }
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = LettaCodeFont,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }

            // Row 2 — supporting: agent id, optional conversation id
            Text(
                text = stringResource(R.string.screen_runs_agent_label, run.agentId),
                style = MaterialTheme.typography.listItemSupporting,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            run.conversationId?.let { conversationId ->
                Text(
                    text = stringResource(R.string.screen_runs_conversation_label, conversationId),
                    style = MaterialTheme.typography.listItemSupporting,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = LettaDimens.Alpha.hairline),
            )

            // Row 3 — metadata: timestamp + low-contrast truncated UUID pill (click-to-copy)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = run.createdAt
                        ?.let { stringResource(R.string.screen_runs_created_label, formatRelativeTime(it)) }
                        .orEmpty(),
                    style = MaterialTheme.typography.listItemMetadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = truncateRunId(run.id),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = LettaCodeFont,
                        fontSize = LettaDimens.Type.caption,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = LettaDimens.Alpha.disabled),
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(LettaDimens.Radius.sm))
                        .clickable {
                            clipboard.setText(AnnotatedString(run.id))
                            HapticEffects.longPress(haptic, view)
                        }
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                        .padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.hair),
                )
            }
        }
    }
}
