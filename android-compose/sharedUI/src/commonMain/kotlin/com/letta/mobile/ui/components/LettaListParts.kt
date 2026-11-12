package com.letta.mobile.ui.components

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.letta.mobile.ui.host.LocalLettaHost
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
import com.letta.mobile.ui.theme.LettaDimens

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
    /** Archived rows mute their complete contents without changing click availability. */
    val muted: Boolean = false,
    /** Overrides the title shown by the host tooltip. */
    val tooltip: String = title,
    /** Optional semantic role, for rows presented as buttons. */
    val role: Role? = null,
    /** A single accessible announcement instead of separately reading the text children. */
    val description: String? = null,
    /** Maximum title lines; additional text is ellipsized. */
    val titleLines: Int = 1,
    /** Maximum subtitle lines; additional text is ellipsized. */
    val subtitleLines: Int = 1,
)

/** Test hooks for [LettaListRow]. */
object LettaListRowDefaults {
    /** On every row's title block, so a test can tell a row is built on [LettaListRow]. */
    const val TitleTestTag: String = "letta_list_row_title"
}

internal class LettaListRowInteraction(
    val source: MutableInteractionSource,
    val hovered: State<Boolean>,
    val focused: State<Boolean>,
)

internal val LocalLettaListRowInteraction = compositionLocalOf<LettaListRowInteraction?> { null }

@Composable
internal fun rememberLettaListRowInteraction(): LettaListRowInteraction {
    val source = remember { MutableInteractionSource() }
    val hovered = source.collectIsHoveredAsState()
    val focused = source.collectIsFocusedAsState()
    return remember(source) { LettaListRowInteraction(source, hovered, focused) }
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
    val selected = spec.selected
    val inherited = LocalLettaListRowInteraction.current
    val interaction = inherited ?: rememberLettaListRowInteraction()
    val hovered by interaction.hovered
    val focused by interaction.focused
    val active = spec.enabled && (hovered || focused)
    val background = when {
        selected && active -> MaterialTheme.colorScheme.surfaceContainerHighest
        selected -> MaterialTheme.colorScheme.surfaceContainerHigh
        active -> MaterialTheme.colorScheme.surfaceContainer
        else -> Color.Transparent
    }
    LocalLettaHost.current.tooltip(spec.tooltip) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(background)
                .then(if (inherited == null) Modifier.hoverable(interaction.source, enabled = spec.enabled) else Modifier)
                .pointerHoverIcon(if (spec.enabled) PointerIcon.Hand else PointerIcon.Default)
                .semantics(mergeDescendants = spec.description != null) {
                    this.selected = selected
                    spec.description?.let { contentDescription = it }
                }
                .clickable(
                    interactionSource = interaction.source, indication = null,
                    enabled = spec.enabled, role = spec.role, onClick = onClick,
                )
                .alpha(if (spec.muted || !spec.enabled) LettaDimens.Alpha.disabled else 1f)
                .padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) { RowContents(spec, leading, end) }
    }
}

@Composable
private fun RowScope.RowContents(spec: LettaListRowSpec, leading: (@Composable () -> Unit)?, end: (@Composable () -> Unit)?) {
    val icon = spec.icon
    val trailing = spec.trailing
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
            modifier = if (spec.description != null) Modifier.clearAndSetSemantics {} else Modifier,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
    end?.invoke()
}

@Composable
private fun RowTitle(spec: LettaListRowSpec, modifier: Modifier) {
    val subtitle = spec.subtitle
    Column(modifier.testTag(LettaListRowDefaults.TitleTestTag)) {
        Text(
            text = spec.title,
            modifier = if (spec.description != null) Modifier.clearAndSetSemantics {} else Modifier,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (spec.emphasized) FontWeight.SemiBold else FontWeight.Normal,
            color = if (spec.quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = spec.titleLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                modifier = if (spec.description != null) Modifier.clearAndSetSemantics {} else Modifier,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = spec.subtitleLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
