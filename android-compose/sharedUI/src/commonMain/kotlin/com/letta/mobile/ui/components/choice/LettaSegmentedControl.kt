package com.letta.mobile.ui.components.choice

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.ui.components.foundation.LettaIcon
import com.letta.mobile.ui.components.foundation.LettaIconStyle
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaStrokeTokens

/**
 * A single choice among a few options shown side by side, the selected one filled: a mode (theme:
 * System, Light, Dark), a view, a scope. For more than about four options, or long labels, use a
 * list or a picker instead. It fills the width it is given, each segment an equal share.
 *
 * [choice] says what is shown (see [LettaSegmentedChoice]); [onSelect] gets the picked segment's key.
 * Each segment is a radio button to assistive technology.
 */
@Composable
fun LettaSegmentedControl(choice: LettaSegmentedChoice, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        choice.segments.forEachIndexed { index, segment ->
            val selected = segment.key == choice.selected
            val tagged = choice.tagPrefix?.let { Modifier.testTag(it + segment.key) } ?: Modifier
            SegmentedButton(
                selected = selected,
                onClick = { if (!selected) onSelect(segment.key) },
                enabled = choice.enabled && segment.enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = choice.segments.size),
                colors = lettaSegmentColors(),
                border = SegmentedButtonDefaults.borderStroke(MaterialTheme.colorScheme.outlineVariant, LettaStrokeTokens.hairline),
                icon = { SegmentIcon(segment, selected, choice) },
                modifier = if (choice.iconOnly) tagged.semantics { contentDescription = segment.label } else tagged,
            ) {
                if (!choice.iconOnly) Text(segment.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The segment's own icon when it has one; otherwise the check Material puts on the selected segment. */
@Composable
private fun SegmentIcon(segment: LettaSegment, selected: Boolean, choice: LettaSegmentedChoice) {
    val icon = segment.icon
    if (icon != null) {
        LettaIcon(icon, contentDescription = null, style = LettaIconStyle(LettaControlTokens.icon))
    } else if (!choice.iconOnly && choice.showCheck) {
        SegmentedButtonDefaults.Icon(active = selected)
    }
}

/** The selected segment in the secondary container; the rest on the surface behind them. */
@Composable
private fun lettaSegmentColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    inactiveContainerColor = Color.Transparent,
    inactiveContentColor = MaterialTheme.colorScheme.onSurface,
)
