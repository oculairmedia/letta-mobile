package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_fork_from_here
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-7mys7: the hover "Fork from here" beside a reply's Copy. Hidden, it keeps its place
 * (no layout jump) and takes no clicks, as [CopyIconButton] does.
 */
@Composable
internal fun ForkIconButton(visible: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = visible,
        modifier = Modifier
            .size(LettaDimens.Control.actionButton)
            .alpha(if (visible) 1f else 0f)
            .clip(CircleShape)
            .testTag(ChatRowTestTags.FORK_REPLY),
    ) {
        Icon(
            LettaIcons.ForkRight,
            contentDescription = stringResource(Res.string.rows_fork_from_here),
            modifier = Modifier.size(LettaDimens.Control.icon),
        )
    }
}
