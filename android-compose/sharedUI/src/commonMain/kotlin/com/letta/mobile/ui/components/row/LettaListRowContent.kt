package com.letta.mobile.ui.components.row

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.ui.theme.tokens.LettaRowDimens

@Composable
internal fun RowScope.RowContents(spec: LettaListRowSpec, leading: (@Composable () -> Unit)?, end: (@Composable () -> Unit)?) {
    val icon = spec.icon
    val trailing = spec.trailing
    if (leading != null) {
        leading()
    } else if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(LettaRowDimens.icon),
        )
    }
    RowTitle(spec, Modifier.weight(1f))
    if (trailing != null) {
        Text(
            text = trailing,
            modifier = textSemantics(spec),
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
    val textModifier = textSemantics(spec)
    val weight = if (spec.emphasized) FontWeight.SemiBold else FontWeight.Normal
    val color = if (spec.quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Column(modifier.testTag(LettaListRowDefaults.TitleTestTag)) {
        Text(
            text = spec.title,
            modifier = textModifier,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = weight,
            color = color,
            maxLines = spec.titleLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                modifier = textModifier,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = spec.subtitleLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun textSemantics(spec: LettaListRowSpec): Modifier =
    if (spec.description != null) Modifier.clearAndSetSemantics {} else Modifier
