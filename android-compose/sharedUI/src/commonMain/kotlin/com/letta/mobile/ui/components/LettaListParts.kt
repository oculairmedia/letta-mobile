package com.letta.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaDimens

/** Upper-case section label used by sidebars and lists ("PINNED", "CANVASES"). */
@Composable
fun LettaSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = LettaDimens.Space.md, start = LettaDimens.Space.xs, bottom = LettaDimens.Space.hair),
    )
}

/** The quiet one-liner an empty section shows ("No chats"). */
@Composable
fun LettaEmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = LettaDimens.Space.xs, top = LettaDimens.Space.hair),
    )
}

/**
 * What a [LettaListRow] shows: title, optional leading icon, optional trailing label (a time, a
 * count) and an optional second line under the title (a path, a description).
 */
data class LettaListRowSpec(
    val title: String,
    val icon: ImageVector? = null,
    val trailing: String? = null,
    val selected: Boolean = false,
    val subtitle: String? = null,
    /** The title reads semibold (the open conversation). */
    val emphasized: Boolean = false,
    /** The title reads muted (a settled or deleting conversation). */
    val quiet: Boolean = false,
    /** A disabled row draws but takes no click (a conversation being deleted). */
    val enabled: Boolean = true,
)

/** Test hooks for [LettaListRow]. */
object LettaListRowDefaults {
    /** On every row's title block, so a test can tell a row is built on [LettaListRow]. */
    const val TitleTestTag: String = "letta_list_row_title"
}

/** A compact selectable row rendering a [LettaListRowSpec]; every roster row is one (see RosterList). */
@Composable
fun LettaListRow(
    spec: LettaListRowSpec,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn where the icon goes, instead of it: a row that swaps its icon for an action on hover. */
    leading: (@Composable () -> Unit)? = null,
    /** Drawn after the trailing label: a check, a checkbox, a drill-in chevron. */
    end: (@Composable () -> Unit)? = null,
) {
    val (_, icon, trailing, selected) = spec
    val background = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(enabled = spec.enabled, onClick = onClick)
            .padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        if (leading != null) {
            leading()
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(LettaDimens.Control.icon),
            )
        }
        RowTitle(spec, Modifier.weight(1f))
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        end?.invoke()
    }
}

@Composable
private fun RowTitle(spec: LettaListRowSpec, modifier: Modifier) {
    val subtitle = spec.subtitle
    Column(modifier.testTag(LettaListRowDefaults.TitleTestTag)) {
        Text(
            text = spec.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (spec.emphasized) FontWeight.SemiBold else FontWeight.Normal,
            color = if (spec.quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
