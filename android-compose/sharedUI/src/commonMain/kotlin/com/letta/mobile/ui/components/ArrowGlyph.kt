package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.components.line.ArrowDirection
import com.letta.mobile.ui.components.line.PagerLabel
import com.letta.mobile.ui.components.line.PagerStep
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.components.foundation.LettaNextButton
import com.letta.mobile.ui.components.foundation.LettaPreviousButton

/**
 * The one arrow a control points with (down to the newest message), in the content colour; paging
 * steps are the shared pager buttons. A disclosure is never drawn with it: that is DisclosureChevron. [size] is the
 * icon's own when null.
 */
@Composable
internal fun ArrowGlyph(direction: ArrowDirection, contentDescription: String?, modifier: Modifier = Modifier, size: Dp? = null) {
    Icon(
        imageVector = direction.icon(),
        contentDescription = contentDescription,
        modifier = if (size != null) modifier.size(size) else modifier,
    )
}

private fun ArrowDirection.icon(): ImageVector = when (this) {
    ArrowDirection.Down -> LettaIcons.KeyboardArrowDown
}

/**
 * The one pager bar: back, where it is ("Approval 2 of 3", "Page 4"), on. The steps are the shared
 * LettaPreviousButton and LettaNextButton; a step that cannot go is disabled, not hidden, so the label
 * never moves.
 */
@Composable
internal fun PagerBar(label: PagerLabel, back: PagerStep, forward: PagerStep, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        LettaPreviousButton(back.onClick, back.contentDescription, tagged(back.tag), back.enabled)
        Text(
            text = label.text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = tagged(label.tag),
        )
        LettaNextButton(forward.onClick, forward.contentDescription, tagged(forward.tag), forward.enabled)
    }
}

private fun tagged(tag: String): Modifier = if (tag.isEmpty()) Modifier else Modifier.testTag(tag)
