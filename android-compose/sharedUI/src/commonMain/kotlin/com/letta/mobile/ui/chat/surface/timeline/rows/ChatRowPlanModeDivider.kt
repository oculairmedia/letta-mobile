package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.runtime.Composable
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_plan_mode_entered
import com.letta.mobile.sharedui.resources.rows_plan_mode_left
import com.letta.mobile.ui.components.DividerLine
import com.letta.mobile.ui.components.line.LineDisclosure
import com.letta.mobile.ui.components.line.LineRail
import org.jetbrains.compose.resources.stringResource

/**
 * Entering or leaving plan mode, as a thin divider across the timeline: a hairline either side of
 * a muted "Entered plan mode" / "Left plan mode". It is a step of the run, so it is the shared
 * [DividerLine] on the step rail (a step's height whatever its state), and leaving it opens (by
 * [disclosure]) to the plan it proposed.
 */
@Composable
internal fun PlanModeDivider(leaving: Boolean, disclosure: LineDisclosure?) {
    DividerLine(
        label = stringResource(if (leaving) Res.string.rows_plan_mode_left else Res.string.rows_plan_mode_entered),
        tag = ChatRowTestTags.PLAN_MODE_DIVIDER,
        rail = LineRail.Step,
        disclosure = disclosure,
    )
}
