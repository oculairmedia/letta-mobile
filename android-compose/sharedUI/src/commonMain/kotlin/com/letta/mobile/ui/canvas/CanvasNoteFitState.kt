package com.letta.mobile.ui.canvas

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasTextStyle

/** The height of note [id] as shown, or none once it is not fitted (a group move reads these). */
internal fun MutableMap<String, Float>.putOrRemove(id: String, height: Float?) {
    if (height == null) remove(id) else put(id, height)
}

/**
 * One card's auto-fit (letta-mobile-bglj6.11), remembered per document by [CanvasNotesLayer]. The
 * stored frame of an AUTO note is a booking; what the card is shown at is [measured] by
 * [NoteFitMeasurer] and never written back. A person's move or resize of a fitted card takes it
 * over at the size and type it was SHOWN at; until that op lands, the card stays at what the person
 * left it at instead of refitting.
 */
@Stable
internal class NoteFitState {
    /** The last fit measured for the card's content, booking and width. */
    var measured: NoteFit? by mutableStateOf(null)
        private set

    /** The type scale a person's gesture took the card over at. */
    var takenScale by mutableStateOf(1f)
        private set

    /** A person moved or resized the fitted card; it is theirs until that op lands. */
    private var userTook by mutableStateOf(false)

    fun record(fit: NoteFit) {
        if (measured != fit) measured = fit
    }

    /** A note drawn as stored again (its frame no longer AUTO) is fitted afresh if it becomes AUTO. */
    fun resetFor(sizing: NoteSizing) {
        if (sizing == NoteSizing.VERBATIM) userTook = false
    }

    /** A person's gesture takes the card over at [fit], the type it was shown at. */
    fun takeOverAt(fit: NoteFit) {
        takenScale = fit.fontScale
    }

    fun markTaken() {
        userTook = true
    }

    /**
     * The fit as one composition of the card shows it, for its [sizing] and whether a gesture is on.
     * Equal from one composition to the next while nothing about it changed, so the card's gesture
     * callbacks that use it stay the same instances (a resize handle restarts on a new one).
     */
    fun shown(sizing: NoteSizing, gestureActive: Boolean): ShownNoteFit {
        val fits = sizing == NoteSizing.FIT
        val fitting = fits && !gestureActive && !userTook
        return ShownNoteFit(this, fits, fitting, measured.takeIf { fitting })
    }
}

/**
 * [state]'s fit as one composition shows it: [fits] when the card is auto-fitted at all,
 * [fitting] while it is shown at its measured [fit] (not while a person holds it).
 */
@Stable
internal data class ShownNoteFit(
    private val state: NoteFitState,
    val fits: Boolean,
    private val fitting: Boolean,
    /** The fit the card is shown at, or null while it is drawn at its frame. */
    val fit: NoteFit?,
) {
    /** The type scale the card is drawn at. */
    fun fontScale(): Float = when {
        !fits -> 1f
        fitting -> fit?.fontScale ?: 1f
        else -> state.takenScale
    }

    /** [base] scaled to the card's fit. */
    fun style(base: CanvasTextStyle?): CanvasTextStyle? = if (fits) CanvasNoteAutoFit.scaledStyle(base, fontScale()) else base

    /** The height the card is shown at: its fit's, else its [frame]'s. */
    fun height(frame: CanvasDocumentFrame): Float = fit?.height ?: frame.height

    /** The height to report to the host: the measured one while the card is fitted, else null. */
    fun reportedHeight(): Float? = state.measured?.height?.takeIf { fits }

    /** A gesture starting on the fitted card: the height it starts from, its type taken over; null when not fitted. */
    fun takeOver(): Float? {
        val shownFit = fit ?: return null
        state.takeOverAt(shownFit)
        return shownFit.height
    }

    /** The style a fitted card set smaller to fit keeps once a person takes it over; null when it has none. */
    fun takenStyle(base: CanvasTextStyle?): CanvasTextStyle? =
        CanvasNoteAutoFit.scaledStyle(base, state.takenScale).takeIf { fits && state.takenScale != 1f }

    /** A committed gesture: a fitted card a person [moved] is theirs until that op lands. */
    fun settle(moved: Boolean) {
        if (fits && moved) state.markTaken()
    }
}
