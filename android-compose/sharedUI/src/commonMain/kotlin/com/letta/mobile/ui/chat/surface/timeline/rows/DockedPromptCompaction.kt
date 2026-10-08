package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/**
 * How far a docked prompt's images shrink (letta-mobile-bglj6.1). The pinned copy of a prompt
 * provides one; the prompt's own row in the list never sees one, so it keeps its full size.
 */
@Stable
internal interface DockedPromptCompaction {
    /** The px the docked copy should give up right now. Read at measure time only, never in composition. */
    fun shrinkPx(): Int

    /** What the images actually gave up in this measure pass, so the copy knows its natural height. */
    fun reportShrink(px: Int)
}

/** The docked copy's compaction; null outside it (the list's rows). */
internal val LocalDockedPromptCompaction = staticCompositionLocalOf<DockedPromptCompaction?> { null }

/**
 * Scales its content down uniformly while docked, so a prompt's images become thumbnails no taller
 * than [cap], keeping their aspect ratio. The content is measured at its natural size and drawn
 * through a layer scaled from its top-start corner, so taps still land on the image under the
 * finger, and the size follows [DockedPromptCompaction.shrinkPx] at layout time: a scroll relayouts
 * the copy but never recomposes it.
 */
internal fun Modifier.dockedCompaction(compaction: DockedPromptCompaction?, cap: Dp): Modifier {
    if (compaction == null) return this
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val natural = placeable.height
        val budget = (natural - cap.roundToPx()).coerceAtLeast(0)
        val shrink = compaction.shrinkPx().coerceIn(0, budget)
        compaction.reportShrink(shrink)
        val scale = if (natural == 0) 1f else (natural - shrink).toFloat() / natural
        val width = (placeable.width * scale).roundToInt()
        // Scaled from the start edge: the placed box keeps the natural width, so in RTL it starts
        // that far left of the reported one.
        val rtl = layoutDirection == LayoutDirection.Rtl
        layout(width, natural - shrink) {
            placeable.placeWithLayer(if (rtl) width - placeable.width else 0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0f)
            }
        }
    }
}
