package com.letta.mobile.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion

/**
 * Visual emphasis tier for [DisclosureChevron].
 */
enum class ChevronEmphasis {
    Neutral,
    Emphasized,
}

/**
 * Destination or affordance role indicated by [DisclosureChevron].
 *
 * [Expansion] animates rotation between 0° (collapsed) and 180° (expanded).
 * [Sheet] displays a static 0° chevron indicating detail sheet presentation.
 */
enum class ChevronIndication {
    Expansion,
    Sheet,
}

/**
 * Token values and resolution helpers for [DisclosureChevron].
 */
object DisclosureChevronDefaults {
    const val TestTag: String = "letta_disclosure_chevron"
    const val RotationCollapsed: Float = 0f
    const val RotationExpanded: Float = 180f
    const val AnimationDurationMillis: Int = 200

    const val TintNeutralAlpha: Float = 0.8f
    const val TintDisabledAlpha: Float = 0.4f

    /**
     * Resolves target rotation in degrees for the given indication mode and state.
     */
    fun targetRotation(indicates: ChevronIndication, expanded: Boolean): Float = when (indicates) {
        ChevronIndication.Sheet -> RotationCollapsed
        ChevronIndication.Expansion -> if (expanded) RotationExpanded else RotationCollapsed
    }

    /**
     * Resolves sizing token according to [compact] mode.
     */
    fun size(compact: Boolean): Dp =
        if (compact) LettaDimens.Control.iconSm else LettaDimens.Control.icon

    /**
     * Resolves animation spec honoring reduced motion.
     */
    fun animationSpec(
        indicates: ChevronIndication,
        reducedMotion: Boolean,
    ): AnimationSpec<Float> = if (indicates == ChevronIndication.Sheet || reducedMotion) {
        snap()
    } else {
        tween(
            durationMillis = AnimationDurationMillis,
            easing = FastOutSlowInEasing,
        )
    }

    /**
     * Resolves tint color given enabled state and emphasis tier.
     */
    fun tint(
        enabled: Boolean,
        emphasis: ChevronEmphasis,
        onSurfaceVariant: Color,
        primary: Color,
    ): Color = when {
        !enabled -> onSurfaceVariant.copy(alpha = TintDisabledAlpha)
        emphasis == ChevronEmphasis.Emphasized -> primary
        else -> onSurfaceVariant.copy(alpha = TintNeutralAlpha)
    }

    /**
     * Resolves default content description when none is explicitly supplied.
     */
    fun contentDescription(
        indicates: ChevronIndication,
        expanded: Boolean,
    ): String = when (indicates) {
        ChevronIndication.Sheet -> "Open details"
        ChevronIndication.Expansion -> if (expanded) "Collapse" else "Expand"
    }
}

/**
 * Unified disclosure chevron affordance for expandable content, accordions, and sheet triggers.
 *
 * @param expanded Whether the target section or container is currently expanded.
 * @param modifier Caller layout modifiers. Row-specific geometry tests keep tagging via caller modifier.
 * @param enabled When false, renders at reduced opacity (0.4 alpha) overriding emphasis.
 * @param emphasis Visual emphasis tier ([ChevronEmphasis.Neutral] or [ChevronEmphasis.Emphasized]).
 * @param indicates Destination role ([ChevronIndication.Expansion] animated rotation vs [ChevronIndication.Sheet] static).
 * @param compact When true, renders at [LettaDimens.Control.iconSm] (13dp); otherwise [LettaDimens.Control.icon] (18dp).
 * @param contentDescription Optional accessibility label override. When null, derived from state.
 */
@Composable
fun DisclosureChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasis: ChevronEmphasis = ChevronEmphasis.Neutral,
    indicates: ChevronIndication = ChevronIndication.Expansion,
    compact: Boolean = false,
    contentDescription: String? = null,
) {
    val isSheet = indicates == ChevronIndication.Sheet
    val reducedMotion = LocalReducedMotion.current

    val targetRot = DisclosureChevronDefaults.targetRotation(indicates, expanded)
    val spec = DisclosureChevronDefaults.animationSpec(indicates, reducedMotion)

    val animatedRotation by animateFloatAsState(
        targetValue = targetRot,
        animationSpec = spec,
        label = "disclosure_chevron_rotation",
    )

    val rotation = if (isSheet) DisclosureChevronDefaults.RotationCollapsed else animatedRotation

    val tint = DisclosureChevronDefaults.tint(
        enabled = enabled,
        emphasis = emphasis,
        onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
        primary = MaterialTheme.colorScheme.primary,
    )

    val effectiveContentDescription = contentDescription
        ?: DisclosureChevronDefaults.contentDescription(indicates, expanded)

    val iconSize = DisclosureChevronDefaults.size(compact)

    Icon(
        imageVector = LettaIcons.ExpandMore,
        contentDescription = effectiveContentDescription,
        tint = tint,
        modifier = modifier
            .size(iconSize)
            .rotate(rotation)
            .testTag(DisclosureChevronDefaults.TestTag),
    )
}
