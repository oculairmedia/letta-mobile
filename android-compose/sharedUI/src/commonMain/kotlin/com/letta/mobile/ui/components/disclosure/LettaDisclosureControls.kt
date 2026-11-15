package com.letta.mobile.ui.components.disclosure

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.components.line.LineDisclosure

/*
 * The disclosure affordances built on [DisclosureChevron], so a screen places one instead of laying
 * out a chevron of its own:
 *
 *   LettaFoldButton        folds a dock, card or chat down toward the bottom of the screen
 *   LettaUnfoldButton      opens it back up from there
 *   LettaDisclosureHeader  a header line that toggles what it discloses, chevron at its end
 *   LettaMenuIndicator     the still "opens a menu" mark at the end of a chip or a picker button
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
 * the shared disclosure toggle: a button with an "Expand" / "Collapse" click label and an
 * "Expanded" / "Collapsed" state, flipped by pointer, touch or keyboard. It is the same line the
 * chat timeline draws its disclosures with.
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
    DisclosureRow(
        disclosure = LineDisclosure(expanded = expanded, onToggle = onToggle),
        words = DisclosureRowWords(chevronDescription = chevronDescription),
        content = content,
    )
}

/**
 * The mark at the end of a control that opens a menu, a picker or a sheet (a composer chip, an
 * agent switcher, a chip that opens options): the [DisclosureChevron] in its still, sheet-opening
 * form, at the small glyph size unless [style] says otherwise. Decorative unless
 * [contentDescription] is given: the control it sits in already says what it opens.
 */
@Composable
fun LettaMenuIndicator(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    style: LettaMenuIndicatorStyle = LettaMenuIndicatorStyle.Default,
) {
    // The chevron would otherwise speak its own "Open details" inside the control's label.
    val decorative = if (contentDescription == null) Modifier.clearAndSetSemantics {} else Modifier
    DisclosureChevron(
        expanded = false,
        modifier = modifier.then(decorative),
        emphasis = style.emphasis,
        indicates = ChevronIndication.Sheet,
        compact = style.compact,
        contentDescription = contentDescription,
        tint = style.tint,
    )
}
