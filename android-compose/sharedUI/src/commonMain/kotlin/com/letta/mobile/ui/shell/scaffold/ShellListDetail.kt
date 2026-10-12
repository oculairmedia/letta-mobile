package com.letta.mobile.ui.shell.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags of [ShellListDetail]. */
object ShellListDetailTags {
    const val LIST = "shell-list-detail-list"
    const val DETAIL = "shell-list-detail-detail"
}

/** Whether a page of [width] lays a list and its detail side by side: the shell's one wide breakpoint. */
fun isWideShellWidth(width: Dp): Boolean = width >= LettaDimens.Pane.wideBreakpoint

/**
 * letta-mobile-ckmub.2.4 (hard cut S16): the shell's wide layout for a list and the page it opens.
 * At or above [LettaDimens.Pane.wideBreakpoint] the [list] docks at [LettaDimens.Pane.sidePanelWidth]
 * as a sidebar and [detail] fills the rest (the desktop's sidebar + page); below it only the list
 * shows and the host navigates to the opened page instead. [list] learns which it is.
 *
 * The list sits at the same place in the composition at every width, so its saved state (scroll,
 * filter) survives a rotation across the breakpoint (letta-mobile-v7aln).
 */
@Composable
fun ShellListDetail(
    modifier: Modifier = Modifier,
    list: @Composable (wide: Boolean) -> Unit,
    detail: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = isWideShellWidth(maxWidth)
        Row(Modifier.fillMaxSize()) {
            Box(
                (if (wide) Modifier.width(LettaDimens.Pane.sidePanelWidth) else Modifier.weight(1f))
                    .fillMaxHeight()
                    .testTag(ShellListDetailTags.LIST),
            ) { list(wide) }
            if (wide) {
                VerticalDivider(thickness = LettaDimens.Stroke.hairline)
                Box(Modifier.weight(1f).fillMaxHeight().testTag(ShellListDetailTags.DETAIL)) { detail() }
            }
        }
    }
}
