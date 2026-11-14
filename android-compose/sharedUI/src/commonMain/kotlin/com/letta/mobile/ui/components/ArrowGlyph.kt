package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.components.line.ArrowDirection
import com.letta.mobile.ui.components.line.PagerStep
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The one arrow a control points with (down to the newest message, back and on through pages), in
 * the content colour. A disclosure is never drawn with it: that is [DisclosureChevron]. [size] is the
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
    ArrowDirection.Back -> LettaIcons.ChevronLeft
    ArrowDirection.Forward -> LettaIcons.ChevronRight
}

/**
 * The one pager: back, where it is ("Approval 2 of 3", "Page 4"), on. Each step is an icon button
 * with the shared [ArrowGlyph]; a step that cannot go is disabled, not hidden, so the label never moves.
 */
@Composable
internal fun PagerBar(label: String, back: PagerStep, forward: PagerStep, modifier: Modifier = Modifier, labelTag: String = "") {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        PagerButton(back, ArrowDirection.Back)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = tagged(labelTag),
        )
        PagerButton(forward, ArrowDirection.Forward)
    }
}

@Composable
private fun PagerButton(step: PagerStep, direction: ArrowDirection) {
    IconButton(onClick = step.onClick, enabled = step.enabled, modifier = tagged(step.tag)) {
        ArrowGlyph(direction, step.contentDescription)
    }
}

private fun tagged(tag: String): Modifier = if (tag.isEmpty()) Modifier else Modifier.testTag(tag)
