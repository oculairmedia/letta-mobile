package com.letta.mobile.data.canvas.compose

import kotlin.math.round

/*
 * STAT and PROGRESS, drawn from board elements in a frame ([drawnParts]).
 *
 * A STAT stacks its label (muted, 14), its value (32, or 24 then 18 when it would wrap), its delta
 * in its tone's colour (14) and its caption (muted, 12) in a STAT_WIDTH frame.
 *
 * A PROGRESS bar sets its label (14) with the percentage right-aligned on the same row, then the
 * bar (a light track with the done part filled in its tone's colour, the accent blue by default)
 * and its caption (muted, 12), in a PROGRESS_WIDTH frame.
 */

internal const val STAT_LABEL_FONT = 14.0
internal const val STAT_DELTA_FONT = 14.0
internal const val STAT_CAPTION_FONT = 12.0

/** A STAT value's sizes, largest first: the first it fits on one line at, else the last. */
internal val STAT_VALUE_FONTS: List<Double> = listOf(32.0, 24.0, 18.0)

internal const val PROGRESS_LABEL_FONT = 14.0
internal const val PROGRESS_CAPTION_FONT = 12.0
internal const val PROGRESS_PERCENT_WIDTH = 56f
internal const val PROGRESS_ROW_GAP = 8f
internal const val PROGRESS_TRACK_HEIGHT = 10f
private const val PERCENT = 100.0

private val statInner: Float get() = CanvasComposeContract.STAT_WIDTH - 2 * DRAWING_PADDING
private val progressInner: Float get() = CanvasComposeContract.PROGRESS_WIDTH - 2 * DRAWING_PADDING

/** A STAT's texts, top to bottom, by part. */
private fun statTexts(stat: ComposeItem.Stat): List<Pair<String, TextLook>> = buildList {
    add("label" to drawnLook(stat.label, STAT_LABEL_FONT, CanvasComposeColors.MUTED))
    add("value" to drawnLook(stat.value, statValueFont(stat.value), CanvasComposeColors.TEXT))
    stat.delta?.takeIf { it.isNotBlank() }?.let {
        add("delta" to drawnLook(it, STAT_DELTA_FONT, CanvasComposeColors.tone(stat.tone, CanvasComposeColors.MUTED)))
    }
    stat.caption?.takeIf { it.isNotBlank() }?.let { add("caption" to drawnLook(it, STAT_CAPTION_FONT, CanvasComposeColors.MUTED)) }
}

private fun statValueFont(value: String): Double =
    STAT_VALUE_FONTS.firstOrNull { fitsOneLine(value, it, statInner) } ?: STAT_VALUE_FONTS.last()

/** A STAT's height: its stacked texts and the padding round them. */
internal fun statHeight(stat: ComposeItem.Stat): Float = stackHeight(statTexts(stat).map { it.second }, statInner) + 2 * DRAWING_PADDING

/** A STAT's elements in [slot]: its frame, then its texts. */
internal fun statParts(stat: ComposeItem.Stat, slot: Slot): List<DrawnPart> =
    listOf(drawnFrame(slot)) + stackParts(statTexts(stat), statInner, slot.x + DRAWING_PADDING to slot.y + DRAWING_PADDING)

/** The percentage a PROGRESS bar shows: its value rounded to a whole percent. */
internal fun progressPercent(value: Double): String = "${round(value * PERCENT).toInt()}%"

private fun progressLabelWidth(): Float = progressInner - PROGRESS_PERCENT_WIDTH

private fun progressRowHeight(progress: ComposeItem.Progress): Float = maxOf(
    textHeight(progress.label, PROGRESS_LABEL_FONT, progressLabelWidth()),
    textHeight(progressPercent(1.0), PROGRESS_LABEL_FONT, PROGRESS_PERCENT_WIDTH),
)

private fun progressCaption(progress: ComposeItem.Progress): String? = progress.caption?.takeIf { it.isNotBlank() }

private fun progressCaptionBlock(progress: ComposeItem.Progress): Float =
    progressCaption(progress)?.let { PROGRESS_ROW_GAP + textHeight(it, PROGRESS_CAPTION_FONT, progressInner) } ?: 0f

/** A PROGRESS bar's height: its label row, its track, its caption and the padding round them. */
internal fun progressHeight(progress: ComposeItem.Progress): Float =
    2 * DRAWING_PADDING + progressRowHeight(progress) + PROGRESS_ROW_GAP + PROGRESS_TRACK_HEIGHT + progressCaptionBlock(progress)

/** A PROGRESS bar's elements in [slot]: its frame, its track and fill, then its label, percentage and caption. */
internal fun progressParts(progress: ComposeItem.Progress, slot: Slot): List<DrawnPart> {
    val left = slot.x + DRAWING_PADDING
    val top = slot.y + DRAWING_PADDING
    val row = progressRowHeight(progress)
    val trackTop = top + row + PROGRESS_ROW_GAP
    val filled = round(progressInner * progress.value.toFloat())
    val fill = CanvasComposeColors.tone(progress.tone, CanvasComposeColors.ACCENT)
    val labelWidth = progressLabelWidth()
    return buildList {
        add(drawnFrame(slot))
        add(drawnBar("track", Slot(left, trackTop, progressInner, PROGRESS_TRACK_HEIGHT), CanvasComposeColors.TRACK, DRAWING_BACK_Z))
        if (filled >= 1f) add(drawnBar("fill", Slot(left, trackTop, filled, PROGRESS_TRACK_HEIGHT), fill, DRAWING_MARK_Z))
        add(drawnText("label", Slot(left, top, labelWidth, row), drawnLook(progress.label, PROGRESS_LABEL_FONT, CanvasComposeColors.TEXT)))
        val percent = drawnLook(progressPercent(progress.value), PROGRESS_LABEL_FONT, CanvasComposeColors.MUTED)
        add(drawnText("percent", Slot(left + labelWidth, top, PROGRESS_PERCENT_WIDTH, row), percent, ALIGN_RIGHT))
        progressCaption(progress)?.let {
            val captionTop = trackTop + PROGRESS_TRACK_HEIGHT + PROGRESS_ROW_GAP
            val look = drawnLook(it, PROGRESS_CAPTION_FONT, CanvasComposeColors.MUTED)
            add(drawnText("caption", Slot(left, captionTop, progressInner, textHeight(it, PROGRESS_CAPTION_FONT, progressInner)), look))
        }
    }
}
