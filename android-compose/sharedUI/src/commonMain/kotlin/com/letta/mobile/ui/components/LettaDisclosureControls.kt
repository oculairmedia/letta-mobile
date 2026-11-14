package com.letta.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.theme.LettaDimens

/*
 * The disclosure affordances built on [DisclosureChevron], so a screen places one instead of laying
 * out a chevron of its own:
 *
 *   LettaFoldButton        folds a dock, card or chat down toward the bottom of the screen
 *   LettaUnfoldButton      opens it back up from there
 *   LettaDisclosureHeader  a header line that toggles what it discloses, chevron at its end
 *
 * The still "opens a menu" mark is LettaMenuIndicator, beside the pills in the theme.
 */

/**
 * An icon button that folds an open dock, card or chat down toward the bottom of the screen: the
 * chevron points down. Size the button with [modifier].
 */
@Composable
fun LettaFoldButton(onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        DisclosureChevron(expanded = true, contentDescription = contentDescription, opensUpward = true)
    }
}

/**
 * An icon button that opens a folded dock, card or chat back up: the chevron points up. Size the
 * button with [modifier].
 *
 * @param glyphSize The chevron's size: the standard glyph unless the button sits among larger
 * action glyphs (the touch composer bar's), which it then matches.
 */
@Composable
fun LettaUnfoldButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    glyphSize: Dp = DisclosureChevronDefaults.size(compact = false),
) {
    IconButton(onClick = onClick, modifier = modifier) {
        DisclosureChevron(
            expanded = false,
            // The outer size wins over the chevron's own token size.
            modifier = Modifier.size(glyphSize),
            contentDescription = contentDescription,
            opensUpward = true,
        )
    }
}

/**
 * A full-width header line that expands and collapses what sits under it: [content] (the title,
 * and any state words such as "Details" / "Hide"), then the [DisclosureChevron]. The whole line is
 * the toggle and announces itself as a button.
 *
 * @param chevronDescription The chevron's label; null derives "Expand" / "Collapse" from [expanded].
 */
@Composable
fun LettaDisclosureHeader(
    expanded: Boolean,
    onToggle: () -> Unit,
    chevronDescription: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        content()
        DisclosureChevron(expanded = expanded, contentDescription = chevronDescription)
    }
}
