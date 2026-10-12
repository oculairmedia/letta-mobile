package com.letta.mobile.ui.host

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline

/** One overlay's identity in its [OverlayHost]; a replaced overlay keeps it. */
@JvmInline
value class OverlayId(val value: Long)

/** How much an overlay matters next to the others in the queue. */
enum class OverlayPriority { Low, Normal, High, Critical }

/** A banner's colouring. */
enum class OverlayTone { Info, Error }

/** The standard times on screen. */
object OverlayDurations {
    const val SHORT: Long = 4_000L
    const val LONG: Long = 10_000L
}

/** An overlay's one action ("Undo", "Retry", "Restart"). */
class OverlayAction(val label: String, val onAction: () -> Unit)

/** How an overlay sits in the queue and on screen. */
@Immutable
data class OverlayPresentation(
    val priority: OverlayPriority = OverlayPriority.Normal,
    /** Time on screen; null stays until dismissed, acted on or withdrawn. */
    val durationMillis: Long? = OverlayDurations.SHORT,
    /** Shows a close control. */
    val dismissible: Boolean = false,
    val tone: OverlayTone = OverlayTone.Info,
) {
    companion object {
        /** A transient snackbar. */
        val Short = OverlayPresentation()

        /** A snackbar that stays longer (an error worth reading). */
        val Long = OverlayPresentation(durationMillis = OverlayDurations.LONG, dismissible = true)

        /** Stays until its owner withdraws it or the person puts it away. */
        val Sticky = OverlayPresentation(durationMillis = null, dismissible = true)

        /** A banner that mirrors its owner's state until withdrawn. */
        val Banner = OverlayPresentation(durationMillis = null)
    }
}

/** What leaving does: through [action], or any other way ([onDismissed]). */
class OverlayResponse(val action: OverlayAction? = null, val onDismissed: () -> Unit = {}) {
    companion object {
        val None = OverlayResponse()
    }
}

/** What a surface asks the [OverlayHost] to show. */
sealed class Overlay(
    val message: String,
    /** Overlays with the same key are one overlay: a newer one replaces the older. Null keys by kind and message. */
    val key: String?,
    val presentation: OverlayPresentation,
    val response: OverlayResponse,
) {
    val priority: OverlayPriority get() = presentation.priority
    val durationMillis: Long? get() = presentation.durationMillis
    val dismissible: Boolean get() = presentation.dismissible
    val action: OverlayAction? get() = response.action

    internal val dedupeKey: String get() = key ?: "${kindName()}:$message"

    private fun kindName(): String = when (this) {
        is Snackbar -> "snackbar"
        is Banner -> "banner"
    }

    /** A transient message at the foot of the window (or the surface's [OverlaySnackbarSlot]). */
    class Snackbar(
        message: String,
        key: String? = null,
        presentation: OverlayPresentation = OverlayPresentation.Short,
        response: OverlayResponse = OverlayResponse.None,
    ) : Overlay(message, key, presentation, response)

    /** A notice across the top of the window: a runtime that is down, a connection lost. */
    class Banner(
        message: String,
        key: String? = null,
        presentation: OverlayPresentation = OverlayPresentation.Banner,
        response: OverlayResponse = OverlayResponse.None,
    ) : Overlay(message, key, presentation, response)

    companion object {
        /**
         * An undo offer: [onUndo] when the person takes it; [onCommit] when it times out or is
         * dismissed, so the change stands.
         */
        fun undo(message: String, key: String, onUndo: () -> Unit, onCommit: () -> Unit): Snackbar = Snackbar(
            message = message,
            key = key,
            presentation = OverlayPresentation(OverlayPriority.High, OverlayDurations.LONG, dismissible = true),
            response = OverlayResponse(OverlayAction(UNDO_LABEL, onUndo), onDismissed = onCommit),
        )

        const val UNDO_LABEL = "Undo"
    }
}

/** One overlay the host holds, with when it reached the screen. */
@Immutable
class OverlayEntry internal constructor(val id: OverlayId, val overlay: Overlay, internal val shownAt: Long) {
    internal fun expired(now: Long): Boolean = overlay.durationMillis?.let { now - shownAt >= it } ?: false

    /** Milliseconds left on screen at [now], or null when it is not timed. */
    internal fun remaining(now: Long): Long? = overlay.durationMillis?.let { shownAt + it - now }

    internal fun with(overlay: Overlay, shownAt: Long = this.shownAt) = OverlayEntry(id, overlay, shownAt)
}

/** What [OverlayHost.show] returns: the owner's way to take its overlay back. */
class OverlayHandle internal constructor(private val host: OverlayHost, val id: OverlayId) {
    val isActive: Boolean get() = host.isActive(id)

    fun dismiss() = host.dismiss(id)

    fun withdraw() = host.withdraw(id)
}
