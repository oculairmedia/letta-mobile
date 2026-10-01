package com.letta.mobile.ui.chat.surface.sendflight

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Where one flight is in its choreography. */
enum class SendFlightPhase {
    /** Sent; the ghost holds at the prompt until its row lays out. */
    Awaiting,

    /** The row has room and the ghost travels to it. */
    Flying,

    /** Landed: the ghost cross-fades into the real row. */
    HandingOff,

    /** No row came in time: the ghost fades where it is. */
    Abandoned,
}

/**
 * letta-mobile-cc25e: one sent prompt in flight from the composer to its timeline row.
 *
 * Bounds are in the [SendFlightState] layer's coordinates. The animated values are snapshot
 * state written by the choreography ([flySendFlight]) and read in layout/draw only.
 */
@Stable
class SendFlight internal constructor(
    val id: Int,
    val text: String,
    val source: Rect,
) {
    var phase: SendFlightPhase by mutableStateOf(SendFlightPhase.Awaiting)
        internal set

    /** The claimed row's current bounds; follows it while the list scrolls or makes room. */
    var target: Rect? by mutableStateOf(null)
        internal set

    /** 0 at the prompt, 1 on the row. */
    var progress: Float by mutableFloatStateOf(0f)
        internal set

    var ghostAlpha: Float by mutableFloatStateOf(1f)
        internal set

    /** The real row's alpha: hidden while the ghost stands in for it. */
    var rowAlpha: Float by mutableFloatStateOf(0f)
        internal set

    /** Share of the row's height the timeline has opened for it so far. */
    var insert: Float by mutableFloatStateOf(0f)
        internal set

    internal var claimant: SendFlightRowKey? = null
}

/**
 * letta-mobile-cc25e: the send-flight coordinator, held by the chat page.
 *
 * The composer's field reports its bounds ([reportSource]); a send [launch]es a flight with
 * the draft; the first user-prompt row composed after the send whose text matches claims it
 * ([reportTarget]); the page's layer draws the ghost and runs the choreography. Nothing here
 * gates the send: a flight is decoration over it.
 */
@Stable
class SendFlightState {
    var flight: SendFlight? by mutableStateOf(null)
        private set

    /** The layer's top-left in root coordinates, so root bounds convert to layer bounds. */
    internal var layerOrigin: Offset = Offset.Zero

    private var sourceBounds: Rect? = null

    /** Bumped per launch; a row remembers it when first composed to tell old rows from new. */
    internal var generation: Int = 0
        private set

    internal fun reportSource(boundsInRoot: Rect) {
        sourceBounds = boundsInRoot
    }

    /** Starts a flight for [draft] from the last reported field bounds. False when there is none. */
    fun launch(draft: String): Boolean {
        val text = draft.trim()
        val source = sourceBounds
        if (text.isEmpty() || source == null || source.isEmpty) return false
        generation += 1
        flight = SendFlight(generation, text, source.translate(-layerOrigin))
        return true
    }

    /**
     * A user-prompt row laid out. It claims the waiting flight when it was composed after the
     * send ([bornAt]) and shows the sent text; the claimant then keeps the target current.
     */
    internal fun reportTarget(row: SendFlightRow, boundsInRoot: Rect) {
        val current = flight ?: return
        if (current.claimant == null && row.canClaim(current)) current.claimant = row.key
        if (current.claimant === row.key) current.target = boundsInRoot.translate(-layerOrigin)
    }

    internal fun rowAlpha(key: SendFlightRowKey): Float = claimed(key)?.rowAlpha ?: 1f

    internal fun rowInsert(key: SendFlightRowKey): Float = claimed(key)?.insert ?: 1f

    internal fun finish(done: SendFlight) {
        if (flight === done) flight = null
    }

    private fun claimed(key: SendFlightRowKey): SendFlight? = flight?.takeIf { it.claimant === key }
}

/** One composed prompt row's identity; compared by reference, so a recomposed slot is a new row. */
internal class SendFlightRowKey

/** One user-prompt row as the flight sees it. */
internal class SendFlightRow(val key: SendFlightRowKey, val bornAt: Int, val text: String) {
    fun canClaim(flight: SendFlight): Boolean =
        flight.phase == SendFlightPhase.Awaiting && bornAt >= flight.id && text.trim() == flight.text
}

/** The page's coordinator; null outside a chat page, where sources and targets do nothing. */
val LocalSendFlight = staticCompositionLocalOf<SendFlightState?> { null }
