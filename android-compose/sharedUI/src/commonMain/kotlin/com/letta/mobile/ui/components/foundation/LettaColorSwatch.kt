package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.tokens.LettaComponentDimens
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaStrokeTokens

/**
 * The one colour swatch: a circle of the swatch's colour with a hairline `outlineVariant` ring, or a
 * [LettaComponentDimens.swatchSelectedStroke] `primary` ring when selected. A fully transparent colour
 * shows a "none" glyph. Replaces the canvas and mascot pickers' own swatches, which used different
 * sizes and selected rings.
 *
 * With [onClick] it is a radio-style choice (role RadioButton, selected state; Compose extends its
 * touch bounds to the platform's 48 dp minimum without growing its layout); without, it only shows a
 * colour.
 */
@Composable
fun LettaColorSwatch(swatch: LettaSwatch, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val color = swatch.color
    val selected = swatch.selected
    val name = swatch.name
    val ring = if (selected) {
        Pair(LettaComponentDimens.swatchSelectedStroke, MaterialTheme.colorScheme.primary)
    } else {
        Pair(LettaStrokeTokens.hairline, MaterialTheme.colorScheme.outlineVariant)
    }
    val interaction = if (onClick == null) {
        Modifier
    } else {
        Modifier
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
    }
    Box(
        modifier = modifier
            .then(interaction)
            .size(swatch.size)
            .background(color, CircleShape)
            .border(ring.first, ring.second, CircleShape)
            .semantics { name?.let { contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        if (color.alpha == 0f) {
            val style = LettaIconStyle(LettaControlTokens.iconSm, MaterialTheme.colorScheme.outline)
            LettaIcon(LettaIcons.NoneSet, contentDescription = null, style = style)
        }
    }
}
