package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.letta.mobile.ui.theme.tokens.LettaTextAccents
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens

/**
 * The one filter / segment / toggle chip (the sidebar's Active / Archived / All, a page's view
 * switch, a legend that filters, a canvas panel's options). [active] draws it raised and semibold.
 * [leading] is a small mark before the words (a legend's [LettaSwatch]); a chip whose mark says it
 * all (a colour target's swatch and glyph) passes null [text] and names itself through [modifier]'s
 * semantics. [role] is [Role.Tab] for one-of-many (announced selected) and [Role.Checkbox] for a chip
 * that toggles on its own (announced checked).
 */
@Composable
fun LettaChipTab(
    text: String?,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.Tab,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .lettaClickable(role = role, target = LettaClickTarget.Inline, onClick = onClick)
            .semantics { if (role == Role.Checkbox) toggleableState = ToggleableState(active) else selected = active }
            .padding(horizontal = LettaSpaceTokens.md, vertical = LettaSpaceTokens.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaSpaceTokens.xs),
    ) {
        leading?.invoke()
        text?.let {
            Text(
                text = it,
                style = if (active) LettaTextAccents.labelMediumSemibold else LettaTextAccents.labelMediumRegular,
                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * A row of [LettaChipTab]s at the one chip spacing: a page's view switch or its filters. It scrolls
 * sideways when the page is too narrow for every chip.
 */
@Composable
fun LettaChipTabRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LettaSpaceTokens.sm),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
