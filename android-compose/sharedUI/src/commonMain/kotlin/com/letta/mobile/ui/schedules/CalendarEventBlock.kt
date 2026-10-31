package com.letta.mobile.ui.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.LoaderCircle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.SkipForward
import com.letta.mobile.ui.theme.LettaDimens

/**
 * How strongly a calendar block reads: [Upcoming] is the default, [Highlighted] picks out one block
 * (the next run), [Past] has happened, [Faded] will not happen as shown (paused).
 */
enum class CalendarTone { Upcoming, Highlighted, Past, Faded }

/** The outcome glyphs calendar blocks and rows carry: the Lucide glyphs LettaIcons maps for the same meanings. */
object CalendarGlyphs {
    val Succeeded: ImageVector get() = Lucide.CircleCheck
    val Failed: ImageVector get() = Lucide.CircleAlert
    val Skipped: ImageVector get() = Lucide.SkipForward
    val Unknown: ImageVector get() = Lucide.CircleHelp
    val Running: ImageVector get() = Lucide.LoaderCircle
}

/**
 * An event owner's accent (an agent's identity colour) by id, for hosts that know colours the
 * calendar cannot derive; null leaves the caller's fallback.
 */
val LocalCalendarAccent = staticCompositionLocalOf<(String) -> Color?> { { null } }

/** A small status glyph at a block's end: an outcome, a warning. */
@Immutable
data class CalendarBadge(val icon: ImageVector, val tint: Color, val description: String)

/**
 * A calendar block: an [accent] stripe and wash (an agent's colour, a calendar's colour), an
 * optional [leading] time, the [title], an optional [trailing] note (a count) and a [badge].
 * [description] is what assistive technology reads for the whole block.
 */
@Immutable
data class CalendarEventSpec(
    val title: String,
    val accent: Color,
    val tone: CalendarTone = CalendarTone.Upcoming,
    val leading: String? = null,
    val trailing: String? = null,
    val badge: CalendarBadge? = null,
    val description: String = title,
)

/**
 * One event on a calendar: a time-grid block, an all-day band, a month-cell line. The caller sizes
 * it ([CalendarTimeGrid] fixes it to [CalendarDimens.eventHeight]); the block fills what it is given.
 */
@Composable
fun CalendarEventBlock(spec: CalendarEventSpec, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(LettaDimens.Radius.sm / 2)
    val tone = spec.tone
    val highlighted = tone == CalendarTone.Highlighted
    Row(
        modifier
            .alpha(if (tone == CalendarTone.Faded) LettaDimens.Alpha.disabled else 1f)
            .clip(shape)
            .background(spec.accent.copy(alpha = tone.washAlpha))
            .then(if (highlighted) Modifier.border(LettaDimens.Stroke.hairline, spec.accent, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .clearAndSetSemantics {
                contentDescription = spec.description
                if (onClick != null) role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Box(Modifier.width(CalendarDimens.eventStripe).fillMaxHeight().background(spec.accent.copy(alpha = tone.stripeAlpha)))
        BlockText(spec, Modifier.weight(1f))
        spec.badge?.let { badge ->
            Icon(badge.icon, contentDescription = null, tint = badge.tint, modifier = Modifier.size(LettaDimens.Control.iconSm))
        }
        if (spec.badge != null || spec.trailing != null) Box(Modifier.width(LettaDimens.Space.hair))
    }
}

@Composable
private fun BlockText(spec: CalendarEventSpec, modifier: Modifier) {
    val past = spec.tone == CalendarTone.Past || spec.tone == CalendarTone.Faded
    val color = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        spec.leading?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            spec.title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        spec.trailing?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(end = LettaDimens.Space.hair),
            )
        }
    }
}

private val CalendarTone.washAlpha: Float
    get() = when (this) {
        CalendarTone.Upcoming -> UPCOMING_WASH
        CalendarTone.Highlighted -> HIGHLIGHT_WASH
        CalendarTone.Past, CalendarTone.Faded -> PAST_WASH
    }

private val CalendarTone.stripeAlpha: Float
    get() = if (this == CalendarTone.Past || this == CalendarTone.Faded) PAST_STRIPE else 1f

private const val UPCOMING_WASH = 0.24f
private const val HIGHLIGHT_WASH = 0.34f
private const val PAST_WASH = 0.10f
private const val PAST_STRIPE = 0.5f
