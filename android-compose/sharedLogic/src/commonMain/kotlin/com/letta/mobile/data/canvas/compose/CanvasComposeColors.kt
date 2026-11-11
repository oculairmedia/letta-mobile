package com.letta.mobile.data.canvas.compose

/**
 * The colours compose writes (plan section 3.2): a request names a JSON Canvas preset or `#rrggbb`,
 * and the board stores `#rrggbb`. Presets map onto the workspace's sticky-note tints (sharedUI
 * `NoteColors`, which sharedUI's CanvasComposeColorsTest holds these to), the closest pale tint
 * for each since the note palette has no red or cyan: red is its pink, cyan its blue, purple its
 * violet.
 */
object CanvasComposeColors {
    val PRESETS: Map<String, String> = mapOf(
        "red" to "#fbcfe8",
        "orange" to "#fed7aa",
        "yellow" to "#fde68a",
        "green" to "#bbf7d0",
        "cyan" to "#bfdbfe",
        "purple" to "#ddd6fe",
    )

    /** A CARD without a colour is white, so it reads as a card beside the tinted notes. */
    const val CARD_DEFAULT = "#ffffff"

    /** TEXT items and group labels (DrawBox `strokeColor` is the text colour). */
    const val TEXT = "#212121ff"
    const val GROUP_LABEL = "#424242ff"

    /** A group's frame: a light fill and a thin grey edge, drawn under its children. */
    const val GROUP_STROKE = "#9e9e9eff"
    const val GROUP_FILL = "#f5f5f5ff"

    /** A drawn item's frame (STAT, PROGRESS, CHART): white with a light edge, like a CARD. */
    const val DRAWING_FILL = "#ffffffff"
    const val DRAWING_STROKE = "#e0e0e0ff"

    /** Secondary text in a drawn item: labels, captions, chart axis labels. */
    const val MUTED = "#616161ff"

    /** A progress bar's empty track, a chart's baseline, and the accent its bars, line and fill take. */
    const val TRACK = "#e0e0e0ff"
    const val AXIS = "#9e9e9eff"
    const val ACCENT = "#1e88e5ff"

    /** The colour of [tone]: neutral grey, positive green, negative red, warning orange; [fallback] for none. */
    fun tone(tone: ComposeTone?, fallback: String): String = when (tone) {
        null -> fallback
        ComposeTone.NEUTRAL -> MUTED
        ComposeTone.POSITIVE -> "#2e7d32ff"
        ComposeTone.NEGATIVE -> "#c62828ff"
        ComposeTone.WARNING -> "#e65100ff"
    }

    /**
     * The stored colour for [requested] (already validated against [CanvasComposeContract.COLOR_PATTERN]):
     * a preset's tint or the hex in lower case; [fallback] when none was asked for (null is the
     * workspace's default note colour).
     */
    fun of(requested: String?, fallback: String? = null): String? =
        requested?.let { PRESETS[it] ?: it.lowercase() } ?: fallback
}
