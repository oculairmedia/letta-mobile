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

/** The widest percentage a PROGRESS bar shows, which its label row is booked for. */
private const val FULL_PERCENT = "100%"

private val statInner: Float get() = CanvasComposeContract.STAT_WIDTH - 2 * DRAWING_PADDING
private val progressInner: Float get() = CanvasComposeContract.PROGRESS_WIDTH - 2 * DRAWING_PADDING
private val progressLabelWidth: Float get() = progressInner - PROGRESS_PERCENT_WIDTH

/** A STAT's texts, top to bottom, by part. */
private fun statTexts(stat: ComposeItem.Stat): List<Pair<String, TextLook>> = buildList {
    add("label" to TextLook(stat.label, STAT_LABEL_FONT, CanvasComposeColors.MUTED, DRAWING_TEXT_Z))
    add("value" to TextLook(stat.value, statValueFont(stat), CanvasComposeColors.TEXT, DRAWING_TEXT_Z))
    stat.delta?.takeIf { it.isNotBlank() }?.let {
        val color = CanvasComposeColors.tone(stat.tone, CanvasComposeColors.MUTED)
        add("delta" to TextLook(it, STAT_DELTA_FONT, color, DRAWING_TEXT_Z))
    }
    stat.caption?.takeIf { it.isNotBlank() }?.let {
        add("caption" to TextLook(it, STAT_CAPTION_FONT, CanvasComposeColors.MUTED, DRAWING_TEXT_Z))
    }
}

private fun statValueFont(stat: ComposeItem.Stat): Double = STAT_VALUE_FONTS.firstOrNull { font ->
    fitsOneLine(TextLook(stat.value, font, CanvasComposeColors.TEXT, DRAWING_TEXT_Z), statInner)
} ?: STAT_VALUE_FONTS.last()

/** A STAT's height: its stacked texts and the padding round them. */
internal fun statHeight(stat: ComposeItem.Stat): Float = stackHeight(statTexts(stat).map { it.second }, statInner) + 2 * DRAWING_PADDING

/** A STAT's elements in [slot]: its frame, then its texts. */
internal fun statParts(stat: ComposeItem.Stat, slot: Slot): List<DrawnPart> {
    val column = Slot(slot.x + DRAWING_PADDING, slot.y + DRAWING_PADDING, statInner, 0f)
    return listOf(drawnFrame(slot)) + stackParts(statTexts(stat), column)
}

/** The percentage [progress] shows: its value rounded to a whole percent. */
private fun progressPercent(progress: ComposeItem.Progress): String = "${round(progress.value * PERCENT).toInt()}%"

private fun progressLabel(progress: ComposeItem.Progress): TextLook =
    TextLook(progress.label, PROGRESS_LABEL_FONT, CanvasComposeColors.TEXT, DRAWING_TEXT_Z)

private fun progressRowHeight(progress: ComposeItem.Progress): Float = maxOf(
    textHeight(progressLabel(progress), progressLabelWidth),
    textHeight(TextLook(FULL_PERCENT, PROGRESS_LABEL_FONT, CanvasComposeColors.MUTED, DRAWING_TEXT_Z), PROGRESS_PERCENT_WIDTH),
)

/** [progress]'s caption as it is set, or null when it has none. */
private fun progressCaption(progress: ComposeItem.Progress): TextLook? = progress.caption?.takeIf { it.isNotBlank() }?.let {
    TextLook(it, PROGRESS_CAPTION_FONT, CanvasComposeColors.MUTED, DRAWING_TEXT_Z)
}

private fun progressCaptionBlock(progress: ComposeItem.Progress): Float =
    progressCaption(progress)?.let { PROGRESS_ROW_GAP + textHeight(it, progressInner) } ?: 0f

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
    return buildList {
        add(drawnFrame(slot))
        add(drawnBar("track" to Slot(left, trackTop, progressInner, PROGRESS_TRACK_HEIGHT), CanvasComposeColors.TRACK to DRAWING_BACK_Z))
        if (filled >= 1f) add(drawnBar("fill" to Slot(left, trackTop, filled, PROGRESS_TRACK_HEIGHT), fill to DRAWING_MARK_Z))
        add(drawnText("label" to Slot(left, top, progressLabelWidth, row), progressLabel(progress)))
        val percent = TextLook(progressPercent(progress), PROGRESS_LABEL_FONT, CanvasComposeColors.MUTED, DRAWING_TEXT_Z)
        add(drawnText("percent" to Slot(left + progressLabelWidth, top, PROGRESS_PERCENT_WIDTH, row), percent, ALIGN_RIGHT))
        progressCaption(progress)?.let {
            val captionTop = trackTop + PROGRESS_TRACK_HEIGHT + PROGRESS_ROW_GAP
            add(drawnText("caption" to Slot(left, captionTop, progressInner, textHeight(it, progressInner)), it))
        }
    }
}
