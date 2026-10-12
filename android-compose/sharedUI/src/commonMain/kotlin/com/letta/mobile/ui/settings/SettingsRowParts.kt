package com.letta.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.settings.SettingsStatus
import com.letta.mobile.data.settings.SettingsTone
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/*
 * letta-mobile-ckmub.3.12.1: the one row anatomy every settings row shares. A label, a one-line
 * helper under it and a trailing value or control; the whole row is one semantic node and at least
 * LettaDimens.Control.minTouchTarget tall, so the row (not the little switch) is the hit target.
 */

/**
 * Why the rows in scope cannot be used now ("Needs a connected host"); null when they can. A locked
 * row is disabled and shows the reason in place of its helper.
 */
val LocalSettingsLock = compositionLocalOf<String?> { null }

/** What a locked row says while no host is connected. */
const val SETTINGS_NEEDS_HOST = "Needs a connected host"

/** A row's words: its label, its one-line helper, and whether it reads as destructive. */
@Immutable
internal data class RowWords(val title: String, val helper: String?, val destructive: Boolean = false)

/**
 * One settings row: [words] at the start, [trailing] at the end. [interaction] is the row's
 * clickable or toggleable modifier (or none); it goes on the whole row, which merges its children
 * into one node with the row's tag.
 */
@Composable
internal fun SettingsRowFrame(
    tag: String,
    words: RowWords,
    enabled: Boolean,
    interaction: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LettaDimens.Control.minTouchTarget)
            .then(interaction)
            .semantics(mergeDescendants = true) {}
            .testTag(tag)
            .padding(vertical = LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        RowWordsColumn(words, enabled, Modifier.weight(1f))
        trailing()
    }
}

/** A row's label over its one-line helper. */
@Composable
internal fun RowWordsColumn(words: RowWords, enabled: Boolean, modifier: Modifier = Modifier) {
    val titleColor = when {
        words.destructive -> MaterialTheme.colorScheme.error
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
        Text(words.title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
        words.helper?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A status as a dot, a glyph for its tone and its words: never colour alone. */
@Composable
internal fun SettingsStatusBadge(status: SettingsStatus, modifier: Modifier = Modifier) {
    val color = status.tone.color(MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        val glyph = status.tone.glyph
        if (glyph != null) {
            Icon(glyph, contentDescription = null, tint = color, modifier = Modifier.size(LettaDimens.Control.iconSm))
        } else {
            Box(Modifier.size(LettaDimens.Space.sm).background(color, CircleShape))
        }
        Text(status.text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** A fixed-height placeholder row, so the page does not jump when the real rows arrive. */
@Composable
internal fun SettingsSkeletonRow(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = LettaDimens.Space.xs)
            .height(LettaDimens.Control.minTouchTarget - LettaDimens.Space.sm)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(LettaDimens.Radius.sm)),
    )
}

/** The glyph that says a tone in shape as well as colour; plain lines have none. */
internal val SettingsTone.glyph: ImageVector?
    get() = when (this) {
        SettingsTone.Normal -> null
        SettingsTone.Positive -> LettaIcons.CheckCircle
        SettingsTone.Warning -> LettaIcons.Warning
        SettingsTone.Error -> LettaIcons.Error
    }

@Composable
internal fun SettingsTone.color(normal: Color): Color = when (this) {
    SettingsTone.Normal -> normal
    SettingsTone.Positive -> MaterialTheme.colorScheme.primary
    SettingsTone.Warning -> MaterialTheme.colorScheme.tertiary
    SettingsTone.Error -> MaterialTheme.colorScheme.error
}
