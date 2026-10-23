package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_plan_mode_entered
import com.letta.mobile.sharedui.resources.rows_plan_mode_left
import com.letta.mobile.ui.theme.ChatRowSpacing
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * Entering or leaving plan mode, as a thin divider across the timeline: a hairline either side of
 * a muted "Entered plan mode" / "Left plan mode". It is a step's line, so it keeps the step's height
 * whatever its state, and leaving it opens (by [disclosure]) to the plan it proposed.
 */
@Composable
internal fun PlanModeDivider(leaving: Boolean, disclosure: RunStepDisclosure?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChatRowSpacing.toolStepMinHeight)
            .testTag(ChatRowTestTags.PLAN_MODE_DIVIDER)
            .then(if (disclosure != null) Modifier.stepToggle(disclosure) else Modifier.semantics { disabled() })
            .padding(horizontal = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = stringResource(if (leaving) Res.string.rows_plan_mode_left else Res.string.rows_plan_mode_entered),
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            maxLines = 1,
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
