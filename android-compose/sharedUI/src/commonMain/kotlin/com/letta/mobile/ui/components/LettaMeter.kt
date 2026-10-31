package com.letta.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.ui.text.LettaSelectionContainer
import com.letta.mobile.ui.theme.LettaDimens

/**
 * A labelled single-value bar: [label], then [value] ("801 / 1,000,000 tokens"), over a track
 * [fraction] full in [colour]. An unknown fraction draws the empty track; a sliver still shows a
 * dot's worth. The track takes the context bar's radius and surface.
 */
@Composable
internal fun LettaMeter(label: String, value: String, fraction: Float?, colour: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        LettaMeterTrack(fraction, colour)
    }
}

@Composable
private fun LettaMeterTrack(fraction: Float?, colour: Color) {
    val shape = RoundedCornerShape(LettaDimens.Radius.sm)
    Box(Modifier.fillMaxWidth().height(LettaDimens.Space.xs).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val filled = fraction ?: 0f
        if (filled > 0f) {
            Box(Modifier.fillMaxWidth(filled).widthIn(min = LettaDimens.Space.xs).fillMaxHeight().clip(shape).background(colour))
        }
    }
}

/** A label/value line for an inspector ("Cron  0 9 * * *"); the value is selectable. */
@Composable
internal fun LettaFactRow(label: String, value: String, modifier: Modifier = Modifier, monospace: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        LettaFactLabel(label)
        LettaSelectionContainer(Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = if (monospace) FontFamily.Monospace else null)
        }
    }
}

/** The fixed-width label column of a [LettaFactRow], for rows that draw their own value. */
@Composable
internal fun LettaFactLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(LettaDimens.Space.xxl * 2),
    )
}

/** A small round colour swatch: a legend key, a status light, a kind marker. */
@Composable
internal fun LettaSwatch(colour: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(LettaDimens.Space.sm).clip(CircleShape).background(colour))
}
