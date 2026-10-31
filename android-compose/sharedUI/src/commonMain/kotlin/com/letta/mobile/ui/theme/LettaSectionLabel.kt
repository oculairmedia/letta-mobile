package com.letta.mobile.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * Upper-case section label used by sidebars, lists, pickers and popovers ("PINNED", "EFFORT"): the
 * one section label. [contentPadding] is its inset, so a caller lines it up with its own gutter.
 */
@Composable
fun LettaSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = SectionLabelPadding,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = LettaDimens.Alpha.muted),
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(contentPadding),
    )
}

/**
 * A section's header row: its [LettaSectionLabel] ("TOOLS · 12" when [count] is given) and an
 * optional [action] at the end of the label's line (an add button, "See all", a menu).
 */
@Composable
fun LettaSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = LettaDimens.Space.md).semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LettaSectionLabel(lettaSectionTitle(title, count), Modifier.weight(1f), contentPadding = SectionHeaderLabelPadding)
        action?.invoke()
    }
}

/** "Tools · 12": the one way a section header writes its count. */
fun lettaSectionTitle(title: String, count: Int?): String = if (count == null) title else "$title · $count"

private val SectionLabelPadding = PaddingValues(top = LettaDimens.Space.md, start = LettaDimens.Space.xs, bottom = LettaDimens.Space.hair)

/** In a [LettaSectionHeader] the row carries the top inset, so the label and the action centre on one line. */
private val SectionHeaderLabelPadding = PaddingValues(start = LettaDimens.Space.xs, bottom = LettaDimens.Space.hair)
